package net.firedevops.firemud.accountservice.authorpublication;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Unwired publication participation in Account's existing source-change order. Callers
 * authenticate, lock and revalidate the real authority sources in their writable owner transaction
 * before reserving or ordering; this component neither captures nor authenticates them. Never
 * retain this transaction across RPC. Reservation grants no permission. Draft/creation results
 * cannot settle publication; every ordered operation remains unresolved until its own authenticated
 * terminal contract and producer exist.
 */
public final class PublicationAuthorizationFenceRepository {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Internal transaction collaborator.")
  public PublicationAuthorizationFenceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public enum Ordering {
    RESERVED,
    PUBLICATION_ORDER,
    REVOKE_ORDER
  }

  public record Snapshot(
      Ordering ordering, byte[] binding, OffsetDateTime reservedAt, OffsetDateTime orderedAt) {
    public Snapshot {
      binding = binding.clone();
    }

    @Override
    public byte[] binding() {
      return binding.clone();
    }
  }

  public Snapshot reserve(AccountPublicationAuthorizationBinding binding) {
    requireTransaction();
    lockSources(binding.sources());
    Record existing = operation(binding.operationId());
    if (existing != null) {
      return snapshot(requireExact(existing, binding));
    }
    if (hasWaitingChange(binding.sources())) {
      throw new IllegalStateException("Applicable authority source change is waiting");
    }
    dsl.execute(
        "INSERT INTO account_publication_authorization_fences"
            + " (operation_id, tenant_id, publish_request_id, fence_id, actor_account_id, selection_json,"
            + " selection_digest, source_vector, binding, ordering)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'RESERVED')",
        binding.operationId(),
        binding.tenantId(),
        binding.publishRequestId(),
        binding.fenceId(),
        binding.input().actorAccountId(),
        binding.input().selection().canonicalJson(),
        binding.input().selection().digest(),
        new ObjectMapper()
            .writeValueAsString(
                binding.sources().stream()
                    .map(
                        source ->
                            Map.of(
                                "key",
                                source.key(),
                                "evidence",
                                HexFormat.of().formatHex(source.canonicalBytes())))
                    .toList()),
        binding.canonicalBytes());
    for (SourceEvidence source : binding.sources()) {
      dsl.execute(
          "INSERT INTO account_publication_authorization_sources"
              + " (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          binding.operationId(),
          source.key(),
          source.canonicalBytes());
    }
    return snapshot(requireExact(operation(binding.operationId()), binding));
  }

  /**
   * Revalidate actual locked source state first. An exact replay recovers order, not currentness.
   */
  public Snapshot claimPublicationOrder(AccountPublicationAuthorizationBinding binding) {
    requireTransaction();
    lockSources(binding.sources());
    Record row = requireExact(operation(binding.operationId()), binding);
    Ordering order = Ordering.valueOf(row.get("ordering", String.class));
    if (order == Ordering.PUBLICATION_ORDER) {
      return snapshot(row);
    }
    if (order == Ordering.REVOKE_ORDER || hasWaitingChange(binding.sources())) {
      throw new IllegalStateException("Revocation precedes this publication");
    }
    dsl.execute(
        "UPDATE account_publication_authorization_fences"
            + " SET ordering = 'PUBLICATION_ORDER', ordered_at = CURRENT_TIMESTAMP"
            + " WHERE operation_id = ? AND ordering = 'RESERVED'",
        binding.operationId());
    return snapshot(requireExact(operation(binding.operationId()), binding));
  }

  public Snapshot read(AccountPublicationAuthorizationBinding binding) {
    requireTransaction();
    return snapshot(requireExact(operation(binding.operationId()), binding));
  }

  /** Exact original evidence only. Absence is UNKNOWN, never definitive no-publication evidence. */
  public Optional<AccountPublicationAuthorizationBinding> readOriginalBinding(UUID operationId) {
    requireTransaction();
    Record row = operation(operationId);
    if (row == null) {
      return Optional.empty();
    }
    var binding =
        AccountPublicationAuthorizationBinding.fromStored(row.get("binding", byte[].class));
    requireExact(row, binding);
    return Optional.of(binding);
  }

  /** Called by the existing source-change writer under its shared source locks. */
  public void revokeAffected(List<SourceEvidence> sources) {
    requireTransaction();
    lockSources(sources);
    for (UUID id : affectedOperations(sources)) {
      Record row = operation(id);
      if (Ordering.RESERVED.name().equals(row.get("ordering", String.class))) {
        dsl.execute(
            "UPDATE account_publication_authorization_fences"
                + " SET ordering = 'REVOKE_ORDER', ordered_at = CURRENT_TIMESTAMP"
                + " WHERE operation_id = ? AND ordering = 'RESERVED'",
            id);
      }
    }
  }

  /**
   * No terminal shortcut exists: neither expiry, FAILED, nor Draft readback releases this order.
   */
  public boolean allAffectedSettled(List<SourceEvidence> sources) {
    requireTransaction();
    lockSources(sources);
    List<UUID> affected = affectedOperations(sources);
    for (UUID id : affected) {
      operation(id);
    }
    return affected.isEmpty();
  }

  private List<UUID> affectedOperations(List<SourceEvidence> sources) {
    return sources.stream()
        .flatMap(
            source ->
                dsl
                    .fetch(
                        "SELECT operation_id FROM account_publication_authorization_sources WHERE source_key = ?",
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
          "INSERT INTO account_draft_authorization_source_locks (source_key)"
              + " VALUES (?) ON CONFLICT DO NOTHING",
          key);
      dsl.fetchOne(
          "SELECT source_key FROM account_draft_authorization_source_locks"
              + " WHERE source_key = ? FOR UPDATE",
          key);
    }
  }

  private boolean hasWaitingChange(List<SourceEvidence> sources) {
    return sources.stream()
        .anyMatch(
            source ->
                !dsl.fetch(
                        "SELECT c.change_id FROM account_draft_authorization_source_changes c"
                            + " JOIN account_draft_authorization_changed_scopes s ON s.change_id = c.change_id"
                            + " WHERE s.source_key = ? AND c.status = 'WAITING'",
                        source.key())
                    .isEmpty());
  }

  private Record operation(UUID id) {
    return dsl.fetchOne(
        "SELECT * FROM account_publication_authorization_fences"
            + " WHERE operation_id = ? FOR UPDATE",
        id);
  }

  private Record requireExact(Record row, AccountPublicationAuthorizationBinding binding) {
    if (row == null
        || !binding.operationId().equals(row.get("operation_id", UUID.class))
        || !binding.tenantId().equals(row.get("tenant_id", UUID.class))
        || !binding.publishRequestId().equals(row.get("publish_request_id", String.class))
        || !binding.fenceId().equals(row.get("fence_id", UUID.class))
        || !Arrays.equals(binding.canonicalBytes(), row.get("binding", byte[].class))) {
      throw new IllegalArgumentException("Absent or changed immutable publication operation");
    }
    List<Record> stored =
        dsl
            .fetch(
                "SELECT source_key, source_evidence"
                    + " FROM account_publication_authorization_sources WHERE operation_id = ?",
                binding.operationId())
            .stream()
            .sorted(Comparator.comparing(source -> source.get("source_key", String.class)))
            .toList();
    if (stored.size() != binding.sources().size()) {
      throw new IllegalStateException("Incomplete publication source participation");
    }
    for (int i = 0; i < stored.size(); i++) {
      SourceEvidence source = binding.sources().get(i);
      if (!source.key().equals(stored.get(i).get("source_key", String.class))
          || !Arrays.equals(
              source.canonicalBytes(), stored.get(i).get("source_evidence", byte[].class))) {
        throw new IllegalStateException(
            "Publication source participation differs from original capture");
      }
    }
    return row;
  }

  private Snapshot snapshot(Record row) {
    return new Snapshot(
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
