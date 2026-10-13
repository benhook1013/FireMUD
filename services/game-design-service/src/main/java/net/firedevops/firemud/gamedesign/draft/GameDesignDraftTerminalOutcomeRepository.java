package net.firedevops.firemud.gamedesign.draft;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owner-local durable Account-operation claim and final Game Design outcome readback.
 *
 * <p>This class deliberately has no Spring component annotation or RPC registration. It stores
 * exact source material and coordinator results only; it neither authenticates Account sources nor
 * treats those results as Account's independent owner outcomes.
 */
public final class GameDesignDraftTerminalOutcomeRepository {
  private static final String OPERATION_TABLE = "game_design_draft_terminal_operation";
  private static final String OUTCOME_TABLE = "game_design_draft_terminal_outcome";
  private static final String COMMIT_TABLE = "game_design_draft_commit";
  private static final String VISIBILITY_FENCE_TABLE = "game_design_draft_commit_visibility_fence";
  private static final String FINAL_ABORT_TABLE = "game_design_draft_commit_final_abort";

  private final DSLContext dsl;

  public GameDesignDraftTerminalOutcomeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Retains the exact original Account operation before any owner dispatch. The caller must hold
   * the same canonical Version row lock used by coordinator transitions.
   */
  GameDesignDraftTerminalOperation claim(
      GameDesignDraftTerminalOperation operation,
      DraftCommitCoordinatorRepository.LockedVersion lockedVersion) {
    requireWritableReadCommittedTransaction();
    validateClaim(operation, lockedVersion);
    DraftCommitBinding binding = operation.gameDesignBinding();

    Record byOperation = findOperation(operation.accountBinding().operationId(), true);
    if (byOperation != null) {
      GameDesignDraftTerminalOperation persisted = toOperation(byOperation);
      requireExactOperation(operation, persisted);
      return persisted;
    }
    Record byCommit =
        findOperation(binding.target(), binding.requestId(), binding.commitId(), true);
    if (byCommit != null) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "A Game Design commit is already claimed by a different Account operation");
    }

    Record commit =
        dsl.fetchOne(
            "SELECT input_digest, binding_json, workflow_state FROM "
                + COMMIT_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ? FOR UPDATE",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    if (commit == null) {
      throw new DraftCommitCoordinatorRepository.DraftCommitNotFoundException(
          "Game Design commit must be claimed before its Account operation");
    }
    DraftCommitBinding storedBinding =
        DraftCommitBinding.fromStored(
            commit.get("binding_json", String.class), commit.get("input_digest", String.class));
    requireExactGameDesignBinding(binding, storedBinding);
    if (!"QUEUED".equals(commit.get("workflow_state", String.class))) {
      throw new DraftCommitCoordinatorRepository.DraftCommitStateConflictException(
          "Original Account operation must be retained before owner dispatch begins");
    }
    if (!"DRAFT".equals(lockedVersion.versionState())) {
      throw new DraftCommitCoordinatorRepository.DraftCommitStateConflictException(
          "An Account operation can be newly claimed only while its exact Version is DRAFT");
    }
    Record slot =
        dsl.fetchOne(
            "SELECT 1 FROM game_design_draft_commit_application_slot "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId());
    if (slot != null) {
      throw new DraftCommitCoordinatorRepository.DraftCommitStateConflictException(
          "An Account operation must be claimed before the Version dispatch slot is acquired");
    }

    DraftAuthorizationFenceBinding account = operation.accountBinding();
    dsl.execute(
        "INSERT INTO "
            + OPERATION_TABLE
            + " (operation_id, request_id, commit_id, fence_id, actor_account_id, "
            + "canonical_tenant_id, canonical_version_id, base_commit_id, expected_draft_epoch, "
            + "account_binding_bytes, account_binding_digest, game_design_binding_json, "
            + "game_design_input_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT DO NOTHING",
        account.operationId(),
        account.requestId(),
        account.commitId(),
        account.fenceId(),
        account.actorAccountId(),
        account.tenantId(),
        account.versionId(),
        account.baseCommitId(),
        account.expectedDraftEpoch(),
        operation.accountBindingBytes(),
        operation.accountBindingDigest(),
        binding.canonicalJson(),
        binding.digest());
    Record persistedRow = findOperation(account.operationId(), true);
    if (persistedRow == null) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Account operation identity conflicts with a retained Game Design binding");
    }
    GameDesignDraftTerminalOperation persisted = toOperation(persistedRow);
    requireExactOperation(operation, persisted);
    return persisted;
  }

  /**
   * Fails before the coordinator creates any row if a supplied Account binding is not claimable.
   */
  void validateClaim(
      GameDesignDraftTerminalOperation operation,
      DraftCommitCoordinatorRepository.LockedVersion lockedVersion) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(lockedVersion, "lockedVersion");
    DraftCommitBinding binding = operation.gameDesignBinding();
    if (!binding.target().equals(lockedVersion.target())) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Account operation differs from the same-Version claim lock");
    }

    Record byOperation = findOperation(operation.accountBinding().operationId(), true);
    if (byOperation != null) {
      GameDesignDraftTerminalOperation persisted = toOperation(byOperation);
      requireExactOperation(operation, persisted);
      return;
    }
    Record byCommit =
        findOperation(binding.target(), binding.requestId(), binding.commitId(), true);
    if (byCommit != null) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "A Game Design commit is already claimed by a different Account operation");
    }
    Record commit =
        dsl.fetchOne(
            "SELECT input_digest, binding_json, workflow_state FROM "
                + COMMIT_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ? FOR UPDATE",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    if (commit != null) {
      DraftCommitBinding storedBinding =
          DraftCommitBinding.fromStored(
              commit.get("binding_json", String.class), commit.get("input_digest", String.class));
      requireExactGameDesignBinding(binding, storedBinding);
      if (!"QUEUED".equals(commit.get("workflow_state", String.class))) {
        throw new DraftCommitCoordinatorRepository.DraftCommitStateConflictException(
            "Original Account operation must be retained before owner dispatch begins");
      }
    }
    if (!"DRAFT".equals(lockedVersion.versionState())) {
      throw new DraftCommitCoordinatorRepository.DraftCommitStateConflictException(
          "An Account operation can be newly claimed only while its exact Version is DRAFT");
    }
    Record slot =
        dsl.fetchOne(
            "SELECT 1 FROM game_design_draft_commit_application_slot "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId());
    if (slot != null) {
      throw new DraftCommitCoordinatorRepository.DraftCommitStateConflictException(
          "An Account operation must be claimed before the Version dispatch slot is acquired");
    }
  }

  /** Reads the immutable operation claim, whether or not a terminal outcome exists yet. */
  Optional<GameDesignDraftTerminalOperation> readOperation(UUID operationId) {
    Objects.requireNonNull(operationId, "operationId");
    Record row = findOperation(operationId, false);
    return row == null ? Optional.empty() : Optional.of(toOperation(row));
  }

  /**
   * Reads only an explicitly persisted terminal outcome. Empty means unresolved or unclaimed; it
   * never means definitively aborted.
   */
  Optional<GameDesignDraftTerminalOutcome> read(UUID operationId) {
    Objects.requireNonNull(operationId, "operationId");
    GameDesignDraftTerminalOperation operation = readOperation(operationId).orElse(null);
    if (operation == null) {
      return Optional.empty();
    }
    Record row = findOutcome(operationId, false);
    return row == null
        ? Optional.empty()
        : Optional.of(readAndVerifyTerminalOutcome(operation, row));
  }

  /** Exact-binding readback for Account; changed original bytes conflict instead of aliasing. */
  public Optional<GameDesignDraftTerminalOutcome> read(GameDesignDraftTerminalOperation expected) {
    Objects.requireNonNull(expected, "expected");
    GameDesignDraftTerminalOperation persisted =
        readOperation(expected.accountBinding().operationId()).orElse(null);
    if (persisted == null) {
      if (findOperation(
              expected.gameDesignBinding().target(),
              expected.gameDesignBinding().requestId(),
              expected.gameDesignBinding().commitId(),
              false)
          != null) {
        throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
            "Game Design commit is retained under a different Account operation identity");
      }
      return Optional.empty();
    }
    requireExactOperation(expected, persisted);
    Record row = findOutcome(expected.accountBinding().operationId(), false);
    return row == null
        ? Optional.empty()
        : Optional.of(readAndVerifyTerminalOutcome(persisted, row));
  }

  /**
   * Reconstructs only the complete binding already embedded in the original Account bytes, then
   * reads its explicit immutable terminal row. Missing operation or result remains unresolved.
   */
  public Optional<GameDesignDraftTerminalOutcome> readAccountBound(
      byte[] originalAccountBindingBytes) {
    requireCommittedReadBoundary();
    if (originalAccountBindingBytes == null || originalAccountBindingBytes.length == 0) {
      throw new IllegalArgumentException("Complete original Account binding is required");
    }
    DraftAuthorizationFenceBinding accountBinding =
        DraftAuthorizationFenceBinding.fromStored(originalAccountBindingBytes.clone());
    return read(new GameDesignDraftTerminalOperation(accountBinding));
  }

  /**
   * External Account readback cannot observe rows before the caller's owner transaction commits.
   */
  private static void requireCommittedReadBoundary() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account-bound terminal readback requires a committed owner transaction boundary");
    }
  }

  /** Records the local committed outcome in the same transaction as synchronized visibility. */
  Optional<GameDesignDraftTerminalOutcome> recordCommitted(
      DraftCommitBinding binding,
      DraftCommitCoordinatorRepository.VisibilityFence visibilityFence,
      String exactOwnerResultVectorJson,
      boolean allowInsert) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(visibilityFence, "visibilityFence");
    Objects.requireNonNull(exactOwnerResultVectorJson, "exactOwnerResultVectorJson");
    Record operationRow =
        findOperation(binding.target(), binding.requestId(), binding.commitId(), true);
    if (operationRow == null) {
      return Optional.empty();
    }
    GameDesignDraftTerminalOperation operation = toOperation(operationRow);
    requireExactGameDesignBinding(binding, operation.gameDesignBinding());
    if (!visibilityFence.target().equals(binding.target())
        || !visibilityFence.requestId().equals(binding.requestId())
        || !visibilityFence.commitId().equals(binding.commitId())
        || !visibilityFence.inputDigest().equals(binding.digest())
        || !visibilityFence.resultVectorJson().equals(exactOwnerResultVectorJson)) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Committed terminal evidence differs from the exact synchronized visibility fence");
    }
    byte[] evidence = exactOwnerResultVectorJson.getBytes(StandardCharsets.UTF_8);
    if (!allowInsert) {
      return Optional.of(
          requireExistingOutcome(
              operation,
              GameDesignDraftTerminalOutcome.Result.COMMITTED,
              exactOwnerResultVectorJson,
              evidence));
    }
    return Optional.of(
        insertOutcome(
            operation,
            GameDesignDraftTerminalOutcome.Result.COMMITTED,
            exactOwnerResultVectorJson,
            evidence));
  }

  /** Records the local no-commit outcome beside the exact abort tombstone and owner vector. */
  Optional<GameDesignDraftTerminalOutcome> recordAborted(
      DraftCommitBinding binding,
      DraftCommitCoordinatorRepository.FinalAbortReceipt abortReceipt,
      String exactOwnerResultVectorJson,
      boolean allowInsert) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(abortReceipt, "abortReceipt");
    Objects.requireNonNull(exactOwnerResultVectorJson, "exactOwnerResultVectorJson");
    Record operationRow =
        findOperation(binding.target(), binding.requestId(), binding.commitId(), true);
    if (operationRow == null) {
      return Optional.empty();
    }
    GameDesignDraftTerminalOperation operation = toOperation(operationRow);
    requireExactGameDesignBinding(binding, operation.gameDesignBinding());
    requireExactGameDesignBinding(binding, abortReceipt.binding());
    if (!allowInsert) {
      return Optional.of(
          requireExistingOutcome(
              operation,
              GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED,
              exactOwnerResultVectorJson,
              abortReceipt.abortBytes()));
    }
    return Optional.of(
        insertOutcome(
            operation,
            GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED,
            exactOwnerResultVectorJson,
            abortReceipt.abortBytes()));
  }

  /** Prevents a late coordinator transition from mutating a definitively aborted operation. */
  void requireNotDefinitivelyAborted(DraftCommitBinding binding) {
    Record operation =
        findOperation(binding.target(), binding.requestId(), binding.commitId(), true);
    if (operation == null) {
      return;
    }
    Record outcome = findOutcome(operation.get("operation_id", UUID.class), true);
    if (outcome != null
        && GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED
            .name()
            .equals(outcome.get("outcome", String.class))) {
      throw new DraftCommitCoordinatorRepository.DraftCommitStateConflictException(
          "A definitively aborted Account-bound Draft operation cannot accept a late writer");
    }
  }

  boolean isAccountBound(DraftCommitBinding binding) {
    return findOperation(binding.target(), binding.requestId(), binding.commitId(), false) != null;
  }

  private GameDesignDraftTerminalOutcome insertOutcome(
      GameDesignDraftTerminalOperation operation,
      GameDesignDraftTerminalOutcome.Result result,
      String ownerResultVectorJson,
      byte[] finalEvidenceBytes) {
    UUID operationId = operation.accountBinding().operationId();
    Record existing = findOutcome(operationId, true);
    String evidenceDigest = GameDesignDraftTerminalOperation.sha256(finalEvidenceBytes);
    if (existing != null) {
      GameDesignDraftTerminalOutcome retained = toOutcome(operation, existing);
      GameDesignDraftTerminalOutcome candidate =
          new GameDesignDraftTerminalOutcome(
              operation,
              result,
              ownerResultVectorJson,
              finalEvidenceBytes,
              evidenceDigest,
              retained.createdAt());
      if (!retained.exactlyMatches(candidate)) {
        throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
            "Game Design terminal outcome identity was reused with changed final evidence");
      }
      return retained;
    }

    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OUTCOME_TABLE
                + " (operation_id, outcome, owner_result_vector_json, terminal_evidence_bytes, "
                + "terminal_evidence_digest) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            operationId,
            result.name(),
            ownerResultVectorJson,
            finalEvidenceBytes.clone(),
            evidenceDigest);
    Record persisted = findOutcome(operationId, true);
    if (persisted == null) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Concurrent Game Design terminal result conflicts with another operation identity");
    }
    GameDesignDraftTerminalOutcome retained = toOutcome(operation, persisted);
    GameDesignDraftTerminalOutcome candidate =
        new GameDesignDraftTerminalOutcome(
            operation,
            result,
            ownerResultVectorJson,
            finalEvidenceBytes,
            evidenceDigest,
            retained.createdAt());
    if (!retained.exactlyMatches(candidate)) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Concurrent Game Design terminal result retained different final evidence");
    }
    if (inserted == 0) {
      return retained;
    }
    return retained;
  }

  private GameDesignDraftTerminalOutcome requireExistingOutcome(
      GameDesignDraftTerminalOperation operation,
      GameDesignDraftTerminalOutcome.Result expectedResult,
      String ownerResultVectorJson,
      byte[] finalEvidenceBytes) {
    UUID operationId = operation.accountBinding().operationId();
    Record existing = findOutcome(operationId, true);
    if (existing == null) {
      throw new IllegalStateException(
          "A retained Game Design terminal fence lacks its atomic Account-bound outcome row");
    }
    GameDesignDraftTerminalOutcome retained = toOutcome(operation, existing);
    GameDesignDraftTerminalOutcome expected =
        new GameDesignDraftTerminalOutcome(
            operation,
            expectedResult,
            ownerResultVectorJson,
            finalEvidenceBytes,
            GameDesignDraftTerminalOperation.sha256(finalEvidenceBytes),
            retained.createdAt());
    if (!retained.exactlyMatches(expected)) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Game Design terminal readback differs from its retained final fence");
    }
    return retained;
  }

  private Record findOperation(UUID operationId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + OPERATION_TABLE
            + " WHERE operation_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        operationId);
  }

  private Record findOperation(
      DraftCommitBinding.TargetProof target, UUID requestId, UUID commitId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + OPERATION_TABLE
            + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
            + "AND request_id = ? AND commit_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        requestId,
        commitId);
  }

  private Record findOutcome(UUID operationId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + OUTCOME_TABLE
            + " WHERE operation_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        operationId);
  }

  private GameDesignDraftTerminalOperation toOperation(Record row) {
    byte[] accountBytes = row.get("account_binding_bytes", byte[].class);
    String accountDigest = row.get("account_binding_digest", String.class);
    DraftAuthorizationFenceBinding account;
    try {
      account = DraftAuthorizationFenceBinding.fromStored(accountBytes);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Persisted Account Draft binding is corrupt", exception);
    }
    if (!GameDesignDraftTerminalOperation.sha256(accountBytes).equals(accountDigest)
        || !account.operationId().equals(row.get("operation_id", UUID.class))
        || !account.requestId().equals(row.get("request_id", UUID.class))
        || !account.commitId().equals(row.get("commit_id", UUID.class))
        || !account.fenceId().equals(row.get("fence_id", UUID.class))
        || !account.actorAccountId().equals(row.get("actor_account_id", UUID.class))
        || !account.tenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || !account.versionId().equals(row.get("canonical_version_id", UUID.class))
        || !account.baseCommitId().equals(row.get("base_commit_id", String.class))
        || !account.expectedDraftEpoch().equals(row.get("expected_draft_epoch", String.class))) {
      throw new IllegalStateException(
          "Stored Account Draft binding differs from its indexed identity");
    }
    DraftCommitBinding gameDesignBinding =
        DraftCommitBinding.fromStored(
            row.get("game_design_binding_json", String.class),
            row.get("game_design_input_digest", String.class));
    GameDesignDraftTerminalOperation operation =
        new GameDesignDraftTerminalOperation(account, gameDesignBinding);
    if (!Arrays.equals(accountBytes, operation.accountBindingBytes())) {
      throw new IllegalStateException("Persisted Account Draft binding is not canonical");
    }
    return operation;
  }

  private GameDesignDraftTerminalOutcome toOutcome(
      GameDesignDraftTerminalOperation operation, Record row) {
    byte[] evidence = row.get("terminal_evidence_bytes", byte[].class);
    String evidenceDigest = row.get("terminal_evidence_digest", String.class);
    if (!GameDesignDraftTerminalOperation.sha256(evidence).equals(evidenceDigest)) {
      throw new IllegalStateException("Persisted terminal evidence digest differs from its bytes");
    }
    return new GameDesignDraftTerminalOutcome(
        operation,
        GameDesignDraftTerminalOutcome.Result.valueOf(row.get("outcome", String.class)),
        row.get("owner_result_vector_json", String.class),
        evidence,
        evidenceDigest,
        row.get("created_at", OffsetDateTime.class));
  }

  /**
   * Confirms that a returned status row still has its exact durable coordinator terminal fence;
   * workflow status alone is never treated as a participant result.
   */
  private GameDesignDraftTerminalOutcome readAndVerifyTerminalOutcome(
      GameDesignDraftTerminalOperation operation, Record outcomeRow) {
    GameDesignDraftTerminalOutcome outcome = toOutcome(operation, outcomeRow);
    DraftCommitBinding binding = operation.gameDesignBinding();
    Record commit =
        dsl.fetchOne(
            "SELECT workflow_state, input_digest, binding_json FROM "
                + COMMIT_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    if (commit == null
        || !binding.digest().equals(commit.get("input_digest", String.class))
        || !binding.canonicalJson().equals(commit.get("binding_json", String.class))) {
      throw new IllegalStateException(
          "Game Design terminal outcome lacks its exact retained coordinator commit");
    }

    if (outcome.result() == GameDesignDraftTerminalOutcome.Result.COMMITTED) {
      Record fence =
          dsl.fetchOne(
              "SELECT input_digest, result_vector_json FROM "
                  + VISIBILITY_FENCE_TABLE
                  + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                  + "AND request_id = ? AND commit_id = ?",
              binding.target().canonicalTenantId(),
              binding.target().canonicalVersionId(),
              binding.requestId(),
              binding.commitId());
      if (!"SYNCHRONIZED".equals(commit.get("workflow_state", String.class))
          || fence == null
          || !binding.digest().equals(fence.get("input_digest", String.class))
          || !outcome.ownerResultVectorJson().equals(fence.get("result_vector_json", String.class))
          || !Arrays.equals(
              outcome.finalEvidenceBytes(),
              outcome.ownerResultVectorJson().getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalStateException(
            "Committed Game Design terminal outcome lacks its exact synchronized visibility fence");
      }
      return outcome;
    }

    Record abort =
        dsl.fetchOne(
            "SELECT input_digest, binding_json, abort_bytes FROM "
                + FINAL_ABORT_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    Record fence =
        dsl.fetchOne(
            "SELECT 1 FROM "
                + VISIBILITY_FENCE_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    if (!"FAILED_NONPUBLICATION".equals(commit.get("workflow_state", String.class))
        || abort == null
        || !binding.digest().equals(abort.get("input_digest", String.class))
        || !binding.canonicalJson().equals(abort.get("binding_json", String.class))
        || !Arrays.equals(outcome.finalEvidenceBytes(), abort.get("abort_bytes", byte[].class))
        || fence != null) {
      throw new IllegalStateException(
          "Aborted Game Design terminal outcome lacks its exact final-abort tombstone");
    }
    return outcome;
  }

  private void requireExactOperation(
      GameDesignDraftTerminalOperation expected, GameDesignDraftTerminalOperation actual) {
    if (!expected.exactlyMatches(actual)) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Account operation identity was reused with changed original canonical bytes");
    }
  }

  private void requireExactGameDesignBinding(
      DraftCommitBinding expected, DraftCommitBinding actual) {
    if (!expected.digest().equals(actual.digest())
        || !Arrays.equals(expected.canonicalBytes(), actual.canonicalBytes())) {
      throw new DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException(
          "Terminal operation differs from its complete canonical Game Design binding");
    }
  }

  private void requireWritableReadCommittedTransaction() {
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(isolation)) {
      throw new IllegalStateException(
          "Game Design terminal mutations require a caller-owned writable READ_COMMITTED transaction");
    }
  }
}
