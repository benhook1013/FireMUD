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
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Unwired publication participation in Account's existing source-change order. Callers
 * authenticate, lock and revalidate the real authority sources in their writable owner transaction
 * before reserving or ordering; this component neither captures nor authenticates them. Never
 * retain this transaction across RPC. Reservation grants no permission. Draft/creation results
 * cannot settle publication; only exact owner terminal evidence supplied by the separate concrete
 * authenticated clients can be retained. SQL guards establish structural consistency, not peer
 * authentication.
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

  public enum Owner {
    GAME_DESIGN,
    WORLD
  }

  public enum Settlement {
    PENDING,
    PUBLISHED,
    NO_PUBLICATION
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

  public record OwnerResultSnapshot(
      GameDesignPublicationTerminalEvidence.Outcome outcome,
      byte[] operationBytes,
      byte[] terminalBytes,
      OffsetDateTime recordedAt) {
    public OwnerResultSnapshot {
      operationBytes = operationBytes.clone();
      terminalBytes = terminalBytes.clone();
    }

    @Override
    public byte[] operationBytes() {
      return operationBytes.clone();
    }

    @Override
    public byte[] terminalBytes() {
      return terminalBytes.clone();
    }
  }

  public record TerminalSnapshot(
      Ordering ordering,
      Settlement settlement,
      Optional<OwnerResultSnapshot> gameDesignResult,
      Optional<OwnerResultSnapshot> worldResult) {
    public TerminalSnapshot {
      Objects.requireNonNull(ordering);
      Objects.requireNonNull(settlement);
      gameDesignResult = Objects.requireNonNull(gameDesignResult);
      worldResult = Objects.requireNonNull(worldResult);
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
    lockSources(binding.sources());
    return snapshot(requireExact(operation(binding.operationId()), binding));
  }

  /** Exact original evidence only. Absence is UNKNOWN, never definitive no-publication evidence. */
  public Optional<AccountPublicationAuthorizationBinding> readOriginalBinding(UUID operationId) {
    requireTransaction();
    Record unlocked = unlockedOperation(operationId);
    if (unlocked == null) {
      return Optional.empty();
    }
    AccountPublicationAuthorizationBinding binding = originalBinding(unlocked);
    lockSources(binding.sources());
    Record row = operation(operationId);
    requireExact(row, binding);
    return Optional.of(binding);
  }

  /**
   * Reads the full post-allocation operation after learning the immutable Account source vector
   * without a row lock. Shared source locks are always held before the operation row is locked.
   */
  public Optional<TerminalSnapshot> readOriginalOperation(
      GameDesignPublicationOperationBinding candidate) {
    requireTransaction();
    Objects.requireNonNull(candidate);
    Record unlocked = unlockedOperation(candidate.account().operationId());
    if (unlocked == null) {
      return Optional.empty();
    }
    AccountPublicationAuthorizationBinding original = originalBinding(unlocked);
    requireCandidateAccount(candidate, original);
    lockSources(original.sources());
    Record row = operation(original.operationId());
    requireExact(row, original);
    requireCandidateAccount(candidate, original);
    Ordering ordering = Ordering.valueOf(row.get("ordering", String.class));
    Optional<OwnerResultSnapshot> gameDesign = readOwnerResultLocked(candidate, Owner.GAME_DESIGN);
    Optional<OwnerResultSnapshot> world = readOwnerResultLocked(candidate, Owner.WORLD);
    return Optional.of(
        new TerminalSnapshot(ordering, settlement(ordering, gameDesign, world), gameDesign, world));
  }

  /**
   * Stores a result returned through the explicitly authenticated owner client. SQL itself proves
   * only structural consistency; it cannot prove that a caller authenticated either owner.
   */
  public OwnerResultSnapshot recordOwnerResult(
      GameDesignPublicationOperationBinding originalBinding,
      Owner owner,
      GameDesignPublicationTerminalEvidence terminal) {
    requireTransaction();
    Objects.requireNonNull(originalBinding);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(terminal);
    GameDesignPublicationOperationBinding operationBinding =
        GameDesignPublicationOperationBinding.fromStored(originalBinding.canonicalBytes());
    GameDesignPublicationTerminalEvidence terminalEvidence =
        GameDesignPublicationTerminalEvidence.fromStored(terminal.canonicalBytes());
    if (!Arrays.equals(operationBinding.canonicalBytes(), terminalEvidence.operationBytes())) {
      throw new IllegalArgumentException(
          "Owner terminal result differs from exact original operation");
    }

    AccountPublicationAuthorizationBinding account = operationBinding.account();
    lockSources(account.sources());
    Record row = operation(account.operationId());
    requireExact(row, account);
    Ordering ordering = Ordering.valueOf(row.get("ordering", String.class));
    if (ordering == Ordering.RESERVED) {
      throw new IllegalStateException(
          "Reservation alone cannot record publication terminal evidence");
    }
    if (ordering == Ordering.REVOKE_ORDER
        && terminalEvidence.outcome()
            != GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION) {
      throw new IllegalStateException("Revocation order permits only no-publication evidence");
    }

    Optional<OwnerResultSnapshot> current = readOwnerResultLocked(operationBinding, owner);
    if (current.isPresent()) {
      OwnerResultSnapshot stored = current.orElseThrow();
      requireSameResult(stored, operationBinding, terminalEvidence);
      return stored;
    }
    Owner otherOwner = owner == Owner.GAME_DESIGN ? Owner.WORLD : Owner.GAME_DESIGN;
    Optional<OwnerResultSnapshot> counterpart = readOwnerResultLocked(operationBinding, otherOwner);
    if (counterpart.isPresent()) {
      requireSameResult(counterpart.orElseThrow(), operationBinding, terminalEvidence);
    }
    dsl.execute(
        "INSERT INTO account_publication_authorization_owner_results"
            + " (operation_id, owner, outcome, operation_bytes, terminal_bytes)"
            + " VALUES (?, ?, ?, ?, ?)",
        account.operationId(),
        owner.name(),
        terminalEvidence.outcome().name(),
        operationBinding.canonicalBytes(),
        terminalEvidence.canonicalBytes());
    return readOwnerResultLocked(operationBinding, owner)
        .orElseThrow(() -> new IllegalStateException("Publication owner result did not read back"));
  }

  /** Absence means UNKNOWN; retained bytes are structural storage, not authentication evidence. */
  public Optional<OwnerResultSnapshot> readOwnerResult(
      GameDesignPublicationOperationBinding originalBinding, Owner owner) {
    requireTransaction();
    Objects.requireNonNull(originalBinding);
    Objects.requireNonNull(owner);
    AccountPublicationAuthorizationBinding account = originalBinding.account();
    lockSources(account.sources());
    Record row = operation(account.operationId());
    requireExact(row, account);
    return readOwnerResultLocked(originalBinding, owner);
  }

  /** Settlement is derived only from immutable order and two byte-identical owner results. */
  public Settlement readSettlement(GameDesignPublicationOperationBinding originalBinding) {
    requireTransaction();
    Objects.requireNonNull(originalBinding);
    AccountPublicationAuthorizationBinding account = originalBinding.account();
    lockSources(account.sources());
    Record row = operation(account.operationId());
    requireExact(row, account);
    Ordering ordering = Ordering.valueOf(row.get("ordering", String.class));
    Optional<OwnerResultSnapshot> gameDesign =
        readOwnerResultLocked(originalBinding, Owner.GAME_DESIGN);
    Optional<OwnerResultSnapshot> world = readOwnerResultLocked(originalBinding, Owner.WORLD);
    return settlement(ordering, gameDesign, world);
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
   * Every related publication must have its two matching owner results under the original source
   * order. Expiry, FAILED, and Draft readback are never terminal publication evidence.
   */
  public boolean allAffectedSettled(List<SourceEvidence> sources) {
    requireTransaction();
    lockSources(sources);
    List<UUID> affected = affectedOperations(sources);
    Map<UUID, AccountPublicationAuthorizationBinding> originals = new java.util.LinkedHashMap<>();
    for (UUID id : affected) {
      Record unlocked = unlockedOperation(id);
      AccountPublicationAuthorizationBinding original = originalBinding(unlocked);
      originals.put(id, original);
    }
    for (UUID id : affected) {
      AccountPublicationAuthorizationBinding original = originals.get(id);
      Record row = operation(id);
      requireExact(row, original);
      Ordering ordering = Ordering.valueOf(row.get("ordering", String.class));
      Optional<OwnerResultSnapshot> gameDesign =
          readStoredOwnerResultLocked(original, Owner.GAME_DESIGN);
      Optional<OwnerResultSnapshot> world = readStoredOwnerResultLocked(original, Owner.WORLD);
      if (settlement(ordering, gameDesign, world) == Settlement.PENDING) {
        return false;
      }
    }
    return true;
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

  private AccountPublicationAuthorizationBinding originalBinding(Record row) {
    if (row == null) {
      throw new IllegalStateException("Affected publication operation disappeared");
    }
    return AccountPublicationAuthorizationBinding.fromStored(row.get("binding", byte[].class));
  }

  private Optional<OwnerResultSnapshot> readOwnerResultLocked(
      GameDesignPublicationOperationBinding candidate, Owner owner) {
    return readOwnerResultLocked(candidate.account(), candidate, owner);
  }

  private Optional<OwnerResultSnapshot> readStoredOwnerResultLocked(
      AccountPublicationAuthorizationBinding original, Owner owner) {
    return readOwnerResultLocked(original, null, owner);
  }

  private Optional<OwnerResultSnapshot> readOwnerResultLocked(
      AccountPublicationAuthorizationBinding original,
      GameDesignPublicationOperationBinding candidate,
      Owner owner) {
    Record result = ownerResultRow(original, owner);
    if (result == null) return Optional.empty();
    OwnerResultSnapshot decoded = structuralOwnerResult(result, original);
    GameDesignPublicationOperationBinding storedOperation =
        GameDesignPublicationOperationBinding.fromStored(decoded.operationBytes());
    requireCandidateAccount(storedOperation, original);
    GameDesignPublicationTerminalEvidence.fromStored(decoded.terminalBytes());
    if (candidate != null) {
      if (!Arrays.equals(candidate.canonicalBytes(), decoded.operationBytes())) {
        throw new IllegalArgumentException(
            "Stored owner result differs from the exact candidate operation");
      }
    }
    return Optional.of(decoded);
  }

  private Record ownerResultRow(AccountPublicationAuthorizationBinding original, Owner owner) {
    return dsl.fetchOne(
        "SELECT outcome, operation_bytes, terminal_bytes, recorded_at"
            + " FROM account_publication_authorization_owner_results"
            + " WHERE operation_id = ? AND owner = ?",
        original.operationId(),
        owner.name());
  }

  private static OwnerResultSnapshot structuralOwnerResult(
      Record row, AccountPublicationAuthorizationBinding original) {
    byte[] operationBytes = row.get("operation_bytes", byte[].class);
    byte[] terminalBytes = row.get("terminal_bytes", byte[].class);
    var operationReader = new DraftAuthorizationFenceBinding.FrameReader(operationBytes);
    operationReader.expect(GameDesignPublicationOperationBinding.SCHEMA);
    if (!Arrays.equals(operationReader.bytes(), original.canonicalBytes())) {
      throw new IllegalStateException(
          "Stored publication owner operation changed its Account binding");
    }
    if (operationReader.bytes().length == 0) {
      throw new IllegalStateException(
          "Stored publication owner operation omits the World checkpoint");
    }
    operationReader.requireEnd();

    var terminalReader = new DraftAuthorizationFenceBinding.FrameReader(terminalBytes);
    terminalReader.expect(GameDesignPublicationTerminalEvidence.SCHEMA);
    if (!Arrays.equals(terminalReader.bytes(), operationBytes)) {
      throw new IllegalStateException(
          "Stored publication terminal changed its complete operation bytes");
    }
    GameDesignPublicationTerminalEvidence.Outcome outcome =
        GameDesignPublicationTerminalEvidence.Outcome.valueOf(terminalReader.text());
    if (outcome == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED) {
      if (terminalReader.bytes().length == 0) {
        throw new IllegalStateException("Published terminal omits release evidence bytes");
      }
      String epoch = terminalReader.text();
      DraftAuthorizationFenceBinding.decimal(epoch, false);
      try {
        Long.parseLong(epoch);
      } catch (NumberFormatException invalid) {
        throw new IllegalStateException("Published terminal epoch exceeds owner range", invalid);
      }
    }
    terminalReader.requireEnd();
    if (!outcome.name().equals(row.get("outcome", String.class))) {
      throw new IllegalStateException(
          "Stored publication owner outcome differs from terminal bytes");
    }
    return new OwnerResultSnapshot(
        outcome, operationBytes, terminalBytes, row.get("recorded_at", OffsetDateTime.class));
  }

  private static void requireCandidateAccount(
      GameDesignPublicationOperationBinding candidate,
      AccountPublicationAuthorizationBinding original) {
    if (!Arrays.equals(candidate.account().canonicalBytes(), original.canonicalBytes())) {
      throw new IllegalArgumentException(
          "Game Design operation changed the original Account binding");
    }
  }

  private static void requireSameResult(
      OwnerResultSnapshot existing,
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence terminal) {
    if (existing.outcome() != terminal.outcome()
        || !Arrays.equals(existing.operationBytes(), operation.canonicalBytes())
        || !Arrays.equals(existing.terminalBytes(), terminal.canonicalBytes())) {
      throw new IllegalArgumentException("Changed or contradictory publication terminal evidence");
    }
  }

  private static Settlement settlement(
      Ordering ordering,
      Optional<OwnerResultSnapshot> gameDesign,
      Optional<OwnerResultSnapshot> world) {
    if (ordering == Ordering.RESERVED || gameDesign.isEmpty() || world.isEmpty()) {
      return Settlement.PENDING;
    }
    OwnerResultSnapshot design = gameDesign.orElseThrow();
    OwnerResultSnapshot worldResult = world.orElseThrow();
    if (design.outcome() != worldResult.outcome()
        || !Arrays.equals(design.operationBytes(), worldResult.operationBytes())
        || !Arrays.equals(design.terminalBytes(), worldResult.terminalBytes())) {
      return Settlement.PENDING;
    }
    if (ordering == Ordering.REVOKE_ORDER
        && design.outcome() != GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION) {
      return Settlement.PENDING;
    }
    return design.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        ? Settlement.PUBLISHED
        : Settlement.NO_PUBLICATION;
  }

  private void lockSources(List<SourceEvidence> sources) {
    lockSourceKeys(sources.stream().map(SourceEvidence::key).toList());
  }

  private void lockSourceKeys(List<String> sourceKeys) {
    for (String key : sourceKeys.stream().distinct().sorted().toList()) {
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

  private Record unlockedOperation(UUID id) {
    return dsl.fetchOne(
        "SELECT * FROM account_publication_authorization_fences WHERE operation_id = ?", id);
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
