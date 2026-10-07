package net.firedevops.firemud.gamedesign.draft;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Durable, owner-local persistence for exact Game Design multi-owner Draft commit coordination. */
@Repository
public class DraftCommitCoordinatorRepository {
  private static final String COMMIT_TABLE = "game_design_draft_commit";
  private static final String OWNER_RESULT_TABLE = "game_design_draft_commit_owner_result";
  private static final String FINAL_ABORT_TABLE = "game_design_draft_commit_final_abort";
  private static final String APPLICATION_SLOT_TABLE = "game_design_draft_commit_application_slot";
  private static final String VISIBILITY_FENCE_TABLE = "game_design_draft_commit_visibility_fence";
  private static final String VISIBILITY_TABLE = "game_design_draft_commit_visibility";
  private static final String PUBLISH_SELECTION_TABLE =
      "game_design_authored_draft_publish_selection";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Comparator<AppliedEpoch> APPLIED_EPOCH_ORDER =
      Comparator.comparing(AppliedEpoch::aggregateType)
          .thenComparing(AppliedEpoch::aggregateId)
          .thenComparing(AppliedEpoch::scopeType)
          .thenComparing(AppliedEpoch::scopeId);

  private final DSLContext dsl;
  private final GameDesignDraftTerminalOutcomeRepository terminalOutcomes;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Internal collaborator only; no resources are acquired and no finalizer is used.")
  public DraftCommitCoordinatorRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.terminalOutcomes = new GameDesignDraftTerminalOutcomeRepository(dsl);
  }

  /**
   * Durably claims one complete immutable request binding and initializes every required owner. An
   * exact replay performs no writes; either identity reused with changed binding data conflicts.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public CommitSnapshot claim(DraftCommitBinding binding) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(binding, "binding");
    CommitRecord existing = findCommit(binding, true);
    if (existing != null) {
      requireExactBinding(binding, existing.binding());
      return snapshot(existing);
    }

    int inserted =
        dsl.execute(
            "INSERT INTO "
                + COMMIT_TABLE
                + " (canonical_tenant_id, canonical_version_id, game_design_version_row_id, "
                + "game_design_version_tenant_key, source_game_row_id, source_game_tenant_key, "
                + "source_provenance_kind, request_id, commit_id, base_commit_id, input_digest, "
                + "binding_json, workflow_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED') "
                // Request and commit are independent immutable identities; suppress either unique
                // collision in PostgreSQL so the transaction remains readable for exact readback.
                + "ON CONFLICT DO NOTHING",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.target().gameDesignVersionRowId(),
            binding.target().gameDesignVersionTenantKey(),
            binding.target().sourceGameRowId(),
            binding.target().sourceGameTenantKey(),
            binding.target().sourceProvenanceKind(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            binding.digest(),
            binding.canonicalJson());

    if (inserted == 0) {
      CommitRecord concurrent = findCommit(binding, true);
      if (concurrent == null) {
        throw new DraftCommitIdentityConflictException(
            "Draft commit identity conflicts with another immutable request");
      }
      requireExactBinding(binding, concurrent.binding());
      return snapshot(concurrent);
    }

    for (Owner owner : binding.requiredOwners()) {
      dsl.execute(
          "INSERT INTO "
              + OWNER_RESULT_TABLE
              + " (canonical_tenant_id, canonical_version_id, request_id, owner, status) "
              + "VALUES (?, ?, ?, ?, 'NOT_ATTEMPTED')",
          binding.target().canonicalTenantId(),
          binding.target().canonicalVersionId(),
          binding.requestId(),
          owner.name());
    }
    CommitRecord persisted = findCommit(binding, true);
    if (persisted == null) {
      throw new IllegalStateException("Durable Draft commit binding disappeared after insert");
    }
    requireExactBinding(binding, persisted.binding());
    return snapshot(persisted);
  }

  /**
   * Claims a complete Account operation alongside its coordinator binding before dispatch. The
   * Account bytes are exact source material only; this storage operation does not authenticate them
   * or authorize owner writes.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public CommitSnapshot claim(
      DraftCommitBinding binding, DraftAuthorizationFenceBinding originalAccountBinding) {
    requireWritableReadCommittedTransaction();
    GameDesignDraftTerminalOperation operation =
        new GameDesignDraftTerminalOperation(originalAccountBinding, binding);
    LockedVersion lockedVersion = lockVersionTarget(binding.target());
    terminalOutcomes.requireNotDefinitivelyAborted(binding);
    terminalOutcomes.validateClaim(operation, lockedVersion);
    CommitSnapshot snapshot = claim(binding);
    terminalOutcomes.claim(operation, lockedVersion);
    return snapshot;
  }

  /** Reads one durable binding and its exact per-owner status vector. */
  public Optional<CommitSnapshot> read(DraftCommitBinding.TargetProof target, UUID requestId) {
    requireTargetAndRequest(target, requestId);
    CommitRecord record = findCommit(target, requestId, false);
    return record == null ? Optional.empty() : Optional.of(snapshot(record));
  }

  /** Reads the current active dispatch/recovery slot for one canonical Version. */
  public Optional<ApplicationSlot> readApplicationSlot(DraftCommitBinding.TargetProof target) {
    requireTarget(target);
    Record record = findSlot(target, false);
    return record == null ? Optional.empty() : Optional.of(toApplicationSlot(record));
  }

  /**
   * Claims the single active owner-application slot for this canonical Version. The persisted
   * binding remains queued if another exact commit currently owns the slot.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public ApplicationSlot claimApplicationSlot(DraftCommitBinding binding) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(binding, "binding");
    LockedVersion version = lockVersionTarget(binding.target());
    terminalOutcomes.requireNotDefinitivelyAborted(binding);
    requireDraftVersion(version);
    requireNoPublicationSelection(binding.target());
    CommitRecord commit = findCommit(binding, true);
    if (commit == null) {
      throw new DraftCommitNotFoundException(
          "Draft commit must be durably claimed before slot claim");
    }
    requireExactBinding(binding, commit.binding());
    if (commit.workflowState() == WorkflowState.SYNCHRONIZED
        || commit.workflowState() == WorkflowState.REJECTED
        || commit.workflowState() == WorkflowState.FAILED_NONPUBLICATION) {
      throw new DraftCommitStateConflictException(
          "A terminal Draft commit cannot reclaim its slot");
    }
    Record existing = findSlot(binding.target(), true);
    if (existing != null) {
      ApplicationSlot slot = toApplicationSlot(existing);
      requireSameSlot(binding, slot);
      return slot;
    }
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + APPLICATION_SLOT_TABLE
                + " (canonical_tenant_id, canonical_version_id, request_id, commit_id) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    Record persisted = findSlot(binding.target(), true);
    if (persisted == null) {
      throw new DraftCommitStateConflictException(
          "Another coordinated Draft commit currently owns the Version application slot");
    }
    ApplicationSlot slot = toApplicationSlot(persisted);
    requireSameSlot(binding, slot);
    if (inserted == 1 && slot.claimedAt() == null) {
      throw new IllegalStateException("Draft commit application slot lacks durable claim time");
    }
    return slot;
  }

  /** Marks an owner dispatch in progress before the caller makes any remote owner call. */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public OwnerState markOwnerInProgress(DraftCommitBinding binding, Owner owner) {
    requireWritableReadCommittedTransaction();
    LockedVersion version = lockVersionTarget(binding.target());
    terminalOutcomes.requireNotDefinitivelyAborted(binding);
    requireDraftVersion(version);
    requireNoPublicationSelection(binding.target());
    requireExactCommitInTransaction(binding);
    requireActiveSlot(binding);
    OwnerState current = requireOwnerState(binding, owner, true);
    if (current.status() == OwnerStatus.IN_PROGRESS) {
      return current;
    }
    if (current.status() != OwnerStatus.NOT_ATTEMPTED) {
      throw new DraftCommitStateConflictException(
          "Only a not-yet-attempted owner can enter IN_PROGRESS");
    }
    int updated =
        dsl.execute(
            "UPDATE "
                + OWNER_RESULT_TABLE
                + " SET status = 'IN_PROGRESS', updated_at = CAST(? AS timestamptz) "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND owner = ? AND status = 'NOT_ATTEMPTED'",
            now(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            owner.name());
    if (updated != 1) {
      throw new DraftCommitStateConflictException("Draft owner status changed concurrently");
    }
    CommitRecord commit = requireCommit(binding, true);
    if (commit.workflowState() == WorkflowState.QUEUED) {
      updateWorkflowState(binding, WorkflowState.APPLYING);
    }
    return requireOwnerState(binding, owner, false);
  }

  /**
   * Retains one full owner result. UNKNOWN remains unresolved and can only be resolved through an
   * exact later APPLIED/REJECTED result for the same commit and binding digest.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public OwnerState recordOwnerOutcome(DraftCommitBinding binding, OwnerOutcome outcome) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(outcome, "outcome");
    LockedVersion version = lockVersionTarget(binding.target());
    terminalOutcomes.requireNotDefinitivelyAborted(binding);
    requireExactCommitInTransaction(binding);
    requireOutcomeMatches(binding, outcome);
    OwnerState current = requireOwnerState(binding, outcome.owner(), true);
    if (current.outcome().filter(outcome::equals).isPresent()) {
      return current;
    }
    requireDraftVersion(version);
    requireNoPublicationSelection(binding.target());
    requireActiveSlot(binding);
    if (current.status() != OwnerStatus.IN_PROGRESS && current.status() != OwnerStatus.UNKNOWN) {
      throw new DraftCommitStateConflictException(
          "A Draft owner outcome cannot replace its durable prior result");
    }
    if (current.status() == OwnerStatus.UNKNOWN && outcome.status() == OwnerStatus.UNKNOWN) {
      throw new DraftCommitStateConflictException(
          "UNKNOWN remains unresolved until exact owner readback supplies a terminal result");
    }
    String appliedUnitsJson =
        outcome.status() == OwnerStatus.UNKNOWN
            ? null
            : canonicalJson(outcome.appliedEpochs().stream().map(this::appliedEpochJson).toList());
    int updated =
        dsl.execute(
            "UPDATE "
                + OWNER_RESULT_TABLE
                + " SET status = ?, result_commit_id = ?, result_binding_digest = ?, "
                + "result_identity = ?, result_bytes = ?, applied_units_json = ?, updated_at = CAST(? AS timestamptz) "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND owner = ? AND status = ?",
            outcome.status().name(),
            outcome.commitId(),
            outcome.bindingDigest(),
            outcome.resultIdentity(),
            outcome.resultBytes(),
            appliedUnitsJson,
            now(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            outcome.owner().name(),
            current.status().name());
    if (updated != 1) {
      throw new DraftCommitStateConflictException("Draft owner result changed concurrently");
    }
    updateWorkflowStateAfterOwnerOutcome(binding, outcome);
    return requireOwnerState(binding, outcome.owner(), false);
  }

  /**
   * Canonical source-aware fence handoff. The caller still supplies independently verified owner
   * proof; both complete source snapshots become visible in this same owner transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public VisibilityFence advanceSourceVisibilityFence(
      DraftCommitBinding binding, CoordinatorProof coordinatorProof) {
    requireWritableReadCommittedTransaction();
    var sources = new GameDesignSourceRepository(dsl);
    if (sources.readGenesis(binding.target()).isEmpty()) {
      throw new IllegalStateException("GAME_DESIGN_SOURCE_GENESIS_UNAVAILABLE");
    }
    VisibilityFence fence = advanceVisibilityFence(binding, coordinatorProof);
    sources.captureSynchronized(binding);
    return fence;
  }

  /**
   * Advances the internal synchronized visibility record only from the complete exact APPLIED
   * result vector. This storage primitive is intentionally not wired to an owner producer or read
   * API; callers must validate each remote producer's authority before invoking it.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public VisibilityFence advanceVisibilityFence(
      DraftCommitBinding binding, CoordinatorProof coordinatorProof) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(coordinatorProof, "coordinatorProof");
    LockedVersion version = lockVersionTarget(binding.target());
    terminalOutcomes.requireNotDefinitivelyAborted(binding);
    CommitRecord commit = requireCommit(binding, true);
    requireExactBinding(binding, commit.binding());
    if (!coordinatorProof.matches(binding)) {
      throw new DraftCommitIdentityConflictException(
          "Visibility fence requires coordinator proof for this exact Draft binding");
    }
    List<OwnerState> ownerStates = lockAndReadOwnerStates(binding);
    coordinatorProof.requireSameOutcomes(ownerStates);
    String resultVectorJson = exactAppliedResultVector(binding, ownerStates);
    VisibilityFence existing = findVisibilityFence(binding, false);
    if (existing != null) {
      if (!existing.inputDigest().equals(binding.digest())
          || !existing.resultVectorJson().equals(resultVectorJson)) {
        throw new DraftCommitIdentityConflictException(
            "A Draft commit visibility fence already retains different owner evidence");
      }
      terminalOutcomes.recordCommitted(binding, existing, resultVectorJson, false);
      return existing;
    }
    if (commit.workflowState() == WorkflowState.SYNCHRONIZED
        || commit.workflowState() == WorkflowState.REJECTED
        || commit.workflowState() == WorkflowState.FAILED_NONPUBLICATION) {
      throw new DraftCommitStateConflictException(
          "Terminal Draft commit has no matching immutable visibility fence");
    }
    requireDraftVersion(version);
    requireNoPublicationSelection(binding.target());
    requireActiveSlot(binding);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + VISIBILITY_FENCE_TABLE
                + " (canonical_tenant_id, canonical_version_id, request_id, commit_id, "
                + "input_digest, result_vector_json) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (canonical_tenant_id, canonical_version_id, request_id, commit_id) "
                + "DO NOTHING",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            resultVectorJson);
    VisibilityFence persisted = findVisibilityFence(binding, false);
    if (persisted == null) {
      throw new IllegalStateException("Draft visibility fence insert returned no persisted row");
    }
    if (inserted == 0) {
      if (!persisted.inputDigest().equals(binding.digest())
          || !persisted.resultVectorJson().equals(resultVectorJson)) {
        throw new DraftCommitIdentityConflictException(
            "A concurrent Draft visibility fence retained different owner evidence");
      }
      CommitRecord concurrent = requireCommit(binding, true);
      if (concurrent.workflowState() != WorkflowState.SYNCHRONIZED) {
        throw new DraftCommitStateConflictException(
            "A pre-existing visibility fence is not paired with synchronized workflow state");
      }
      terminalOutcomes.recordCommitted(binding, persisted, resultVectorJson, false);
      return persisted;
    }
    dsl.execute(
        "INSERT INTO "
            + VISIBILITY_TABLE
            + " (canonical_tenant_id, canonical_version_id, request_id, commit_id, input_digest) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (canonical_tenant_id, canonical_version_id) "
            + "DO UPDATE SET request_id = EXCLUDED.request_id, commit_id = EXCLUDED.commit_id, "
            + "input_digest = EXCLUDED.input_digest, updated_at = EXCLUDED.updated_at",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        binding.digest());
    updateWorkflowState(binding, WorkflowState.SYNCHRONIZED);
    terminalOutcomes.recordCommitted(binding, persisted, resultVectorJson, true);
    return persisted;
  }

  /**
   * Reads the current internal visibility pointer without claiming that downstream reads use it.
   */
  public Optional<VisibilityFence> readVisibilityFence(DraftCommitBinding.TargetProof target) {
    requireTarget(target);
    Record pointer =
        dsl.fetchOne(
            "SELECT request_id, commit_id FROM "
                + VISIBILITY_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (pointer == null) {
      return Optional.empty();
    }
    UUID requestId = pointer.get("request_id", UUID.class);
    UUID commitId = pointer.get("commit_id", UUID.class);
    Record fence =
        dsl.fetchOne(
            "SELECT input_digest, result_vector_json, created_at FROM "
                + VISIBILITY_FENCE_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            requestId,
            commitId);
    if (fence == null) {
      throw new IllegalStateException("Draft visibility pointer lacks its immutable fence record");
    }
    return Optional.of(toVisibilityFence(target, requestId, commitId, fence));
  }

  /**
   * Captures only an explicitly selected current synchronized commit while the caller's publication
   * transaction owns the exact Version row lock. It does not authorize publication or assert any
   * remote freeze/participant result.
   */
  PublicationEvidence requireSynchronizedPublicationEvidence(
      DraftCommitBinding.TargetProof target, UUID requestId, UUID commitId, String inputDigest) {
    requireWritableReadCommittedTransaction();
    LockedVersion version = lockVersionTarget(target);
    requireDraftVersion(version);
    if (findSlot(target, false) != null) {
      throw new DraftCommitStateConflictException(
          "An active owner application prevents authored Draft publication selection");
    }

    CommitRecord commit = findCommit(target, requestId, true);
    if (commit == null
        || !commit.binding().commitId().equals(commitId)
        || !commit.binding().digest().equals(inputDigest)) {
      throw new DraftCommitIdentityConflictException(
          "Selected authored Draft commit identity or digest does not match durable input");
    }
    if (commit.workflowState() != WorkflowState.SYNCHRONIZED) {
      throw new DraftCommitStateConflictException(
          "Only an explicitly selected synchronized authored Draft commit can be published");
    }
    List<OwnerState> ownerStates = lockAndReadOwnerStates(commit.binding());
    String exactVector = exactAppliedResultVector(commit.binding(), ownerStates);
    VisibilityFence fence = findVisibilityFence(commit.binding(), true);
    if (fence == null
        || !fence.inputDigest().equals(inputDigest)
        || !fence.resultVectorJson().equals(exactVector)) {
      throw new DraftCommitStateConflictException(
          "Selected authored Draft commit lacks its exact complete synchronized result fence");
    }
    VisibilityFence current =
        readVisibilityFence(target)
            .orElseThrow(
                () ->
                    new DraftCommitStateConflictException(
                        "Selected authored Draft commit is not the current synchronized fence"));
    if (!current.requestId().equals(requestId)
        || !current.commitId().equals(commitId)
        || !current.inputDigest().equals(inputDigest)
        || !current.resultVectorJson().equals(exactVector)) {
      throw new DraftCommitStateConflictException(
          "Selected authored Draft commit is stale relative to the current synchronized fence");
    }
    return new PublicationEvidence(commit.binding(), fence);
  }

  /**
   * Releases the Version application slot after complete success, definite no-application
   * rejection, or exact final-abort evidence with every owner definitive. Unresolved outcomes and
   * partial application without that final-abort proof keep the slot held.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public void releaseApplicationSlot(DraftCommitBinding binding) {
    requireWritableReadCommittedTransaction();
    LockedVersion version = lockVersionTarget(binding.target());
    CommitRecord commit = requireCommit(binding, true);
    requireExactBinding(binding, commit.binding());
    Record slotRecord = findSlot(binding.target(), true);
    if (commit.workflowState() == WorkflowState.FAILED_NONPUBLICATION) {
      requireFinalAbortSlotRelease(binding, commit);
      if (slotRecord == null || !isSameSlot(binding, toApplicationSlot(slotRecord))) {
        return;
      }
      requireDraftVersion(version);
      requireNoPublicationSelection(binding.target());
      deleteApplicationSlot(binding);
      return;
    }
    if (slotRecord == null) {
      if (commit.workflowState() == WorkflowState.SYNCHRONIZED
          || commit.workflowState() == WorkflowState.REJECTED) {
        return;
      }
      throw new DraftCommitStateConflictException(
          "Draft commit does not own the active Version slot");
    }
    requireSameSlot(binding, toApplicationSlot(slotRecord));
    requireDraftVersion(version);
    requireNoPublicationSelection(binding.target());
    List<OwnerState> outcomes = lockAndReadOwnerStates(binding);
    boolean anyApplied = outcomes.stream().anyMatch(state -> state.status() == OwnerStatus.APPLIED);
    boolean unresolved =
        outcomes.stream()
            .anyMatch(
                state ->
                    state.status() == OwnerStatus.UNKNOWN
                        || state.status() == OwnerStatus.IN_PROGRESS);
    boolean allApplied = outcomes.stream().allMatch(state -> state.status() == OwnerStatus.APPLIED);
    boolean definiteNoApply =
        outcomes.stream()
                .allMatch(
                    state ->
                        state.status() == OwnerStatus.NOT_ATTEMPTED
                            || state.status() == OwnerStatus.REJECTED)
            && outcomes.stream().anyMatch(state -> state.status() == OwnerStatus.REJECTED);
    if (unresolved || (anyApplied && !allApplied) || (!allApplied && !definiteNoApply)) {
      throw new DraftCommitStateConflictException(
          "Unresolved or partially applied Draft commit cannot release its Version slot");
    } else if (allApplied) {
      VisibilityFence fence = findVisibilityFence(binding, false);
      if (commit.workflowState() != WorkflowState.SYNCHRONIZED
          || fence == null
          || !fence.inputDigest().equals(binding.digest())) {
        throw new DraftCommitStateConflictException(
            "All-owner Draft application must have its exact synchronized visibility fence first");
      }
    } else if (definiteNoApply) {
      if (anyApplied) {
        throw new DraftCommitStateConflictException(
            "A rejected Draft commit with any applied owner requires reconciliation");
      }
      if (terminalOutcomes.isAccountBound(binding)) {
        throw new DraftCommitStateConflictException(
            "An Account-bound Draft commit requires its exact final-abort tombstone and complete owner vector");
      }
      if (commit.workflowState() != WorkflowState.APPLYING
          && commit.workflowState() != WorkflowState.RECONCILIATION_REQUIRED) {
        throw new DraftCommitStateConflictException(
            "Definite rejected Draft commit is not in an applicable workflow state");
      }
      updateWorkflowState(binding, WorkflowState.REJECTED);
    }
    deleteApplicationSlot(binding);
  }

  private void deleteApplicationSlot(DraftCommitBinding binding) {
    int deleted =
        dsl.execute(
            "DELETE FROM "
                + APPLICATION_SLOT_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    if (deleted != 1) {
      throw new DraftCommitStateConflictException(
          "Draft Version application slot changed concurrently");
    }
  }

  /**
   * Stores exact, already-authenticated producer no-commit evidence for a definitive owner vector
   * and atomically makes this commit terminally non-publishable. This storage method does not
   * authenticate arbitrary bytes; only a future authenticated recovery producer may call it.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public FinalAbortReceipt recordDefinitiveFinalAbort(
      DraftCommitBinding binding, byte[] exactAbortBytes) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(exactAbortBytes, "exactAbortBytes");
    if (exactAbortBytes.length == 0) {
      throw new IllegalArgumentException("Final-abort evidence bytes must not be empty");
    }

    LockedVersion version = lockVersionTarget(binding.target());
    CommitRecord commit = requireCommit(binding, true);
    Record slotRecord = findSlot(binding.target(), true);
    Record existingAbort = findFinalAbort(binding, true);
    if (existingAbort != null) {
      requireExactFinalAbort(binding, exactAbortBytes, existingAbort);
      if (commit.workflowState() != WorkflowState.FAILED_NONPUBLICATION) {
        throw new IllegalStateException(
            "Durable final-abort evidence is not paired with terminal nonpublication state");
      }
      requireFinalAbortSlotRelease(binding, commit);
      terminalOutcomes.recordAborted(
          binding,
          toFinalAbortReceipt(binding, existingAbort),
          exactTerminalAbortResultVector(binding, lockAndReadOwnerStates(binding)),
          false);
      return toFinalAbortReceipt(binding, existingAbort);
    }

    terminalOutcomes.requireNotDefinitivelyAborted(binding);
    requireDraftVersion(version);
    requireNoPublicationSelection(binding.target());
    if (slotRecord == null) {
      throw new DraftCommitStateConflictException(
          "Definitive final-abort evidence requires the active Version application slot");
    }
    requireSameSlot(binding, toApplicationSlot(slotRecord));
    if (commit.workflowState() != WorkflowState.APPLYING
        && commit.workflowState() != WorkflowState.RECONCILIATION_REQUIRED) {
      throw new DraftCommitStateConflictException(
          "Only an unresolved owner-application workflow can record final-abort evidence");
    }
    if (findVisibilityFence(binding, true) != null) {
      throw new DraftCommitStateConflictException(
          "A commit with a successful visibility fence cannot be finalized as nonpublication");
    }

    List<OwnerState> ownerStates = lockAndReadOwnerStates(binding);
    requireCompleteTerminalAbortVector(binding, ownerStates);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + FINAL_ABORT_TABLE
                + " (canonical_tenant_id, canonical_version_id, request_id, commit_id, "
                + "input_digest, binding_json, abort_bytes) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (canonical_tenant_id, canonical_version_id, request_id, commit_id) "
                + "DO NOTHING",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId(),
            binding.digest(),
            binding.canonicalJson(),
            exactAbortBytes.clone());
    Record persisted = findFinalAbort(binding, true);
    if (persisted == null) {
      throw new IllegalStateException("Final-abort evidence insert returned no durable row");
    }
    requireExactFinalAbort(binding, exactAbortBytes, persisted);
    if (inserted == 0) {
      CommitRecord concurrent = requireCommit(binding, true);
      if (concurrent.workflowState() != WorkflowState.FAILED_NONPUBLICATION) {
        throw new DraftCommitStateConflictException(
            "Concurrent final-abort evidence has no terminal nonpublication state");
      }
      terminalOutcomes.recordAborted(
          binding,
          toFinalAbortReceipt(binding, persisted),
          exactTerminalAbortResultVector(binding, ownerStates),
          false);
      return toFinalAbortReceipt(binding, persisted);
    }

    updateWorkflowState(binding, WorkflowState.FAILED_NONPUBLICATION);
    terminalOutcomes.recordAborted(
        binding,
        toFinalAbortReceipt(binding, persisted),
        exactTerminalAbortResultVector(binding, ownerStates),
        true);
    return toFinalAbortReceipt(binding, persisted);
  }

  private void requireFinalAbortSlotRelease(DraftCommitBinding binding, CommitRecord commit) {
    if (commit.workflowState() != WorkflowState.FAILED_NONPUBLICATION) {
      throw new DraftCommitStateConflictException(
          "Only a terminal nonpublication commit can release by final-abort evidence");
    }
    Record abort = findFinalAbort(binding, true);
    if (abort == null) {
      throw new DraftCommitStateConflictException(
          "Failed nonpublication state lacks exact durable final-abort evidence");
    }
    requireExactFinalAbort(binding, abort.get("abort_bytes", byte[].class), abort);
    if (findVisibilityFence(binding, true) != null) {
      throw new DraftCommitStateConflictException(
          "A final-aborted Draft commit cannot own a successful visibility fence");
    }
    requireCompleteTerminalAbortVector(binding, lockAndReadOwnerStates(binding));
  }

  private void requireCompleteTerminalAbortVector(
      DraftCommitBinding binding, List<OwnerState> ownerStates) {
    if (ownerStates.size() != binding.requiredOwners().size()) {
      throw new DraftCommitStateConflictException(
          "Definitive final-abort evidence requires every exact required owner result");
    }
    for (Owner owner : binding.requiredOwners()) {
      OwnerState state =
          ownerStates.stream()
              .filter(candidate -> candidate.owner() == owner)
              .findFirst()
              .orElseThrow(
                  () ->
                      new DraftCommitStateConflictException(
                          "Definitive final-abort evidence cannot omit a required owner result"));
      if (state.status() != OwnerStatus.APPLIED && state.status() != OwnerStatus.REJECTED) {
        throw new DraftCommitStateConflictException(
            "Definitive final-abort evidence requires only exact terminal APPLIED or REJECTED owners");
      }
      OwnerOutcome outcome =
          state
              .outcome()
              .orElseThrow(
                  () ->
                      new DraftCommitStateConflictException(
                          "Definitive final-abort evidence requires durable owner result bytes"));
      requireOutcomeMatches(binding, outcome);
    }
  }

  private String exactTerminalAbortResultVector(
      DraftCommitBinding binding, List<OwnerState> ownerStates) {
    requireCompleteTerminalAbortVector(binding, ownerStates);
    List<Map<String, Object>> resultVector = new ArrayList<>();
    for (Owner owner : binding.requiredOwners()) {
      OwnerState state =
          ownerStates.stream()
              .filter(candidate -> candidate.owner() == owner)
              .findFirst()
              .orElseThrow(
                  () ->
                      new DraftCommitStateConflictException(
                          "Definitive final-abort evidence cannot omit a required owner result"));
      resultVector.add(ownerResultJson(state.outcome().orElseThrow()));
    }
    return canonicalJson(resultVector);
  }

  private void updateWorkflowStateAfterOwnerOutcome(
      DraftCommitBinding binding, OwnerOutcome outcome) {
    CommitRecord commit = requireCommit(binding, true);
    if (commit.workflowState() == WorkflowState.QUEUED) {
      updateWorkflowState(binding, WorkflowState.APPLYING);
      commit = requireCommit(binding, true);
    }
    if (outcome.status() == OwnerStatus.UNKNOWN) {
      if (commit.workflowState() != WorkflowState.RECONCILIATION_REQUIRED) {
        updateWorkflowState(binding, WorkflowState.RECONCILIATION_REQUIRED);
      }
      return;
    }
    List<OwnerState> states = lockAndReadOwnerStates(binding);
    boolean hasApplied = states.stream().anyMatch(state -> state.status() == OwnerStatus.APPLIED);
    boolean hasRejected = states.stream().anyMatch(state -> state.status() == OwnerStatus.REJECTED);
    boolean hasUnknown = states.stream().anyMatch(state -> state.status() == OwnerStatus.UNKNOWN);
    if (hasUnknown || (hasApplied && hasRejected)) {
      if (commit.workflowState() != WorkflowState.RECONCILIATION_REQUIRED) {
        updateWorkflowState(binding, WorkflowState.RECONCILIATION_REQUIRED);
      }
    }
  }

  private void updateWorkflowState(DraftCommitBinding binding, WorkflowState state) {
    int updated =
        dsl.execute(
            "UPDATE "
                + COMMIT_TABLE
                + " SET workflow_state = ?, updated_at = CAST(? AS timestamptz) "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ? "
                + "AND workflow_state <> ?",
            state.name(),
            now(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            state.name());
    if (updated != 1) {
      throw new DraftCommitStateConflictException(
          "Draft commit workflow state changed concurrently");
    }
  }

  private CommitRecord requireExactCommitInTransaction(DraftCommitBinding binding) {
    requireWritableReadCommittedTransaction();
    return requireCommit(binding, true);
  }

  private CommitRecord requireCommit(DraftCommitBinding binding, boolean forUpdate) {
    Objects.requireNonNull(binding, "binding");
    CommitRecord record = findCommit(binding, forUpdate);
    if (record == null) {
      throw new DraftCommitNotFoundException("Draft commit binding is missing");
    }
    requireExactBinding(binding, record.binding());
    return record;
  }

  private CommitRecord findCommit(DraftCommitBinding binding, boolean forUpdate) {
    return findCommit(binding.target(), binding.requestId(), forUpdate);
  }

  private CommitRecord findCommit(
      DraftCommitBinding.TargetProof target, UUID requestId, boolean forUpdate) {
    String suffix = forUpdate ? " FOR UPDATE" : "";
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + COMMIT_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ?"
                + suffix,
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            requestId);
    if (row == null) {
      return null;
    }
    DraftCommitBinding binding =
        DraftCommitBinding.fromStored(
            row.get("binding_json", String.class), row.get("input_digest", String.class));
    if (!target.equals(binding.target())
        || !requestId.equals(binding.requestId())
        || !row.get("commit_id", UUID.class).equals(binding.commitId())
        || !row.get("base_commit_id", String.class).equals(binding.baseCommitId())
        || !row.get("game_design_version_row_id", Long.class)
            .equals(binding.target().gameDesignVersionRowId())
        || !row.get("game_design_version_tenant_key", String.class)
            .equals(binding.target().gameDesignVersionTenantKey())
        || !row.get("source_game_row_id", Long.class).equals(binding.target().sourceGameRowId())
        || !row.get("source_game_tenant_key", String.class)
            .equals(binding.target().sourceGameTenantKey())
        || !row.get("source_provenance_kind", String.class)
            .equals(binding.target().sourceProvenanceKind())) {
      throw new IllegalStateException(
          "Draft commit indexed identity differs from its exact JSON binding");
    }
    return new CommitRecord(
        binding,
        WorkflowState.valueOf(row.get("workflow_state", String.class)),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private CommitSnapshot snapshot(CommitRecord record) {
    return new CommitSnapshot(record, readOwnerStates(record.binding(), false));
  }

  private List<OwnerState> lockAndReadOwnerStates(DraftCommitBinding binding) {
    return readOwnerStates(binding, true);
  }

  private List<OwnerState> readOwnerStates(DraftCommitBinding binding, boolean forUpdate) {
    List<OwnerState> states =
        dsl
            .fetch(
                "SELECT * FROM "
                    + OWNER_RESULT_TABLE
                    + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                    + "AND request_id = ? ORDER BY owner"
                    + (forUpdate ? " FOR UPDATE" : ""),
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.requestId())
            .stream()
            .map(this::toOwnerState)
            .toList();
    Map<Owner, OwnerState> byOwner = new EnumMap<>(Owner.class);
    for (OwnerState state : states) {
      if (byOwner.put(state.owner(), state) != null) {
        throw new IllegalStateException("Duplicate durable Draft owner status row");
      }
    }
    if (!byOwner.keySet().equals(new HashSet<>(binding.requiredOwners()))) {
      throw new IllegalStateException(
          "Durable Draft owner status set differs from its complete binding");
    }
    return binding.requiredOwners().stream().map(byOwner::get).toList();
  }

  private OwnerState requireOwnerState(DraftCommitBinding binding, Owner owner, boolean forUpdate) {
    Objects.requireNonNull(owner, "owner");
    if (!binding.requiredOwners().contains(owner)) {
      throw new DraftCommitStateConflictException(
          "Owner is not in this Draft commit's required set");
    }
    String suffix = forUpdate ? " FOR UPDATE" : "";
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + OWNER_RESULT_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND owner = ?"
                + suffix,
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            owner.name());
    if (row == null) {
      throw new IllegalStateException("Required Draft owner status row is missing");
    }
    return toOwnerState(row);
  }

  private OwnerState toOwnerState(Record row) {
    Owner owner = Owner.valueOf(row.get("owner", String.class));
    OwnerStatus status = OwnerStatus.valueOf(row.get("status", String.class));
    UUID resultCommitId = row.get("result_commit_id", UUID.class);
    String digest = row.get("result_binding_digest", String.class);
    String resultIdentity = row.get("result_identity", String.class);
    byte[] resultBytes = row.get("result_bytes", byte[].class);
    String appliedUnitsJson = row.get("applied_units_json", String.class);
    OwnerOutcome outcome = null;
    if (resultCommitId != null || digest != null || resultIdentity != null || resultBytes != null) {
      if (resultCommitId == null || digest == null) {
        throw new IllegalStateException("Draft owner result has incomplete commit identity");
      }
      List<AppliedEpoch> epochs =
          appliedUnitsJson == null ? List.of() : parseAppliedEpochs(appliedUnitsJson);
      outcome =
          new OwnerOutcome(
              owner, status, resultCommitId, digest, resultIdentity, resultBytes, epochs);
    }
    return new OwnerState(
        owner,
        status,
        Optional.ofNullable(outcome),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private List<AppliedEpoch> parseAppliedEpochs(String appliedUnitsJson) {
    try {
      List<AppliedEpoch> epochs = new ArrayList<>();
      for (tools.jackson.databind.JsonNode node : JSON.readTree(appliedUnitsJson)) {
        epochs.add(
            new AppliedEpoch(
                requiredText(node, "aggregateType"),
                requiredText(node, "aggregateId"),
                requiredText(node, "scopeType"),
                requiredText(node, "scopeId"),
                requiredText(node, "expectedEpoch"),
                requiredText(node, "resultingEpoch")));
      }
      if (!canonicalJson(epochs.stream().map(this::appliedEpochJson).toList())
          .equals(appliedUnitsJson)) {
        throw new IllegalStateException("Persisted Draft owner epoch vector is not canonical");
      }
      return List.copyOf(epochs);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Persisted Draft owner epoch vector is corrupt", exception);
    }
  }

  private Record findSlot(DraftCommitBinding.TargetProof target, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + APPLICATION_SLOT_TABLE
            + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        target.canonicalTenantId(),
        target.canonicalVersionId());
  }

  private ApplicationSlot toApplicationSlot(Record row) {
    return new ApplicationSlot(
        row.get("canonical_tenant_id", UUID.class),
        row.get("canonical_version_id", UUID.class),
        row.get("request_id", UUID.class),
        row.get("commit_id", UUID.class),
        row.get("claimed_at", OffsetDateTime.class));
  }

  private void requireActiveSlot(DraftCommitBinding binding) {
    Record row = findSlot(binding.target(), true);
    if (row == null) {
      throw new DraftCommitStateConflictException(
          "Owner dispatch requires the durable Version slot");
    }
    requireSameSlot(binding, toApplicationSlot(row));
  }

  private static boolean isSameSlot(DraftCommitBinding binding, ApplicationSlot slot) {
    return slot.canonicalTenantId().equals(binding.target().canonicalTenantId())
        && slot.canonicalVersionId().equals(binding.target().canonicalVersionId())
        && slot.requestId().equals(binding.requestId())
        && slot.commitId().equals(binding.commitId());
  }

  private static void requireSameSlot(DraftCommitBinding binding, ApplicationSlot slot) {
    if (!isSameSlot(binding, slot)) {
      throw new DraftCommitStateConflictException(
          "Another exact Draft commit owns the Version application slot");
    }
  }

  private VisibilityFence findVisibilityFence(DraftCommitBinding binding, boolean forUpdate) {
    Record row =
        dsl.fetchOne(
            "SELECT input_digest, result_vector_json, created_at FROM "
                + VISIBILITY_FENCE_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND request_id = ? AND commit_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.requestId(),
            binding.commitId());
    return row == null
        ? null
        : toVisibilityFence(binding.target(), binding.requestId(), binding.commitId(), row);
  }

  private Record findFinalAbort(DraftCommitBinding binding, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT input_digest, binding_json, abort_bytes, created_at FROM "
            + FINAL_ABORT_TABLE
            + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
            + "AND request_id = ? AND commit_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId());
  }

  private void requireExactFinalAbort(
      DraftCommitBinding binding, byte[] expectedAbortBytes, Record row) {
    if (!binding.digest().equals(row.get("input_digest", String.class))
        || !binding.canonicalJson().equals(row.get("binding_json", String.class))
        || !Arrays.equals(expectedAbortBytes, row.get("abort_bytes", byte[].class))) {
      throw new DraftCommitIdentityConflictException(
          "Final-abort identity was reused with changed binding or exact evidence bytes");
    }
  }

  private FinalAbortReceipt toFinalAbortReceipt(DraftCommitBinding binding, Record row) {
    requireExactFinalAbort(binding, row.get("abort_bytes", byte[].class), row);
    return new FinalAbortReceipt(
        binding, row.get("abort_bytes", byte[].class), row.get("created_at", OffsetDateTime.class));
  }

  private VisibilityFence toVisibilityFence(
      DraftCommitBinding.TargetProof target, UUID requestId, UUID commitId, Record row) {
    return new VisibilityFence(
        target,
        requestId,
        commitId,
        row.get("input_digest", String.class),
        row.get("result_vector_json", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private String exactAppliedResultVector(
      DraftCommitBinding binding, List<OwnerState> ownerStates) {
    if (ownerStates.size() != binding.requiredOwners().size()) {
      throw new DraftCommitStateConflictException("Complete Draft owner result vector is required");
    }
    List<Map<String, Object>> resultVector = new ArrayList<>();
    for (Owner owner : binding.requiredOwners()) {
      OwnerState state =
          ownerStates.stream()
              .filter(candidate -> candidate.owner() == owner)
              .findFirst()
              .orElseThrow(
                  () ->
                      new DraftCommitStateConflictException(
                          "Required Draft owner result is missing"));
      OwnerOutcome outcome = state.outcome().orElse(null);
      if (state.status() != OwnerStatus.APPLIED || outcome == null) {
        throw new DraftCommitStateConflictException(
            "Draft visibility cannot advance before every required owner is exactly APPLIED");
      }
      requireOutcomeMatches(binding, outcome);
      List<AffectedUnit> expected = binding.affectedUnits(owner);
      if (!hasExpectedTuples(expected, outcome.appliedEpochs())) {
        throw new DraftCommitStateConflictException(
            "Draft owner result does not prove the complete bound affected tuple set");
      }
      resultVector.add(ownerResultJson(outcome));
    }
    return canonicalJson(resultVector);
  }

  private Map<String, Object> ownerResultJson(OwnerOutcome outcome) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("owner", outcome.owner().name());
    item.put("status", outcome.status().name());
    item.put("commitId", outcome.commitId().toString());
    item.put("bindingDigest", outcome.bindingDigest());
    item.put("resultIdentity", outcome.resultIdentity());
    item.put(
        "resultBytesBase64",
        outcome.resultBytes() == null
            ? ""
            : Base64.getEncoder().encodeToString(outcome.resultBytes()));
    item.put(
        "appliedEpochs", outcome.appliedEpochs().stream().map(this::appliedEpochJson).toList());
    return item;
  }

  private Map<String, Object> appliedEpochJson(AppliedEpoch epoch) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("aggregateType", epoch.aggregateType());
    item.put("aggregateId", epoch.aggregateId());
    item.put("scopeType", epoch.scopeType());
    item.put("scopeId", epoch.scopeId());
    item.put("expectedEpoch", epoch.expectedEpoch());
    item.put("resultingEpoch", epoch.resultingEpoch());
    return item;
  }

  private void requireOutcomeMatches(DraftCommitBinding binding, OwnerOutcome outcome) {
    if (!binding.requiredOwners().contains(outcome.owner())
        || !binding.commitId().equals(outcome.commitId())
        || !binding.digest().equals(outcome.bindingDigest())) {
      throw new DraftCommitIdentityConflictException(
          "Draft owner outcome is not bound to this exact commit and full input digest");
    }
    if (outcome.status() == OwnerStatus.APPLIED) {
      List<AffectedUnit> expected = binding.affectedUnits(outcome.owner());
      if (!hasExpectedTuples(expected, outcome.appliedEpochs())) {
        throw new DraftCommitStateConflictException(
            "Draft owner result omitted a declared aggregate/scope epoch tuple");
      }
      for (AppliedEpoch actualEpoch : outcome.appliedEpochs()) {
        if (new BigInteger(actualEpoch.resultingEpoch())
                .compareTo(new BigInteger(actualEpoch.expectedEpoch()))
            <= 0) {
          throw new DraftCommitStateConflictException(
              "Draft owner result does not exactly advance every declared tuple");
        }
      }
    } else if (!outcome.appliedEpochs().isEmpty()) {
      throw new DraftCommitStateConflictException(
          "Only an APPLIED owner result may report advanced aggregate/scope epochs");
    }
  }

  private boolean hasExpectedTuples(List<AffectedUnit> expected, List<AppliedEpoch> actual) {
    if (expected.size() != actual.size()) {
      return false;
    }
    for (int index = 0; index < expected.size(); index++) {
      if (!actual.get(index).matchesExpectedTuple(expected.get(index))) {
        return false;
      }
    }
    return true;
  }

  private void requireExactBinding(DraftCommitBinding expected, DraftCommitBinding actual) {
    if (!expected.digest().equals(actual.digest())
        || !Arrays.equals(expected.canonicalBytes(), actual.canonicalBytes())) {
      throw new DraftCommitIdentityConflictException(
          "Draft request or commit identity was reused with changed complete binding data");
    }
  }

  /**
   * Locks and proves one canonical Game Design Version before any Draft commit/application lock.
   */
  LockedVersion lockVersionTarget(UUID canonicalTenantId, UUID canonicalVersionId) {
    requireWritableReadCommittedTransaction();
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    Record row =
        dsl.fetchOne(
            "SELECT v.id AS version_row_id, v.tenant_id AS version_tenant_key, "
                + "v.canonical_tenant_id, v.canonical_version_id, "
                + "v.identity_source_game_row_id, v.identity_source_game_tenant_key, "
                + "v.identity_source_provenance_kind, v.version_state, v.version_state_epoch, "
                + "v.is_script_only, v.script_patch_version, v.base_version_id "
                + "FROM version v JOIN game g "
                + "ON g.id = v.identity_source_game_row_id "
                + "AND g.tenant_id = v.identity_source_game_tenant_key "
                + "AND g.canonical_tenant_id = v.canonical_tenant_id "
                + "AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind "
                + "AND g.tenant_identity_source_game_id = g.id "
                + "AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id "
                + "WHERE v.canonical_tenant_id = ? AND v.canonical_version_id = ? "
                + "AND v.identity_source_game_row_id > 0 "
                + "AND v.identity_source_game_tenant_key = v.tenant_id "
                + "AND v.identity_source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V30') "
                + "FOR UPDATE OF v",
            canonicalTenantId,
            canonicalVersionId);
    if (row == null) {
      throw new DraftCommitNotFoundException(
          "Exact canonical Version and immutable Game source provenance were not found");
    }
    Long stateEpoch = row.get("version_state_epoch", Long.class);
    if (stateEpoch == null || stateEpoch <= 0) {
      throw new DraftCommitStateConflictException(
          "Canonical Version lacks a positive persisted version-state epoch");
    }
    TargetProof target =
        new TargetProof(
            row.get("canonical_tenant_id", UUID.class),
            row.get("canonical_version_id", UUID.class),
            row.get("version_row_id", Long.class),
            row.get("version_tenant_key", String.class),
            row.get("identity_source_game_row_id", Long.class),
            row.get("identity_source_game_tenant_key", String.class),
            row.get("identity_source_provenance_kind", String.class));
    return new LockedVersion(
        target,
        row.get("version_state", String.class),
        stateEpoch,
        row.get("is_script_only", Boolean.class),
        row.get("script_patch_version", String.class),
        row.get("base_version_id", Long.class));
  }

  private LockedVersion lockVersionTarget(TargetProof expected) {
    Objects.requireNonNull(expected, "expected");
    LockedVersion locked =
        lockVersionTarget(expected.canonicalTenantId(), expected.canonicalVersionId());
    if (!expected.equals(locked.target())) {
      throw new DraftCommitIdentityConflictException(
          "Draft binding source proof differs from its locked canonical Version");
    }
    return locked;
  }

  private static void requireDraftVersion(LockedVersion version) {
    if (!"DRAFT".equals(version.versionState())) {
      throw new DraftCommitStateConflictException(
          "Owner application requires the exact Version to remain DRAFT");
    }
  }

  private void requireNoPublicationSelection(TargetProof target) {
    Record selection =
        dsl.fetchOne(
            "SELECT publish_request_id FROM "
                + PUBLISH_SELECTION_TABLE
                + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (selection != null) {
      throw new DraftCommitStateConflictException(
          "Authored Draft publication reservation prevents later owner application");
    }
  }

  private void requireWritableReadCommittedTransaction() {
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(isolation)) {
      throw new IllegalStateException(
          "Draft coordinator mutations require a caller-owned writable READ_COMMITTED transaction");
    }
  }

  private static void requireTargetAndRequest(
      DraftCommitBinding.TargetProof target, UUID requestId) {
    requireTarget(target);
    requireNonNil(requestId, "requestId");
  }

  private static void requireTarget(DraftCommitBinding.TargetProof target) {
    Objects.requireNonNull(target, "target");
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private String canonicalJson(Object value) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      return new String(canonical, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Draft owner result evidence could not be canonicalized", exception);
    }
  }

  private static String requiredText(tools.jackson.databind.JsonNode node, String field) {
    tools.jackson.databind.JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Persisted Draft owner epoch field is missing: " + field);
    }
    return value.textValue();
  }

  private record CommitRecord(
      DraftCommitBinding binding,
      WorkflowState workflowState,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  record LockedVersion(
      DraftCommitBinding.TargetProof target,
      String versionState,
      long versionStateEpoch,
      boolean scriptOnly,
      String scriptPatchVersion,
      Long baseVersionId) {}

  public record PublicationEvidence(DraftCommitBinding binding, VisibilityFence visibilityFence) {
    public PublicationEvidence {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(visibilityFence, "visibilityFence");
    }
  }

  public enum WorkflowState {
    QUEUED,
    APPLYING,
    RECONCILIATION_REQUIRED,
    SYNCHRONIZED,
    REJECTED,
    FAILED_NONPUBLICATION
  }

  public enum OwnerStatus {
    NOT_ATTEMPTED,
    IN_PROGRESS,
    APPLIED,
    REJECTED,
    UNKNOWN
  }

  public record OwnerState(
      Owner owner,
      OwnerStatus status,
      Optional<OwnerOutcome> outcome,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    public OwnerState {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(status, "status");
      outcome = Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(updatedAt, "updatedAt");
      if ((status == OwnerStatus.NOT_ATTEMPTED || status == OwnerStatus.IN_PROGRESS)
          != outcome.isEmpty()) {
        throw new IllegalArgumentException("Draft owner status/result shape is inconsistent");
      }
      if (outcome.isPresent()
          && (outcome.orElseThrow().owner() != owner || outcome.orElseThrow().status() != status)) {
        throw new IllegalArgumentException(
            "Draft owner result identity differs from its status row");
      }
    }
  }

  public record CommitSnapshot(
      DraftCommitBinding binding,
      WorkflowState workflowState,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      Map<Owner, OwnerState> ownerStates) {
    private CommitSnapshot(CommitRecord record, List<OwnerState> states) {
      this(
          record.binding(),
          record.workflowState(),
          record.createdAt(),
          record.updatedAt(),
          ownerStateMap(states));
    }

    public CommitSnapshot {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(workflowState, "workflowState");
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(updatedAt, "updatedAt");
      ownerStates = Map.copyOf(ownerStates);
    }

    private static Map<Owner, OwnerState> ownerStateMap(List<OwnerState> states) {
      Map<Owner, OwnerState> result = new EnumMap<>(Owner.class);
      states.forEach(state -> result.put(state.owner(), state));
      return result;
    }
  }

  public record ApplicationSlot(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      UUID requestId,
      UUID commitId,
      OffsetDateTime claimedAt) {
    public ApplicationSlot {
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requireNonNil(requestId, "requestId");
      requireNonNil(commitId, "commitId");
      Objects.requireNonNull(claimedAt, "claimedAt");
    }
  }

  public record VisibilityFence(
      DraftCommitBinding.TargetProof target,
      UUID requestId,
      UUID commitId,
      String inputDigest,
      String resultVectorJson,
      OffsetDateTime createdAt) {
    public VisibilityFence {
      Objects.requireNonNull(target, "target");
      requireNonNil(requestId, "requestId");
      requireNonNil(commitId, "commitId");
      Objects.requireNonNull(inputDigest, "inputDigest");
      Objects.requireNonNull(resultVectorJson, "resultVectorJson");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  /** Exact immutable producer evidence that a commit is fenced from any later GD commit. */
  public record FinalAbortReceipt(
      DraftCommitBinding binding, byte[] abortBytes, OffsetDateTime createdAt) {
    public FinalAbortReceipt {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(abortBytes, "abortBytes");
      if (abortBytes.length == 0) {
        throw new IllegalArgumentException("Final-abort evidence bytes must not be empty");
      }
      abortBytes = abortBytes.clone();
      Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public byte[] abortBytes() {
      return abortBytes.clone();
    }
  }

  public record AppliedEpoch(
      String aggregateType,
      String aggregateId,
      String scopeType,
      String scopeId,
      String expectedEpoch,
      String resultingEpoch) {
    public AppliedEpoch {
      requireResultText(aggregateType, "aggregateType");
      requireResultText(aggregateId, "aggregateId");
      requireResultText(scopeType, "scopeType");
      requireResultText(scopeId, "scopeId");
      requireDecimal(expectedEpoch, "expectedEpoch");
      requireDecimal(resultingEpoch, "resultingEpoch");
    }

    private boolean matchesExpectedTuple(AffectedUnit expected) {
      return aggregateType.equals(expected.aggregateType())
          && aggregateId.equals(expected.aggregateId())
          && scopeType.equals(expected.scopeType())
          && scopeId.equals(expected.scopeId())
          && expectedEpoch.equals(expected.expectedEpoch());
    }
  }

  /**
   * Internal handoff token for the future coordinator after every authenticated owner producer has
   * been validated. Its package-private constructor deliberately leaves this storage slice unwired;
   * a repository can persist a fence only when a same-package coordinator supplies the exact
   * durable owner outcomes it has independently verified.
   */
  public static final class CoordinatorProof {
    private final DraftCommitBinding binding;
    private final List<OwnerOutcome> outcomes;

    CoordinatorProof(DraftCommitBinding binding, List<OwnerOutcome> outcomes) {
      this.binding = Objects.requireNonNull(binding, "binding");
      this.outcomes = List.copyOf(outcomes);
    }

    private boolean matches(DraftCommitBinding candidate) {
      return binding.equals(candidate);
    }

    private void requireSameOutcomes(List<OwnerState> states) {
      Map<Owner, OwnerOutcome> verified = new EnumMap<>(Owner.class);
      for (OwnerOutcome outcome : outcomes) {
        if (outcome.status() != OwnerStatus.APPLIED
            || verified.put(outcome.owner(), outcome) != null) {
          throw new DraftCommitStateConflictException(
              "Coordinator proof must contain one exact APPLIED result per required owner");
        }
      }
      if (!verified.keySet().equals(new HashSet<>(binding.requiredOwners()))) {
        throw new DraftCommitStateConflictException(
            "Coordinator proof omits or adds a required Draft owner");
      }
      for (OwnerState state : states) {
        OwnerOutcome outcome = verified.get(state.owner());
        if (state.status() != OwnerStatus.APPLIED
            || outcome == null
            || state.outcome().isEmpty()
            || !state.outcome().orElseThrow().equals(outcome)) {
          throw new DraftCommitStateConflictException(
              "Coordinator proof differs from the exact durable owner result row");
        }
      }
    }
  }

  /** Immutable copy of an owner's full terminal or uncertain result evidence. */
  public static final class OwnerOutcome {
    private final Owner owner;
    private final OwnerStatus status;
    private final UUID commitId;
    private final String bindingDigest;
    private final String resultIdentity;
    private final byte[] resultBytes;
    private final List<AppliedEpoch> appliedEpochs;

    public OwnerOutcome(
        Owner owner,
        OwnerStatus status,
        UUID commitId,
        String bindingDigest,
        String resultIdentity,
        byte[] resultBytes,
        List<AppliedEpoch> appliedEpochs) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.status = Objects.requireNonNull(status, "status");
      if (status == OwnerStatus.NOT_ATTEMPTED || status == OwnerStatus.IN_PROGRESS) {
        throw new IllegalArgumentException(
            "OwnerOutcome must carry an observed owner result state");
      }
      requireNonNil(commitId, "commitId");
      this.commitId = commitId;
      this.bindingDigest = Objects.requireNonNull(bindingDigest, "bindingDigest");
      if (!bindingDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("bindingDigest must be a canonical SHA-256 digest");
      }
      if ((resultIdentity == null) != (resultBytes == null)) {
        throw new IllegalArgumentException(
            "Owner result identity and exact result bytes must be paired");
      }
      if (status == OwnerStatus.APPLIED || status == OwnerStatus.REJECTED) {
        requireResultText(resultIdentity, "resultIdentity");
      } else if (resultIdentity != null) {
        requireResultText(resultIdentity, "resultIdentity");
      }
      this.resultIdentity = resultIdentity;
      this.resultBytes = resultBytes == null ? null : resultBytes.clone();
      Objects.requireNonNull(appliedEpochs, "appliedEpochs");
      List<AppliedEpoch> epochCopy = new ArrayList<>(appliedEpochs);
      epochCopy.sort(APPLIED_EPOCH_ORDER);
      if (!epochCopy.equals(appliedEpochs)) {
        throw new IllegalArgumentException("Applied owner epoch tuples must be in canonical order");
      }
      if ((status == OwnerStatus.APPLIED) != !epochCopy.isEmpty()) {
        throw new IllegalArgumentException("APPLIED result must contain its complete tuple vector");
      }
      this.appliedEpochs = List.copyOf(epochCopy);
    }

    public Owner owner() {
      return owner;
    }

    public OwnerStatus status() {
      return status;
    }

    public UUID commitId() {
      return commitId;
    }

    public String bindingDigest() {
      return bindingDigest;
    }

    public String resultIdentity() {
      return resultIdentity;
    }

    public byte[] resultBytes() {
      return resultBytes == null ? null : resultBytes.clone();
    }

    public List<AppliedEpoch> appliedEpochs() {
      return appliedEpochs;
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof OwnerOutcome result
              && owner == result.owner
              && status == result.status
              && commitId.equals(result.commitId)
              && bindingDigest.equals(result.bindingDigest)
              && Objects.equals(resultIdentity, result.resultIdentity)
              && Arrays.equals(resultBytes, result.resultBytes)
              && appliedEpochs.equals(result.appliedEpochs));
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          owner,
          status,
          commitId,
          bindingDigest,
          resultIdentity,
          Arrays.hashCode(resultBytes),
          appliedEpochs);
    }
  }

  private static void requireDecimal(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be a canonical nonnegative decimal string");
    }
  }

  private static void requireResultText(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank() || !value.equals(value.trim())) {
      throw new IllegalArgumentException(label + " must be nonblank exact text");
    }
  }

  public static class DraftCommitIdentityConflictException extends IllegalStateException {
    public DraftCommitIdentityConflictException(String message) {
      super(message);
    }

    public DraftCommitIdentityConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class DraftCommitStateConflictException extends IllegalStateException {
    public DraftCommitStateConflictException(String message) {
      super(message);
    }
  }

  public static final class DraftCommitNotFoundException extends IllegalStateException {
    public DraftCommitNotFoundException(String message) {
      super(message);
    }
  }
}
