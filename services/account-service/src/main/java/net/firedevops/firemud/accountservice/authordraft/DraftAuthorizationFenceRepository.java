package net.firedevops.firemud.accountservice.authordraft;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired durable ordering component. Every method requires a writable caller READ_COMMITTED
 * Account transaction. Callers lock/revalidate actual authority sources first, then this component
 * locks sorted source participation rows and sorted operations. Never hold these locks across RPC.
 * Reservation and returned source evidence alone grant no owner commit permission.
 *
 * <p>Future authenticated capture/source-change and owner-readback producers are mandatory. This
 * class does not authenticate supplied evidence or manufacture missing role/terms source state.
 */
public final class DraftAuthorizationFenceRepository {
  private static final String FENCES = "account_draft_authorization_fences";
  private static final String SOURCES = "account_draft_authorization_sources";
  private static final String READBACKS = "account_draft_authorization_owner_readbacks";
  private static final String CHANGES = "account_draft_authorization_source_changes";
  private static final String CHANGED = "account_draft_authorization_changed_scopes";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected DSLContext is an internal owner-transaction persistence collaborator.")
  public DraftAuthorizationFenceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public enum Ordering {
    RESERVED,
    COMMIT_ORDER,
    REVOKE_ORDER
  }

  public enum Settlement {
    PENDING,
    COMMITTED,
    FAILED_NONPUBLICATION
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

  public record SourceChangeSnapshot(
      UUID changeId,
      byte[] binding,
      String status,
      OffsetDateTime requestedAt,
      OffsetDateTime committedAt) {
    public SourceChangeSnapshot {
      binding = binding.clone();
    }

    @Override
    public byte[] binding() {
      return binding.clone();
    }
  }

  /** Full exact mutation intent and independently captured affected source vector. */
  public record SourceChange(UUID changeId, List<SourceEvidence> sources, byte[] mutation) {
    public SourceChange {
      DraftAuthorizationFenceBinding.requireUuid(changeId);
      sources =
          List.copyOf(Objects.requireNonNull(sources)).stream()
              .sorted(Comparator.comparing(SourceEvidence::key))
              .toList();
      if (sources.isEmpty()
          || sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
        throw new IllegalArgumentException("Distinct affected sources required");
      }
      mutation = DraftAuthorizationFenceBinding.bytes(mutation);
    }

    @Override
    public List<SourceEvidence> sources() {
      return List.copyOf(sources);
    }

    @Override
    public byte[] mutation() {
      return mutation.clone();
    }

    public byte[] canonicalBytes() {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, "account-draft-source-change/v1");
      DraftAuthorizationFenceBinding.frame(out, changeId.toString());
      DraftAuthorizationFenceBinding.frame(out, Integer.toString(sources.size()));
      for (SourceEvidence source : sources) {
        DraftAuthorizationFenceBinding.frame(out, source.canonicalBytes());
      }
      DraftAuthorizationFenceBinding.frame(out, mutation);
      return out.toByteArray();
    }

    /**
     * Exact recovery of the original vector; callers must never replace it with a fresh capture.
     */
    public static SourceChange fromStored(byte[] stored) {
      var reader = new DraftAuthorizationFenceBinding.FrameReader(stored);
      reader.expect("account-draft-source-change/v1");
      String id = reader.text();
      DraftAuthorizationFenceBinding.canonicalUuid(id);
      String count = reader.text();
      DraftAuthorizationFenceBinding.decimal(count, false);
      final int size;
      try {
        size = Integer.parseInt(count);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("Stored source count is out of range", invalid);
      }
      if (size > reader.remaining() / Integer.BYTES) {
        throw new IllegalArgumentException("Incomplete stored source vector");
      }
      java.util.ArrayList<SourceEvidence> sources = new java.util.ArrayList<>();
      for (int i = 0; i < size; i++) {
        sources.add(SourceEvidence.fromStored(reader.bytes()));
      }
      SourceChange change = new SourceChange(UUID.fromString(id), sources, reader.bytes());
      reader.requireEnd();
      if (!Arrays.equals(stored, change.canonicalBytes())) {
        throw new IllegalArgumentException("Noncanonical stored source change");
      }
      return change;
    }
  }

  /**
   * Already-existing source capture only. Future producer must establish completeness/currentness.
   */
  public FenceSnapshot reserve(DraftAuthorizationFenceBinding binding) {
    requireTransaction();
    lockSources(binding.sources());
    Record existing = readOperation(binding.operationId());
    if (existing != null) {
      return snapshot(requireExact(existing, binding));
    }
    if (hasWaitingChange(binding.sources())) {
      throw new IllegalStateException("Applicable authority source change is waiting");
    }
    dsl.execute(
        "INSERT INTO "
            + FENCES
            + " (operation_id, request_id, commit_id, fence_id, binding, ordering) VALUES (?, ?, ?, ?, ?, 'RESERVED')",
        binding.operationId(),
        binding.requestId(),
        binding.commitId(),
        binding.fenceId(),
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

  /**
   * Irrevocable exact operation order; revalidate actual source state under its locks before
   * calling.
   */
  public FenceSnapshot claimCommitOrder(DraftAuthorizationFenceBinding binding) {
    requireTransaction();
    lockSources(binding.sources());
    Record row = requireExact(readOperation(binding.operationId()), binding);
    Ordering ordering = Ordering.valueOf(row.get("ordering", String.class));
    if (ordering == Ordering.COMMIT_ORDER) {
      return snapshot(row);
    }
    if (ordering == Ordering.REVOKE_ORDER || hasWaitingChange(binding.sources())) {
      throw new IllegalStateException("Revocation precedes this owner commit");
    }
    dsl.execute(
        "UPDATE "
            + FENCES
            + " SET ordering = 'COMMIT_ORDER', ordered_at = CURRENT_TIMESTAMP"
            + " WHERE operation_id = ? AND ordering = 'RESERVED'",
        binding.operationId());
    return snapshot(requireExact(readOperation(binding.operationId()), binding));
  }

  /**
   * Commit this WAITING result even when false: rolling back would lose revocation intent. True
   * means settled or an exact SOURCE_COMMITTED replay; call sourceMutationPermitted before writing.
   */
  public boolean requestSourceChange(SourceChange change) {
    requireTransaction();
    lockSources(change.sources());
    Record prior = readChange(change.changeId());
    if (prior != null) {
      requireChange(prior, change);
      if ("SOURCE_COMMITTED".equals(prior.get("status", String.class))) {
        return true;
      }
    } else {
      dsl.execute(
          "INSERT INTO " + CHANGES + " (change_id, binding, status) VALUES (?, ?, 'WAITING')",
          change.changeId(),
          change.canonicalBytes());
      for (SourceEvidence source : change.sources()) {
        dsl.execute(
            "INSERT INTO " + CHANGED + " (change_id, source_key) VALUES (?, ?)",
            change.changeId(),
            source.key());
      }
    }
    for (UUID operation : affectedOperations(change.sources())) {
      Record row = readOperation(operation);
      if ("RESERVED".equals(row.get("ordering", String.class))) {
        dsl.execute(
            "UPDATE "
                + FENCES
                + " SET ordering = 'REVOKE_ORDER', ordered_at = CURRENT_TIMESTAMP"
                + " WHERE operation_id = ? AND ordering = 'RESERVED'",
            operation);
      }
    }
    return allAffectedSettled(change.sources());
  }

  /** Verify before the first source mutation and retain the transaction through source commit. */
  public boolean sourceMutationPermitted(SourceChange change) {
    requireTransaction();
    lockSources(change.sources());
    Record row = readChange(change.changeId());
    requireChange(row, change);
    return "WAITING".equals(row.get("status", String.class))
        && allAffectedSettled(change.sources());
  }

  /**
   * Same transaction as actual source mutation/event/readback. No RPC or fake source write here.
   */
  public void markSourceCommitted(SourceChange change) {
    requireTransaction();
    lockSources(change.sources());
    Record row = readChange(change.changeId());
    requireChange(row, change);
    if ("SOURCE_COMMITTED".equals(row.get("status", String.class))) {
      return;
    }
    if (!allAffectedSettled(change.sources())) {
      throw new IllegalStateException("Owner outcomes unresolved");
    }
    dsl.execute(
        "UPDATE "
            + CHANGES
            + " SET status = 'SOURCE_COMMITTED', committed_at = CURRENT_TIMESTAMP"
            + " WHERE change_id = ? AND status = 'WAITING'",
        change.changeId());
  }

  /** Exact durable owner readback, supplied only by the future authenticated owner verifier. */
  public void recordOwnerReadback(DraftAuthorizationFenceBinding binding, OwnerReadback readback) {
    requireTransaction();
    readback.requireBinding(binding);
    Record row = requireExact(readOperation(binding.operationId()), binding);
    if ("RESERVED".equals(row.get("ordering", String.class))) {
      throw new IllegalStateException("Reservation alone cannot produce terminal owner evidence");
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
            + " (operation_id, owner, outcome, readback) VALUES (?, ?, ?, ?)",
        binding.operationId(),
        readback.owner().name(),
        readback.outcome().name(),
        readback.canonicalBytes());
  }

  public FenceSnapshot read(DraftAuthorizationFenceBinding binding) {
    requireTransaction();
    return snapshot(requireExact(readOperation(binding.operationId()), binding));
  }

  /** Derived exact settlement only; original ordering and owner readbacks remain immutable. */
  public Settlement readSettlement(DraftAuthorizationFenceBinding binding) {
    requireTransaction();
    Record row = requireExact(readOperation(binding.operationId()), binding);
    return settlement(binding.operationId(), Ordering.valueOf(row.get("ordering", String.class)));
  }

  /** Absence means UNKNOWN, never no-commit proof. */
  public Optional<OwnerResultSnapshot> readOwnerResult(
      DraftAuthorizationFenceBinding binding, Owner owner) {
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

  /** Durable recovery discovery only; no auto-running worker or authority decision. */
  public List<SourceChangeSnapshot> waitingSourceChanges() {
    requireTransaction();
    return dsl
        .fetch("SELECT * FROM " + CHANGES + " WHERE status = 'WAITING' ORDER BY change_id")
        .stream()
        .map(
            row ->
                new SourceChangeSnapshot(
                    row.get("change_id", UUID.class),
                    row.get("binding", byte[].class),
                    row.get("status", String.class),
                    row.get("requested_at", OffsetDateTime.class),
                    row.get("committed_at", OffsetDateTime.class)))
        .toList();
  }

  public SourceChangeSnapshot readSourceChange(SourceChange change) {
    requireTransaction();
    Record row = readChange(change.changeId());
    requireChange(row, change);
    return new SourceChangeSnapshot(
        change.changeId(),
        row.get("binding", byte[].class),
        row.get("status", String.class),
        row.get("requested_at", OffsetDateTime.class),
        row.get("committed_at", OffsetDateTime.class));
  }

  private void lockSources(List<SourceEvidence> sources) {
    for (SourceEvidence source : sources) {
      dsl.execute(
          "INSERT INTO account_draft_authorization_source_locks (source_key) VALUES (?) ON CONFLICT DO NOTHING",
          source.key());
      dsl.fetchOne(
          "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE",
          source.key());
    }
  }

  private boolean hasWaitingChange(List<SourceEvidence> sources) {
    for (SourceEvidence source : sources) {
      if (!dsl.fetch(
              "SELECT c.change_id FROM "
                  + CHANGES
                  + " c JOIN "
                  + CHANGED
                  + " s ON s.change_id = c.change_id WHERE s.source_key = ? AND c.status = 'WAITING'",
              source.key())
          .isEmpty()) {
        return true;
      }
    }
    return false;
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

  /**
   * Commit order settles only after both exact definitive outcomes, including mixed failure. Revoke
   * order requires both definitive aborts. Missing evidence always remains pending.
   */
  private boolean allAffectedSettled(List<SourceEvidence> sources) {
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
    List<Outcome> outcomes =
        dsl
            .fetch("SELECT outcome FROM " + READBACKS + " WHERE operation_id = ?", operation)
            .getValues("outcome", String.class)
            .stream()
            .map(Outcome::valueOf)
            .toList();
    // The owner CHECK and (operation_id, owner) primary key admit exactly these two owners.
    if (outcomes.size() != 2) {
      return Settlement.PENDING;
    }
    boolean bothAborted = outcomes.stream().allMatch(o -> o == Outcome.DEFINITIVELY_ABORTED);
    if (ordering == Ordering.REVOKE_ORDER) {
      return bothAborted ? Settlement.FAILED_NONPUBLICATION : Settlement.PENDING;
    }
    return outcomes.stream().allMatch(o -> o == Outcome.COMMITTED)
        ? Settlement.COMMITTED
        : Settlement.FAILED_NONPUBLICATION;
  }

  private Record readOperation(UUID operation) {
    return dsl.fetchOne(
        "SELECT * FROM " + FENCES + " WHERE operation_id = ? FOR UPDATE", operation);
  }

  private Record readChange(UUID change) {
    return dsl.fetchOne("SELECT * FROM " + CHANGES + " WHERE change_id = ? FOR UPDATE", change);
  }

  private Record requireExact(Record row, DraftAuthorizationFenceBinding binding) {
    if (row == null
        || !Arrays.equals(row.get("binding", byte[].class), binding.canonicalBytes())
        || !binding.requestId().equals(row.get("request_id", UUID.class))
        || !binding.commitId().equals(row.get("commit_id", UUID.class))
        || !binding.fenceId().equals(row.get("fence_id", UUID.class))) {
      throw new IllegalArgumentException("Absent or changed immutable Draft operation");
    }
    List<Record> sourceRows =
        dsl
            .fetch(
                "SELECT source_key, source_evidence FROM " + SOURCES + " WHERE operation_id = ?",
                binding.operationId())
            .stream()
            .sorted(Comparator.comparing(record -> record.get("source_key", String.class)))
            .toList();
    if (sourceRows.size() != binding.sources().size()) {
      throw new IllegalStateException("Incomplete source participation");
    }
    for (int i = 0; i < sourceRows.size(); i++) {
      SourceEvidence source = binding.sources().get(i);
      if (!source.key().equals(sourceRows.get(i).get("source_key", String.class))
          || !Arrays.equals(
              source.canonicalBytes(), sourceRows.get(i).get("source_evidence", byte[].class))) {
        throw new IllegalStateException("Source participation differs from exact binding");
      }
    }
    return row;
  }

  private void requireChange(Record row, SourceChange change) {
    if (row == null || !Arrays.equals(row.get("binding", byte[].class), change.canonicalBytes())) {
      throw new IllegalArgumentException("Absent or changed immutable source mutation intent");
    }
    List<String> stored =
        dsl
            .fetch("SELECT source_key FROM " + CHANGED + " WHERE change_id = ?", change.changeId())
            .getValues("source_key", String.class)
            .stream()
            .sorted()
            .toList();
    if (!stored.equals(change.sources().stream().map(SourceEvidence::key).toList())) {
      throw new IllegalStateException("Incomplete source change participation");
    }
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
