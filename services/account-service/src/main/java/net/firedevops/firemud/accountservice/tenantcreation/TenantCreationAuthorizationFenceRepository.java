package net.firedevops.firemud.accountservice.tenantcreation;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.OwnerReadback;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired CREATE_TENANT durable ordering/readback storage. Callers first authenticate and lock/
 * revalidate every real applicable source, then acquire the same sorted participation locks used by
 * Draft and source mutations. Do not retain any lock across RPC. Correlation is not authority; an
 * exact replay recovers the original authorization capture, never reopens owner permission.
 */
public final class TenantCreationAuthorizationFenceRepository {
  private static final String FENCES = "account_tenant_creation_authorization_fences";
  private static final String SOURCES = "account_tenant_creation_authorization_sources";
  private static final String READBACKS = "account_tenant_creation_authorization_readbacks";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "DSLContext is an internal caller-transaction persistence collaborator.")
  public TenantCreationAuthorizationFenceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public record FenceSnapshot(
      Ordering ordering, byte[] binding, OffsetDateTime reservedAt, OffsetDateTime orderedAt) {
    public FenceSnapshot {
      binding = binding.clone();
    }

    @Override
    public byte[] binding() {
      return binding.clone();
    }
  }

  public record OwnerResultSnapshot(Outcome outcome, byte[] readback, OffsetDateTime recordedAt) {
    public OwnerResultSnapshot {
      readback = readback.clone();
    }

    @Override
    public byte[] readback() {
      return readback.clone();
    }
  }

  public FenceSnapshot reserve(TenantCreationAuthorizationFenceBinding binding) {
    requireTransaction();
    lockSources(binding.sources());
    Record row = readOperation(binding.operationId());
    if (row != null) {
      return snapshot(requireExact(row, binding));
    }
    if (hasWaitingChange(binding.sources())) {
      throw new IllegalStateException("Applicable authority source change is waiting");
    }
    dsl.execute(
        "INSERT INTO "
            + FENCES
            + " (operation_id, request_id, fence_id, actor_account_id, tenant_id, creation_operation_id,"
            + " creation_request_digest, binding, ordering) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED')",
        binding.operationId(),
        binding.requestId(),
        binding.fenceId(),
        binding.actorAccountId(),
        binding.tenantId(),
        binding.creationOperationId(),
        binding.creationRequestDigest(),
        binding.canonicalBytes());
    for (SourceEvidence source : binding.sources()) {
      dsl.execute(
          "INSERT INTO "
              + SOURCES
              + " (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          binding.operationId(),
          source.key(),
          source.canonicalBytes());
    }
    return snapshot(requireExact(readOperation(binding.operationId()), binding));
  }

  /** Caller must revalidate real current source state before claiming this irrevocable order. */
  public FenceSnapshot claimCommitOrder(TenantCreationAuthorizationFenceBinding binding) {
    requireTransaction();
    lockSources(binding.sources());
    Record row = requireExact(readOperation(binding.operationId()), binding);
    Ordering ordering = Ordering.valueOf(row.get("ordering", String.class));
    if (ordering == Ordering.COMMIT_ORDER) {
      return snapshot(row);
    }
    if (ordering == Ordering.REVOKE_ORDER || hasWaitingChange(binding.sources())) {
      throw new IllegalStateException("Revocation precedes this tenant creation");
    }
    dsl.execute(
        "UPDATE "
            + FENCES
            + " SET ordering = 'COMMIT_ORDER', ordered_at = CURRENT_TIMESTAMP"
            + " WHERE operation_id = ? AND ordering = 'RESERVED'",
        binding.operationId());
    return snapshot(requireExact(readOperation(binding.operationId()), binding));
  }

  /** Original exact source correlation only; not a fresh permission or owner dispatch gate. */
  public FenceSnapshot read(TenantCreationAuthorizationFenceBinding binding) {
    requireTransaction();
    return snapshot(requireExact(readOperation(binding.operationId()), binding));
  }

  /** Read-only recovery of original provenance; absence is UNKNOWN and never no-commit proof. */
  public Optional<TenantCreationAuthorizationFenceBinding> readOriginalBinding(UUID operationId) {
    requireTransaction();
    Record row = readOperation(operationId);
    if (row == null) {
      return Optional.empty();
    }
    var binding =
        TenantCreationAuthorizationFenceBinding.fromStored(row.get("binding", byte[].class));
    if (!operationId.equals(binding.operationId())) {
      throw new IllegalStateException("Stored CREATE_TENANT operation identity differs");
    }
    requireExact(row, binding);
    return Optional.of(binding);
  }

  public void recordOwnerReadback(
      TenantCreationAuthorizationFenceBinding binding, OwnerReadback readback) {
    requireTransaction();
    readback.requireBinding(binding);
    Record row = requireExact(readOperation(binding.operationId()), binding);
    if (Ordering.RESERVED.name().equals(row.get("ordering", String.class))) {
      throw new IllegalStateException("Reservation alone cannot produce owner terminal evidence");
    }
    Record prior =
        dsl.fetchOne(
            "SELECT readback FROM " + READBACKS + " WHERE operation_id = ? AND owner = ?",
            binding.operationId(),
            readback.owner().name());
    if (prior != null) {
      if (!Arrays.equals(prior.get("readback", byte[].class), readback.canonicalBytes())) {
        throw new IllegalArgumentException("Changed owner terminal evidence");
      }
      return;
    }
    dsl.execute(
        "INSERT INTO "
            + READBACKS
            + " (operation_id, owner, owner_operation_id, outcome, readback, owner_result) VALUES (?, ?, ?, ?, ?, ?)",
        binding.operationId(),
        readback.owner().name(),
        readback.ownerOperationId(),
        readback.outcome().name(),
        readback.canonicalBytes(),
        readback.result());
  }

  public Optional<OwnerResultSnapshot> readOwnerResult(
      TenantCreationAuthorizationFenceBinding binding, Owner owner) {
    requireTransaction();
    requireExact(readOperation(binding.operationId()), binding);
    Record row =
        dsl.fetchOne(
            "SELECT outcome, readback, recorded_at FROM "
                + READBACKS
                + " WHERE operation_id = ? AND owner = ?",
            binding.operationId(),
            owner.name());
    return row == null
        ? Optional.empty()
        : Optional.of(
            new OwnerResultSnapshot(
                Outcome.valueOf(row.get("outcome", String.class)),
                row.get("readback", byte[].class),
                row.get("recorded_at", OffsetDateTime.class)));
  }

  public Settlement readSettlement(TenantCreationAuthorizationFenceBinding binding) {
    requireTransaction();
    Record row = requireExact(readOperation(binding.operationId()), binding);
    return settlement(binding.operationId(), Ordering.valueOf(row.get("ordering", String.class)));
  }

  /**
   * Existing source-change engine calls this under its shared sorted source locks, after locking
   * affected Draft operations. Commit order remains fixed; reserved creation is revoked forever.
   */
  public void revokeAffected(List<SourceEvidence> sources) {
    requireTransaction();
    lockSources(sources);
    for (UUID operation : affectedOperations(sources)) {
      Record row = readOperation(operation);
      if (Ordering.RESERVED.name().equals(row.get("ordering", String.class))) {
        dsl.execute(
            "UPDATE "
                + FENCES
                + " SET ordering = 'REVOKE_ORDER', ordered_at = CURRENT_TIMESTAMP"
                + " WHERE operation_id = ? AND ordering = 'RESERVED'",
            operation);
      }
    }
  }

  /** Settlement is source-order release only. Mixed outcomes cannot qualify usable tenant state. */
  public boolean allAffectedSettled(List<SourceEvidence> sources) {
    requireTransaction();
    lockSources(sources);
    for (UUID operation : affectedOperations(sources)) {
      Record row = readOperation(operation);
      if (settlement(operation, Ordering.valueOf(row.get("ordering", String.class)))
          == Settlement.PENDING) {
        return false;
      }
    }
    return true;
  }

  private Settlement settlement(UUID operation, Ordering ordering) {
    if (ordering == Ordering.RESERVED) {
      return Settlement.PENDING;
    }
    List<String> outcomes =
        dsl.fetch("SELECT outcome FROM " + READBACKS + " WHERE operation_id = ?", operation)
            .getValues("outcome", String.class);
    if (outcomes.size() != 2) {
      return Settlement.PENDING;
    }
    if (ordering == Ordering.REVOKE_ORDER) {
      return outcomes.stream().allMatch(Outcome.DEFINITIVELY_ABORTED.name()::equals)
          ? Settlement.FAILED_NONPUBLICATION
          : Settlement.PENDING;
    }
    return outcomes.stream().allMatch(Outcome.COMMITTED.name()::equals)
        ? Settlement.COMMITTED
        : Settlement.FAILED_NONPUBLICATION;
  }

  private List<UUID> affectedOperations(List<SourceEvidence> sources) {
    return sources.stream()
        .flatMap(
            source ->
                dsl
                    .fetch(
                        "SELECT operation_id FROM " + SOURCES + " WHERE source_key = ?",
                        source.key())
                    .getValues("operation_id", UUID.class)
                    .stream())
        .distinct()
        .sorted(Comparator.comparing(UUID::toString))
        .toList();
  }

  private void lockSources(List<SourceEvidence> sources) {
    for (String key : sources.stream().map(SourceEvidence::key).distinct().sorted().toList()) {
      dsl.execute(
          "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?) ON CONFLICT DO NOTHING",
          key);
      dsl.fetchOne(
          "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE",
          key);
    }
  }

  private boolean hasWaitingChange(List<SourceEvidence> sources) {
    for (SourceEvidence source : sources) {
      if (!dsl.fetch(
              "SELECT c.change_id FROM account_draft_authorization_source_changes c"
                  + " JOIN account_draft_authorization_changed_scopes s ON s.change_id = c.change_id"
                  + " WHERE s.source_key = ? AND c.status = 'WAITING'",
              source.key())
          .isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private Record readOperation(UUID operation) {
    return dsl.fetchOne(
        "SELECT * FROM " + FENCES + " WHERE operation_id = ? FOR UPDATE", operation);
  }

  private Record requireExact(Record row, TenantCreationAuthorizationFenceBinding binding) {
    if (row == null
        || !Arrays.equals(row.get("binding", byte[].class), binding.canonicalBytes())
        || !binding.requestId().equals(row.get("request_id", UUID.class))
        || !binding.fenceId().equals(row.get("fence_id", UUID.class))
        || !binding.actorAccountId().equals(row.get("actor_account_id", UUID.class))
        || !binding.tenantId().equals(row.get("tenant_id", UUID.class))
        || !binding.creationOperationId().equals(row.get("creation_operation_id", UUID.class))
        || !binding
            .creationRequestDigest()
            .equals(row.get("creation_request_digest", String.class))) {
      throw new IllegalArgumentException("Absent or changed immutable CREATE_TENANT operation");
    }
    List<Record> rows =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM "
                + SOURCES
                + " WHERE operation_id = ? ORDER BY source_key",
            binding.operationId());
    // Java canonical key ordering is independent of the database collation.
    rows =
        rows.stream().sorted(Comparator.comparing(r -> r.get("source_key", String.class))).toList();
    if (rows.size() != binding.sources().size()) {
      throw new IllegalStateException("Incomplete creation source participation");
    }
    for (int i = 0; i < rows.size(); i++) {
      SourceEvidence source = binding.sources().get(i);
      if (!source.key().equals(rows.get(i).get("source_key", String.class))
          || !Arrays.equals(
              source.canonicalBytes(), rows.get(i).get("source_evidence", byte[].class))) {
        throw new IllegalStateException("Source participation differs from exact creation binding");
      }
    }
    return row;
  }

  private FenceSnapshot snapshot(Record row) {
    return new FenceSnapshot(
        Ordering.valueOf(row.get("ordering", String.class)),
        row.get("binding", byte[].class),
        row.get("reserved_at", OffsetDateTime.class),
        row.get("ordered_at", OffsetDateTime.class));
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable caller Account transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Transaction-bound writable READ_COMMITTED connection required");
          }
        });
  }
}
