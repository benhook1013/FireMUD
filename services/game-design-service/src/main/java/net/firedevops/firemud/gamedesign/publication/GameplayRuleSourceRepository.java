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
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual coordinated author history. Account mutation authority must be supplied by its owner. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Owner-local persistence collaborator")
public final class GameplayRuleSourceRepository {
  private final DSLContext dsl;
  private final DraftCommitCoordinatorRepository coordinator;

  public GameplayRuleSourceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    coordinator = new DraftCommitCoordinatorRepository(dsl);
  }

  public GameplayRuleSnapshot.Genesis enrollFreshDraft(TargetProof target) {
    write();
    lock(target);
    var existing = readGenesis(target);
    if (existing.isPresent()) return existing.orElseThrow();
    Record witness =
        dsl.fetchOne(
            "SELECT creation_transaction_id FROM game_design_realm_policy_version_insert "
                + "WHERE version_id = ? AND creation_transaction_id = pg_current_xact_id()::TEXT",
            target.gameDesignVersionRowId());
    if (witness == null) throw new IllegalStateException("GAMEPLAY_RULE_FRESH_INSERT_UNAVAILABLE");
    var result =
        new GameplayRuleSnapshot.Genesis(
            target,
            UUID.randomUUID(),
            witness.get(0, String.class),
            GameplayRuleManifest.explicitEmpty());
    dsl.execute(
        "INSERT INTO game_design_gameplay_rule_genesis (canonical_tenant_id, canonical_version_id, "
            + "version_id, receipt_id, creation_transaction_id, target_json, inventory_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        target.gameDesignVersionRowId(),
        result.receiptId(),
        result.creationTransactionId(),
        CommandSource.canonical(CommandSource.targetObject(target)),
        result.inventory().canonicalJson());
    dsl.execute(
        "INSERT INTO game_design_gameplay_rule_head (canonical_tenant_id, canonical_version_id, source_epoch, genesis_receipt_id) "
            + "VALUES (?, ?, '0', ?)",
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        result.receiptId());
    if (!result.equals(readGenesis(target).orElseThrow()))
      throw new IllegalStateException("GAMEPLAY_RULE_GENESIS_READBACK_CONFLICT");
    return result;
  }

  public Optional<GameplayRuleSnapshot.Genesis> readGenesis(TargetProof target) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_gameplay_rule_genesis WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (row == null) return Optional.empty();
    if (!CommandSource.canonical(CommandSource.targetObject(target))
            .equals(row.get("target_json", String.class))
        || target.gameDesignVersionRowId() != row.get("version_id", Long.class))
      throw new IllegalStateException("GAMEPLAY_RULE_GENESIS_TARGET_CONFLICT");
    return Optional.of(
        new GameplayRuleSnapshot.Genesis(
            target,
            row.get("receipt_id", UUID.class),
            row.get("creation_transaction_id", String.class),
            GameplayRuleManifest.fromStored(row.get("inventory_json", String.class))));
  }

  public Optional<GameplayRuleSnapshot.Application> apply(DraftCommitBinding binding) {
    write();
    lock(binding.target());
    exact(binding);
    var prior = application(binding);
    if (prior.isPresent()) return prior;
    var mutations = GameplayRuleSource.mutations(binding);
    if (mutations.isEmpty()) return Optional.empty();
    open(binding.target());
    var slot = coordinator.readApplicationSlot(binding.target()).orElseThrow();
    var owner =
        coordinator
            .read(binding.target(), binding.requestId())
            .orElseThrow()
            .ownerStates()
            .get(Owner.GAME_DESIGN_CONTROL_PLANE);
    if (!slot.commitId().equals(binding.commitId())
        || !slot.requestId().equals(binding.requestId())
        || owner == null
        || owner.status() != DraftCommitCoordinatorRepository.OwnerStatus.IN_PROGRESS
            && owner.status() != DraftCommitCoordinatorRepository.OwnerStatus.UNKNOWN)
      throw new IllegalStateException("GAMEPLAY_RULE_COORDINATOR_APPLICATION_UNAVAILABLE");
    Record head = head(binding.target());
    String epoch = head.get("source_epoch", String.class);
    var units =
        binding.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE).stream()
            .filter(GameplayRuleSource::isScope)
            .toList();
    if (units.size() != 1
        || !units.getFirst().aggregateId().equals(binding.target().canonicalVersionId().toString())
        || !epoch.equals(units.getFirst().expectedEpoch()))
      throw new IllegalStateException("GAMEPLAY_RULE_EPOCH_CONFLICT");
    UUID inherited = head.get("visible_commit_id", UUID.class);
    var fence = coordinator.readVisibilityFence(binding.target());
    if (inherited == null
        ? fence.isPresent() || !"0".equals(epoch)
        : fence.isEmpty() || !inherited.equals(fence.orElseThrow().commitId()))
      throw new IllegalStateException("GAMEPLAY_RULE_PREVIOUS_FENCE_CHANGED");
    List<GameplayRuleSource.Entry> previous =
        inherited == null
            ? List.of()
            : readSnapshot(binding.target(), inherited).orElseThrow().entries();
    var snapshot =
        new GameplayRuleSnapshot(
            binding,
            new BigInteger(epoch).add(BigInteger.ONE).toString(),
            inherited,
            head.get("genesis_receipt_id", UUID.class),
            GameplayRuleSource.replay(previous, binding));
    for (var mutation : mutations)
      dsl.execute(
          "INSERT INTO game_design_gameplay_rule_revision "
              + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, revision_id, revision_order, operation_kind, family, definition_key, payload_json) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          binding.target().canonicalTenantId(),
          binding.target().canonicalVersionId(),
          binding.requestId(),
          binding.commitId(),
          mutation.revisionId(),
          Integer.parseInt(mutation.revisionOrder()),
          mutation.operation().name(),
          mutation.family().name(),
          mutation.key(),
          mutation.payload());
    var result = new GameplayRuleSnapshot.Application(binding, epoch, snapshot);
    dsl.execute(
        "INSERT INTO game_design_gameplay_rule_application (canonical_tenant_id, canonical_version_id, request_id, commit_id, "
            + "expected_epoch, snapshot_json, result_bytes) VALUES (?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        epoch,
        snapshot.canonicalJson(),
        result.canonicalBytes());
    if (dsl.execute(
            "UPDATE game_design_gameplay_rule_head SET source_epoch = ?, applied_commit_id = ? "
                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND source_epoch = ? AND visible_commit_id IS NOT DISTINCT FROM CAST(? AS UUID)",
            snapshot.sourceEpoch(),
            binding.commitId(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            epoch,
            inherited)
        != 1) throw new IllegalStateException("GAMEPLAY_RULE_EPOCH_CONFLICT");
    return Optional.of(application(binding).orElseThrow());
  }

  public GameplayRuleSnapshot captureSynchronized(DraftCommitBinding binding) {
    write();
    lock(binding.target());
    exact(binding);
    var existing = readSnapshot(binding.target(), binding.commitId());
    if (existing.isPresent()) {
      if (!existing.orElseThrow().binding().equals(binding))
        throw new IllegalStateException("GAMEPLAY_RULE_SNAPSHOT_BINDING_CONFLICT");
      return existing.orElseThrow();
    }
    open(binding.target());
    requireFence(binding);
    var head = head(binding.target());
    UUID inherited = head.get("visible_commit_id", UUID.class);
    var applied = application(binding);
    GameplayRuleSnapshot snapshot;
    if (!GameplayRuleSource.mutations(binding).isEmpty()) {
      snapshot = applied.orElseThrow().snapshot();
      if (!Objects.equals(inherited, snapshot.inheritedCommitId()))
        throw new IllegalStateException("GAMEPLAY_RULE_PREDECESSOR_CHANGED");
    } else {
      if (applied.isPresent())
        throw new IllegalStateException("GAMEPLAY_RULE_UNDECLARED_APPLICATION");
      var entries =
          inherited == null
              ? List.<GameplayRuleSource.Entry>of()
              : readSnapshot(binding.target(), inherited).orElseThrow().entries();
      if (inherited == null && !"0".equals(head.get("source_epoch", String.class)))
        throw new IllegalStateException("GAMEPLAY_RULE_GENESIS_NOT_CURRENT");
      snapshot =
          new GameplayRuleSnapshot(
              binding,
              head.get("source_epoch", String.class),
              inherited,
              head.get("genesis_receipt_id", UUID.class),
              entries);
    }
    if (!snapshot.sourceEpoch().equals(head.get("source_epoch", String.class)))
      throw new IllegalStateException("GAMEPLAY_RULE_PARTIAL_WRITE_NOT_VISIBLE");
    dsl.execute(
        "INSERT INTO game_design_gameplay_rule_snapshot (canonical_tenant_id, canonical_version_id, request_id, commit_id, "
            + "snapshot_json, snapshot_digest) VALUES (?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        snapshot.canonicalJson(),
        snapshot.digest());
    if (dsl.execute(
            "UPDATE game_design_gameplay_rule_head SET visible_commit_id = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                + "AND visible_commit_id IS NOT DISTINCT FROM CAST(? AS UUID) AND source_epoch = ?",
            binding.commitId(),
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            inherited,
            snapshot.sourceEpoch())
        != 1) throw new IllegalStateException("GAMEPLAY_RULE_VISIBILITY_CONFLICT");
    return readSnapshot(binding.target(), binding.commitId()).orElseThrow();
  }

  public Optional<GameplayRuleSnapshot> readSnapshot(TargetProof target, UUID commitId) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_gameplay_rule_snapshot WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            commitId);
    if (row == null) return Optional.empty();
    var snapshot = GameplayRuleSnapshot.fromStored(row.get("snapshot_json", String.class));
    if (!snapshot.binding().target().equals(target)
        || !snapshot.binding().commitId().equals(commitId)
        || !snapshot.binding().requestId().equals(row.get("request_id", UUID.class))
        || !snapshot.digest().equals(row.get("snapshot_digest", String.class)))
      throw new IllegalStateException("GAMEPLAY_RULE_SNAPSHOT_READBACK_CONFLICT");
    exact(snapshot.binding());
    var genesis = readGenesis(target).orElseThrow();
    if (!snapshot.genesisReceiptId().equals(genesis.receiptId()))
      throw new IllegalStateException("GAMEPLAY_RULE_GENESIS_CHANGED");
    var fence =
        dsl.fetchOne(
            "SELECT input_digest FROM game_design_draft_commit_visibility_fence WHERE canonical_tenant_id = ? "
                + "AND canonical_version_id = ? AND request_id = ? AND commit_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            snapshot.binding().requestId(),
            commitId);
    if (fence == null || !snapshot.binding().digest().equals(fence.get(0, String.class)))
      throw new IllegalStateException("GAMEPLAY_RULE_SNAPSHOT_FENCE_CONFLICT");
    verifyEntries(snapshot);
    return Optional.of(snapshot);
  }

  /** No generic rule revision or legacy effective command may disappear from a complete handoff. */
  public GameplayRuleSnapshot requireCompleteForGameLogic(TargetProof target, UUID commitId) {
    var snapshot =
        readSnapshot(target, commitId)
            .orElseThrow(
                () -> new IllegalStateException("GAMEPLAY_RULE_SELECTED_SOURCE_UNAVAILABLE"));
    // Generic revisions have no authorized commit/source binding, even when IDs or payloads match.
    if (dsl.fetchOne(
            "SELECT 1 FROM revision WHERE tenant_id = ? AND version_id = ? "
                + "AND revision_kind = 'GAMEPLAY_RULE' LIMIT 1",
            target.gameDesignVersionTenantKey(),
            target.gameDesignVersionRowId())
        != null) {
      throw new IllegalStateException("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    }
    var commands =
        new CommandSourceRepository(dsl)
            .readSnapshot(target, commitId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "GAMEPLAY_RULE_LEGACY_COMMAND_INVENTORY_UNAVAILABLE"));
    if (!commands.binding().equals(snapshot.binding()) || !commands.definitions().isEmpty())
      throw new IllegalStateException("GAMEPLAY_RULE_LEGACY_COMMAND_SOURCE_NOT_CONVERGED");
    return snapshot;
  }

  private Optional<GameplayRuleSnapshot.Application> application(DraftCommitBinding binding) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_gameplay_rule_application WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
            binding.target().canonicalTenantId(),
            binding.target().canonicalVersionId(),
            binding.commitId());
    if (row == null) return Optional.empty();
    var result =
        new GameplayRuleSnapshot.Application(
            binding,
            row.get("expected_epoch", String.class),
            GameplayRuleSnapshot.fromStored(row.get("snapshot_json", String.class)));
    if (!binding.requestId().equals(row.get("request_id", UUID.class))
        || !Arrays.equals(result.canonicalBytes(), row.get("result_bytes", byte[].class)))
      throw new IllegalStateException("GAMEPLAY_RULE_APPLICATION_READBACK_CONFLICT");
    var stored =
        dsl.fetch(
                "SELECT payload_json FROM game_design_gameplay_rule_revision WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                    + "AND commit_id = ? ORDER BY revision_order",
                binding.target().canonicalTenantId(),
                binding.target().canonicalVersionId(),
                binding.commitId())
            .getValues("payload_json", String.class);
    if (!stored.equals(
        GameplayRuleSource.mutations(binding).stream()
            .map(GameplayRuleSource.Mutation::payload)
            .toList())) throw new IllegalStateException("GAMEPLAY_RULE_REVISION_READBACK_CONFLICT");
    verifyEntries(result.snapshot());
    return Optional.of(result);
  }

  private void verifyEntries(GameplayRuleSnapshot snapshot) {
    for (var entry : snapshot.entries()) {
      exact(entry.sourceBinding());
      var mutation =
          new GameplayRuleSource.Mutation(
              entry.sourceBinding(),
              entry.revisionOrder(),
              entry.revisionId(),
              GameplayRuleSource.OperationKind.UPSERT,
              entry.family(),
              entry.definition().key(),
              entry.definition());
      var row =
          dsl.fetchOne(
              "SELECT payload_json FROM game_design_gameplay_rule_revision WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                  + "AND request_id = ? AND commit_id = ? AND revision_id = ? AND revision_order = ? AND family = ? AND definition_key = ? AND operation_kind = 'UPSERT'",
              snapshot.binding().target().canonicalTenantId(),
              snapshot.binding().target().canonicalVersionId(),
              entry.sourceBinding().requestId(),
              entry.sourceBinding().commitId(),
              entry.revisionId(),
              Integer.parseInt(entry.revisionOrder()),
              entry.family().name(),
              entry.definition().key());
      if (row == null || !mutation.payload().equals(row.get(0, String.class)))
        throw new IllegalStateException("GAMEPLAY_RULE_ENTRY_PROVENANCE_CHANGED");
    }
  }

  private Record head(TargetProof target) {
    readGenesis(target)
        .orElseThrow(() -> new IllegalStateException("GAMEPLAY_RULE_GENESIS_UNAVAILABLE"));
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT * FROM game_design_gameplay_rule_head WHERE canonical_tenant_id = ? AND canonical_version_id = ? FOR UPDATE",
            target.canonicalTenantId(),
            target.canonicalVersionId()),
        "GAMEPLAY_RULE_HEAD_UNAVAILABLE");
  }

  private void exact(DraftCommitBinding binding) {
    if (!coordinator
        .read(binding.target(), binding.requestId())
        .orElseThrow()
        .binding()
        .equals(binding)) throw new IllegalStateException("GAMEPLAY_RULE_BINDING_CHANGED");
  }

  private void requireFence(DraftCommitBinding binding) {
    var fence = coordinator.readVisibilityFence(binding.target()).orElseThrow();
    if (!binding.commitId().equals(fence.commitId())
        || !binding.requestId().equals(fence.requestId())
        || !binding.digest().equals(fence.inputDigest())
        || coordinator.read(binding.target(), binding.requestId()).orElseThrow().workflowState()
            != DraftCommitCoordinatorRepository.WorkflowState.SYNCHRONIZED)
      throw new IllegalStateException("GAMEPLAY_RULE_SYNCHRONIZED_FENCE_UNAVAILABLE");
  }

  private void open(TargetProof target) {
    if (dsl.fetchOne(
                "SELECT id FROM version WHERE id = ? AND version_state = 'DRAFT' AND NOT is_script_only "
                    + "AND base_version_id IS NULL AND script_patch_version IS NULL",
                target.gameDesignVersionRowId())
            == null
        || dsl.fetchOne(
                "SELECT 1 FROM game_design_authored_draft_publish_selection WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                target.canonicalTenantId(),
                target.canonicalVersionId())
            != null) throw new IllegalStateException("GAMEPLAY_RULE_FROZEN_OR_NOT_DRAFT");
  }

  private void lock(TargetProof target) {
    var repository = new net.firedevops.firemud.gamedesign.repository.VersionRepository(dsl);
    var qualified =
        repository
            .findByCanonicalTenantIdAndCanonicalVersionId(
                target.canonicalTenantId(), target.canonicalVersionId())
            .orElseThrow();
    var version =
        repository
            .findByTenantIdAndIdForUpdate(
                target.gameDesignVersionTenantKey(), target.gameDesignVersionRowId())
            .orElseThrow();
    var actual =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    if (!target.equals(actual) || !qualified.getId().equals(version.getId()))
      throw new IllegalStateException("GAMEPLAY_RULE_TARGET_CHANGED");
  }

  private static void write() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
      throw new IllegalStateException(
          "Gameplay rule source requires writable READ_COMMITTED transaction");
  }
}
