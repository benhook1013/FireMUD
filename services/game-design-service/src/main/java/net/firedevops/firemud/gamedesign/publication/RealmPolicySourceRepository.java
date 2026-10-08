package net.firedevops.firemud.gamedesign.publication;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal owner storage, with no runtime registration or creator authorization. Missing actual
 * source baseline denies. Empty genesis can only be enrolled in the actual Version insert
 * transaction.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Internal owner storage collaborator.")
public final class RealmPolicySourceRepository {
  private final DSLContext dsl;

  public RealmPolicySourceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Parent invokes only from the new Version insert branch, in that same owner transaction. */
  public RealmPolicyGenesis recordFreshGenesis(TargetProof target) {
    requireWrite();
    lockAuthority(target);
    var prior = readGenesis(target);
    if (prior.isPresent()) return prior.orElseThrow();
    requireDraftAndUnfrozen(target);
    var witness =
        dsl.fetchOne(
            "SELECT creation_transaction_id FROM game_design_realm_policy_version_insert "
                + "WHERE version_id = ? AND creation_transaction_id = pg_current_xact_id()::TEXT",
            target.gameDesignVersionRowId());
    if (witness == null)
      throw new IllegalStateException("POLICY_FRESH_VERSION_INSERT_PROOF_UNAVAILABLE");
    var receipt = new RealmPolicyGenesis(target, UUID.randomUUID(), witness.get(0, String.class));
    dsl.execute(
        "INSERT INTO game_design_realm_policy_genesis "
            + "(canonical_tenant_id, canonical_version_id, receipt_id, version_id, creation_transaction_id, receipt_json) VALUES (?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        receipt.receiptId(),
        target.gameDesignVersionRowId(),
        receipt.creationTransactionId(),
        receipt.canonicalJson());
    dsl.execute(
        "INSERT INTO game_design_realm_policy_source "
            + "(canonical_tenant_id, canonical_version_id, source_epoch, visible_commit_id, genesis_receipt_id) VALUES (?, ?, '0', NULL, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        receipt.receiptId());
    return readGenesis(target).orElseThrow();
  }

  public Optional<RealmPolicyGenesis> readGenesis(TargetProof target) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_realm_policy_genesis WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    var receipt =
        new RealmPolicyGenesis(
            target,
            row.get("receipt_id", UUID.class),
            row.get("creation_transaction_id", String.class));
    if (!receipt.canonicalJson().equals(row.get("receipt_json", String.class))
        || target.gameDesignVersionRowId() != row.get("version_id", Long.class)) {
      throw new IllegalStateException("POLICY_GENESIS_TARGET_CONFLICT");
    }
    return Optional.of(receipt);
  }

  /** Caller must already own the authorized coordinator application slot. */
  public RealmPolicyApplication apply(DraftCommitBinding binding) {
    requirePolicyOnly(binding);
    var result = applyMutation(binding);
    recordCompleteOwnerOutcome(binding, result.ownerOutcome());
    return result;
  }

  /**
   * Stages only policy mutation evidence; parent records one complete owner outcome before commit.
   */
  public RealmPolicyApplication applyMutation(DraftCommitBinding binding) {
    requireWrite();
    lockAuthority(binding.target());
    exactCommit(binding);
    var prior = application(binding);
    if (prior.isPresent()) {
      var result = prior.orElseThrow();
      return result;
    }
    requireDraftAndUnfrozen(binding.target());
    if (dsl.fetchOne(
            "SELECT 1 FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId())
        != null) {
      throw new IllegalStateException("POLICY_PUBLICATION_SELECTION_ALREADY_RESERVED");
    }
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    var slot =
        coordinator
            .readApplicationSlot(binding.target())
            .orElseThrow(() -> new IllegalStateException("POLICY_APPLICATION_SLOT_UNAVAILABLE"));
    if (!slot.requestId().equals(binding.requestId())
        || !slot.commitId().equals(binding.commitId())) {
      throw new IllegalStateException("POLICY_APPLICATION_SLOT_CHANGED");
    }
    var state =
        coordinator
            .read(binding.target(), binding.requestId())
            .orElseThrow()
            .ownerStates()
            .get(Owner.GAME_DESIGN_CONTROL_PLANE);
    if (state == null
        || (state.status() != DraftCommitCoordinatorRepository.OwnerStatus.IN_PROGRESS
            && state.status() != DraftCommitCoordinatorRepository.OwnerStatus.UNKNOWN)) {
      throw new IllegalStateException("POLICY_OWNER_APPLICATION_NOT_STARTED");
    }
    var source = source(binding.target());
    UUID inherited = source.get("visible_commit_id", UUID.class);
    String epoch = source.get("source_epoch", String.class);
    var genesis =
        source.get("genesis_receipt_id", UUID.class) == null
            ? null
            : readGenesis(binding.target()).orElseThrow();
    if (genesis != null
        && !genesis.receiptId().equals(source.get("genesis_receipt_id", UUID.class))) {
      throw new IllegalStateException("POLICY_GENESIS_SOURCE_CONFLICT");
    }
    List<RealmPolicySource.Policy> inheritedPolicies;
    if (inherited == null) {
      if (genesis == null
          || !"0".equals(epoch)
          || new DraftCommitCoordinatorRepository(dsl)
              .readVisibilityFence(binding.target())
              .isPresent()) {
        throw new IllegalStateException("POLICY_SOURCE_GENESIS_NOT_CURRENT");
      }
      inheritedPolicies = List.of();
    } else {
      requireCurrentFence(binding.target(), inherited);
      var previous = readSnapshot(binding.target(), inherited).orElseThrow();
      if (!previous.sourceEpoch().equals(epoch))
        throw new IllegalStateException("POLICY_SOURCE_UNSYNCHRONIZED");
      inheritedPolicies = previous.policies();
    }
    var units =
        binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE).stream()
            .filter(u -> RealmPolicySource.SCOPE.equals(u.aggregateType()))
            .toList();
    if (units.size() != 1
        || !RealmPolicySource.SCOPE.equals(units.getFirst().aggregateType())
        || !binding.target().canonicalVersionId().toString().equals(units.getFirst().aggregateId())
        || !RealmPolicySource.SCOPE.equals(units.getFirst().scopeType())
        || !"effective".equals(units.getFirst().scopeId())
        || !epoch.equals(units.getFirst().expectedEpoch())) {
      throw new IllegalArgumentException(
          "Complete policy-set containing scope and exact epoch required");
    }
    var updates =
        binding.revisions().stream()
            .filter(r -> r.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
            .filter(RealmPolicySource::isPolicyRevision)
            .map(r -> RealmPolicySource.revision(binding, r))
            .toList();
    if (updates.isEmpty())
      throw new IllegalArgumentException("Typed authored policy revisions required");
    for (var update : updates) {
      if (dsl.fetchExists(
          dsl.selectOne()
              .from("game_design_realm_policy_application")
              .where(
                  "canonical_tenant_id = ? AND canonical_version_id = ? AND snapshot_json::jsonb -> 'policies' @> ?::jsonb",
                  binding.target().canonicalTenantId(),
                  binding.target().canonicalVersionId(),
                  RealmPolicySource.canonical(
                      List.of(java.util.Map.of("revisionId", update.revisionId().toString())))))) {
        throw new IllegalArgumentException("Authored policy revision identity cannot be reused");
      }
      if (inheritedPolicies.stream().anyMatch(p -> p.revisionId().equals(update.revisionId()))) {
        throw new IllegalArgumentException("Authored baseline revision identity cannot be reused");
      }
    }
    var snapshot =
        new RealmPolicySnapshot(
            binding,
            new BigInteger(epoch).add(BigInteger.ONE).toString(),
            RealmPolicySource.effective(inheritedPolicies, updates));
    var result = new RealmPolicyApplication(genesis, inherited, epoch, snapshot);
    int advanced =
        dsl.execute(
            "UPDATE game_design_realm_policy_source SET source_epoch = ? "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND source_epoch = ? AND visible_commit_id IS NOT DISTINCT FROM CAST(? AS UUID)",
            snapshot.sourceEpoch(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            epoch,
            inherited);
    if (advanced != 1) throw new IllegalStateException("POLICY_SOURCE_EPOCH_CONFLICT");
    dsl.execute(
        "INSERT INTO game_design_realm_policy_application "
            + "(canonical_tenant_id, canonical_version_id, commit_id, request_id, inherited_commit_id, genesis_receipt_id, expected_epoch, snapshot_json, result_bytes) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.commitId(),
        binding.requestId(),
        inherited,
        genesis == null ? null : genesis.receiptId(),
        epoch,
        snapshot.canonicalJson(),
        result.canonicalBytes());
    var retained = application(binding).orElseThrow();
    return retained;
  }

  /** Parent supplies actual sibling evidence and every exact owned epoch to one shared result. */
  public void recordCombinedOwnerOutcome(
      DraftCommitBinding binding,
      byte[] siblingEvidence,
      List<DraftCommitCoordinatorRepository.AppliedEpoch> completeEpochs) {
    requireWrite();
    lockAuthority(binding.target());
    var result = application(binding).orElseThrow();
    recordCompleteOwnerOutcome(
        binding, result.combinedOwnerOutcome(siblingEvidence, completeEpochs));
  }

  private void recordCompleteOwnerOutcome(
      DraftCommitBinding binding, DraftCommitCoordinatorRepository.OwnerOutcome outcome) {
    requireWrite();
    var row =
        dsl.fetchOne(
            "SELECT owner_result_bytes FROM game_design_realm_policy_application WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.commitId());
    if (row == null) throw new IllegalStateException("POLICY_OWNER_APPLICATION_UNAVAILABLE");
    byte[] previous = row.get(0, byte[].class);
    if (previous != null && !Arrays.equals(previous, outcome.resultBytes())) {
      throw new IllegalArgumentException("POLICY_COMPLETE_OWNER_RESULT_CHANGED");
    }
    new DraftCommitCoordinatorRepository(dsl).recordOwnerOutcome(binding, outcome);
    if (previous == null)
      dsl.execute(
          "UPDATE game_design_realm_policy_application SET owner_result_bytes = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
          outcome.resultBytes(),
          binding.target().canonicalTenantId(),
          binding.target().canonicalVersionId(),
          binding.commitId());
    requireCoordinatorResult(application(binding).orElseThrow());
  }

  private static void requirePolicyOnly(DraftCommitBinding binding) {
    if (binding.revisions().stream()
            .filter(r -> r.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
            .anyMatch(r -> !RealmPolicySource.isPolicyRevision(r))
        || binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE).stream()
            .anyMatch(u -> !RealmPolicySource.SCOPE.equals(u.aggregateType()))) {
      throw new IllegalArgumentException(
          "Mixed Game Design commit requires one combined owner result");
    }
  }

  /**
   * Invoke in the transaction that advances the coordinator fence, before releasing its slot. A
   * disjoint synchronized commit inherits the last complete snapshot; staged owner rows never
   * become visible. Absence of a baseline is a denial, including for retained content.
   */
  public RealmPolicySnapshot captureSynchronized(DraftCommitBinding binding) {
    requireWrite();
    lockAuthority(binding.target());
    exactCommit(binding);
    requireCurrentFence(binding.target(), binding.commitId());
    var existing = readSnapshot(binding.target(), binding.commitId());
    if (existing.isPresent()) {
      if (!existing.orElseThrow().binding().equals(binding))
        throw new IllegalStateException("POLICY_SNAPSHOT_BINDING_CONFLICT");
      return existing.orElseThrow();
    }
    requireDraftAndUnfrozen(binding.target());
    var source = source(binding.target());
    UUID previousCommit = source.get("visible_commit_id", UUID.class);
    var previous =
        previousCommit == null
            ? null
            : readSnapshot(binding.target(), previousCommit).orElseThrow();
    if (previous == null
        && (source.get("genesis_receipt_id", UUID.class) == null
            || readGenesis(binding.target()).isEmpty())) {
      throw new IllegalStateException("POLICY_SOURCE_BASELINE_UNAVAILABLE");
    }
    var applied = application(binding);
    RealmPolicySnapshot snapshot;
    if (binding.revisions().stream()
        .anyMatch(
            r ->
                r.owner() == Owner.GAME_DESIGN_CONTROL_PLANE
                    && RealmPolicySource.isPolicyRevision(r))) {
      var result =
          applied.orElseThrow(() -> new IllegalStateException("POLICY_OWNER_RESULT_UNAVAILABLE"));
      requireCoordinatorResult(result);
      if (!Objects.equals(result.inheritedCommitId(), previousCommit)) {
        throw new IllegalStateException("POLICY_SNAPSHOT_PREDECESSOR_CHANGED");
      }
      snapshot = result.snapshot();
    } else {
      snapshot =
          new RealmPolicySnapshot(
              binding,
              previous == null ? "0" : previous.sourceEpoch(),
              previous == null ? List.of() : previous.policies());
    }
    if (!snapshot.sourceEpoch().equals(source.get("source_epoch", String.class))) {
      throw new IllegalStateException("PARTIAL_POLICY_OWNER_WRITE_NOT_VISIBLE");
    }
    dsl.execute(
        "INSERT INTO game_design_realm_policy_snapshot "
            + "(canonical_tenant_id, canonical_version_id, commit_id, request_id, inherited_commit_id, snapshot_json) VALUES (?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.commitId(),
        binding.requestId(),
        previousCommit,
        snapshot.canonicalJson());
    dsl.execute(
        "UPDATE game_design_realm_policy_source SET visible_commit_id = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
        binding.commitId(),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId());
    return readSnapshot(binding.target(), binding.commitId()).orElseThrow();
  }

  public RealmPolicySnapshot.Capture freeze(GameDesignPublicationOperation operation) {
    requireWrite();
    var selection = operation.account().input().selection();
    var target = selection.target();
    lockAuthority(target);
    var existing = readCapture(operation);
    if (existing.isPresent()) return existing.orElseThrow();
    requireDraftAndUnfrozen(target);
    var row =
        dsl.fetchOne(
            "SELECT selection_json, selection_digest FROM game_design_authored_draft_publish_selection "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null
        || !selection.canonicalJson().equals(row.get("selection_json", String.class))
        || !selection.digest().equals(row.get("selection_digest", String.class))) {
      throw new IllegalStateException("POLICY_PUBLICATION_SELECTION_UNAVAILABLE");
    }
    // The Version lock serializes this check with the owner finalizer. Do not acquire its earlier
    // game/attempt locks after the Version lock and invert the existing publication lock order.
    var retainedOperation =
        new GameDesignPublicationOperationRepository(dsl)
            .read(operation.workflowId())
            .orElseThrow();
    GameDesignPublicationOperationRepository.exact(retainedOperation.operation(), operation);
    if (!"PENDING".equals(retainedOperation.outcome()))
      throw new IllegalStateException("POLICY_PUBLICATION_OPERATION_SEALED");
    var versionEpoch =
        dsl.fetchOne(
            "SELECT version_state_epoch FROM version WHERE id = ?",
            target.gameDesignVersionRowId());
    Long persistedEpoch = versionEpoch == null ? null : versionEpoch.get(0, Long.class);
    if (persistedEpoch == null || persistedEpoch <= 0) {
      throw new IllegalStateException("POLICY_PUBLICATION_VERSION_EPOCH_UNAVAILABLE");
    }
    if (!selection.intent().expectedVersionStateEpoch().equals(Long.toString(persistedEpoch))) {
      throw new IllegalStateException("POLICY_PUBLICATION_DRAFT_EPOCH_CHANGED");
    }
    requireCurrentFence(target, selection.selectedCommit().commitId());
    var source = source(target);
    var snapshot = readSnapshot(target, selection.selectedCommit().commitId()).orElseThrow();
    if (!source.get("visible_commit_id", UUID.class).equals(snapshot.binding().commitId())
        || !source.get("source_epoch", String.class).equals(snapshot.sourceEpoch())) {
      throw new IllegalStateException("POLICY_FROZEN_SOURCE_NOT_SYNCHRONIZED");
    }
    var capture = new RealmPolicySnapshot.Capture(operation, snapshot);
    dsl.execute(
        "INSERT INTO game_design_realm_policy_capture "
            + "(canonical_tenant_id, canonical_version_id, publish_workflow_id, commit_id, operation_bytes, capture_bytes) VALUES (?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        operation.workflowId(),
        snapshot.binding().commitId(),
        operation.canonicalBytes(),
        capture.canonicalBytes());
    return readCapture(operation).orElseThrow();
  }

  public Optional<RealmPolicySnapshot.Capture> readCapture(
      GameDesignPublicationOperation operation) {
    var target = operation.account().input().selection().target();
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_realm_policy_capture WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    if (!Arrays.equals(operation.canonicalBytes(), row.get("operation_bytes", byte[].class))
        || !operation.workflowId().equals(row.get("publish_workflow_id", String.class))) {
      throw new IllegalArgumentException("POLICY_FREEZE_OPERATION_CONFLICT");
    }
    var snapshot = readSnapshot(target, row.get("commit_id", UUID.class)).orElseThrow();
    var capture = new RealmPolicySnapshot.Capture(operation, snapshot);
    if (!Arrays.equals(capture.canonicalBytes(), row.get("capture_bytes", byte[].class))) {
      throw new IllegalStateException("POLICY_CAPTURE_STORAGE_CONFLICT");
    }
    return Optional.of(capture);
  }

  public Optional<RealmPolicySnapshot> readSnapshot(TargetProof target, UUID commitId) {
    var row =
        dsl.fetchOne(
            "SELECT snapshot_json FROM game_design_realm_policy_snapshot WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            commitId);
    if (row == null) return Optional.empty();
    var snapshot = RealmPolicySnapshot.fromStored(row.get("snapshot_json", String.class));
    if (!snapshot.binding().target().equals(target)
        || !snapshot.binding().commitId().equals(commitId)) {
      throw new IllegalStateException("POLICY_SNAPSHOT_OWNER_CONFLICT");
    }
    exactCommit(snapshot.binding());
    var fence =
        dsl.fetchOne(
            "SELECT input_digest FROM game_design_draft_commit_visibility_fence "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ? AND request_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            commitId,
            snapshot.binding().requestId());
    if (fence == null
        || !snapshot.binding().digest().equals(fence.get("input_digest", String.class))) {
      throw new IllegalStateException("POLICY_SNAPSHOT_SYNCHRONIZED_FENCE_UNAVAILABLE");
    }
    for (var policy : snapshot.policies()) {
      var authored =
          dsl.fetchOne(
              "SELECT binding_json, input_digest FROM game_design_draft_commit "
                  + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
              target.canonicalTenantId(),
              target.canonicalVersionId(),
              policy.commitId());
      if (authored == null)
        throw new IllegalStateException("POLICY_AUTHORED_PROVENANCE_UNAVAILABLE");
      var binding =
          DraftCommitBinding.fromStored(
              authored.get("binding_json", String.class),
              authored.get("input_digest", String.class));
      var revision =
          binding.revisions().stream()
              .filter(r -> r.revisionId().equals(policy.revisionId()))
              .findFirst()
              .orElseThrow();
      if (!binding.target().equals(target)
          || !RealmPolicySource.revision(binding, revision).equals(policy)) {
        throw new IllegalStateException("POLICY_AUTHORED_PROVENANCE_CHANGED");
      }
    }
    return Optional.of(snapshot);
  }

  private Optional<RealmPolicyApplication> application(DraftCommitBinding binding) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_realm_policy_application WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.commitId());
    if (row == null) return Optional.empty();
    var result =
        new RealmPolicyApplication(
            row.get("genesis_receipt_id", UUID.class) == null
                ? null
                : readGenesis(binding.target()).orElseThrow(),
            row.get("inherited_commit_id", UUID.class),
            row.get("expected_epoch", String.class),
            RealmPolicySnapshot.fromStored(row.get("snapshot_json", String.class)));
    if (!result.snapshot().binding().equals(binding)
        || !binding.requestId().equals(row.get("request_id", UUID.class))
        || !Arrays.equals(result.canonicalBytes(), row.get("result_bytes", byte[].class))) {
      throw new IllegalStateException("POLICY_APPLICATION_EXACT_BINDING_CONFLICT");
    }
    return Optional.of(result);
  }

  private void requireCoordinatorResult(RealmPolicyApplication result) {
    var binding = result.snapshot().binding();
    var state =
        new DraftCommitCoordinatorRepository(dsl)
            .read(binding.target(), binding.requestId())
            .orElseThrow()
            .ownerStates()
            .get(Owner.GAME_DESIGN_CONTROL_PLANE);
    var stored =
        dsl.fetchOne(
            "SELECT owner_result_bytes FROM game_design_realm_policy_application WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.commitId());
    if (state == null
        || state.outcome().isEmpty()
        || stored == null
        || !Arrays.equals(state.outcome().orElseThrow().resultBytes(), stored.get(0, byte[].class))
        || !state
            .outcome()
            .orElseThrow()
            .appliedEpochs()
            .contains(result.ownerOutcome().appliedEpochs().getFirst())) {
      throw new IllegalStateException("POLICY_COORDINATOR_RESULT_CONFLICT");
    }
  }

  private Record source(TargetProof target) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_realm_policy_source WHERE canonical_tenant_id = ? AND canonical_version_id = ? FOR UPDATE",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) throw new IllegalStateException("POLICY_SOURCE_BASELINE_UNAVAILABLE");
    return row;
  }

  private void requireCurrentFence(TargetProof target, UUID commitId) {
    var current =
        new DraftCommitCoordinatorRepository(dsl).readVisibilityFence(target).orElseThrow();
    if (!current.commitId().equals(commitId))
      throw new IllegalStateException("POLICY_VISIBLE_FENCE_CHANGED");
  }

  private void exactCommit(DraftCommitBinding binding) {
    var retained =
        new DraftCommitCoordinatorRepository(dsl)
            .read(binding.target(), binding.requestId())
            .orElseThrow();
    if (!retained.binding().equals(binding))
      throw new IllegalArgumentException("POLICY_COMMIT_BINDING_CONFLICT");
  }

  private void requireDraftAndUnfrozen(TargetProof target) {
    var version =
        dsl.fetchOne(
            "SELECT version_state, is_script_only, script_patch_version, base_version_id FROM version WHERE id = ?",
            target.gameDesignVersionRowId());
    if (version == null
        || !"DRAFT".equals(version.get("version_state", String.class))
        || !Boolean.FALSE.equals(version.get("is_script_only", Boolean.class))
        || version.get("script_patch_version") != null
        || version.get("base_version_id") != null
        || dsl.fetchOne(
                "SELECT 1 FROM game_design_realm_policy_capture WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                target.canonicalTenantId(),
                target.canonicalVersionId())
            != null) {
      throw new IllegalStateException("POLICY_SOURCE_NOT_OPEN_FULL_DRAFT");
    }
  }

  private void lockAuthority(TargetProof target) {
    var row =
        dsl.fetchOne(
            "SELECT v.id FROM version v JOIN game g ON g.id = v.identity_source_game_row_id "
                + "AND g.tenant_id = v.identity_source_game_tenant_key AND g.canonical_tenant_id = v.canonical_tenant_id "
                + "AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind "
                + "AND g.tenant_identity_source_game_id = g.id AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id "
                + "WHERE v.id = ? AND v.tenant_id = ? AND v.canonical_tenant_id = ? AND v.canonical_version_id = ? "
                + "AND v.identity_source_game_row_id = ? AND v.identity_source_game_tenant_key = ? AND v.identity_source_provenance_kind = ? FOR UPDATE OF v",
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind());
    if (row == null)
      throw new IllegalStateException("POLICY_CANONICAL_OWNER_AUTHORITY_UNAVAILABLE");
  }

  private static void requireWrite() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Policy writes require caller-owned writable READ_COMMITTED transaction");
    }
  }
}
