package net.firedevops.firemud.gamedesign.draft;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.AssetSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationOwner;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.jooq.DSLContext;

/**
 * ISOLATED upstream authority; executes actual GD synchronized selection/attempt/operation writes.
 */
public final class IsolatedPublicationOwnerSetup {
  private IsolatedPublicationOwnerSetup() {}

  /** Actual ordinary source/coordinator transaction; does not stipulate complete inventory. */
  public static DraftCommitBinding applyOrdinaryReferences(
      DSLContext dsl, DraftCommitBinding.TargetProof target, List<String> payloads) {
    var head =
        dsl.fetchOne(
            "SELECT source_epoch FROM game_design_asset_source_head WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
            target.canonicalTenantId(),
            target.canonicalVersionId());
    if (head == null) throw new IllegalStateException("ASSET_SOURCE_GENESIS_UNAVAILABLE");
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    var prior = coordinator.readVisibilityFence(target);
    var revisions = new java.util.ArrayList<DraftCommitBinding.RevisionPayload>();
    for (int index = 0; index < payloads.size(); index++) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(index),
              java.util.UUID.randomUUID(),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads.get(index)));
    }
    var binding =
        DraftCommitBinding.create(
            target,
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            prior.map(value -> value.commitId().toString()).orElse("base-commit-0"),
            revisions,
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    AssetSource.SCOPE,
                    target.canonicalVersionId().toString(),
                    AssetSource.SCOPE,
                    AssetSource.SCOPE_ID,
                    head.get("source_epoch", String.class))));
    coordinator.claim(binding);
    coordinator.claimApplicationSlot(binding);
    coordinator.markOwnerInProgress(binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
    var applied = new GameDesignSourceRepository(dsl).apply(binding);
    coordinator.advanceSourceVisibilityFence(
        binding,
        new DraftCommitCoordinatorRepository.CoordinatorProof(
            binding, List.of(applied.ownerOutcome())));
    coordinator.releaseApplicationSlot(binding);
    return binding;
  }

  /**
   * Fixture-only source visibility advance; the coordinator still validates every exact outcome.
   */
  public static void advanceSourceVisibility(
      DSLContext dsl,
      DraftCommitBinding binding,
      List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes) {
    new DraftCommitCoordinatorRepository(dsl)
        .advanceSourceVisibilityFence(
            binding, new DraftCommitCoordinatorRepository.CoordinatorProof(binding, outcomes));
  }

  /** Fixture-only visibility advance; supplied owner authority remains explicitly isolated. */
  public static void advanceIsolatedVisibility(
      DSLContext dsl,
      DraftCommitBinding binding,
      List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes) {
    new DraftCommitCoordinatorRepository(dsl)
        .advanceVisibilityFence(
            binding, new DraftCommitCoordinatorRepository.CoordinatorProof(binding, outcomes));
  }

  /**
   * Caller supplies a writable READ_COMMITTED owner transaction and an actual Draft target/epoch.
   */
  public static GameDesignPublicationOperation retain(
      DSLContext dsl, DraftCommitBinding.TargetProof target, long draftEpoch) throws Exception {
    return retain(dsl, target, draftEpoch, "ISOLATED owner transaction proof");
  }

  public static GameDesignPublicationOperation retain(
      DSLContext dsl, DraftCommitBinding.TargetProof target, long draftEpoch, String notes)
      throws Exception {
    return retain(dsl, target, draftEpoch, notes, false);
  }

  /** Actual source inheritance precedes selection; only the remote owner inputs are isolated. */
  public static GameDesignPublicationOperation retainSourceBacked(
      DSLContext dsl, DraftCommitBinding.TargetProof target, long draftEpoch) throws Exception {
    return retain(dsl, target, draftEpoch, "ISOLATED remote owner transaction proof", true);
  }

  public static GameDesignPublicationOperation retainSourceBacked(
      DSLContext dsl, DraftCommitBinding.TargetProof target, long draftEpoch, String notes)
      throws Exception {
    return retain(dsl, target, draftEpoch, notes, true);
  }

  /** Creates actual synchronized source rows and selection, without any publication operation. */
  public static AuthoredDraftPublishSelectionRepository.SelectionSnapshot selectSourceBackedDraft(
      DSLContext dsl, DraftCommitBinding.TargetProof target, long draftEpoch, String notes)
      throws Exception {
    return selectSourceBackedDraftWithWorld(dsl, target, draftEpoch, notes).selection();
  }

  /**
   * Creates actual synchronized source rows and selection, retaining its exact isolated World seed
   * so publication fixtures can derive matching Account and selector inputs without regenerating
   * the randomized source binding.
   */
  public static SourceBackedSelection selectSourceBackedDraftWithWorld(
      DSLContext dsl, DraftCommitBinding.TargetProof target, long draftEpoch, String notes)
      throws Exception {
    PreparedSelection prepared = prepareSelection(dsl, target, draftEpoch, notes, true);
    return new SourceBackedSelection(prepared.selection(), prepared.world());
  }

  private static GameDesignPublicationOperation retain(
      DSLContext dsl,
      DraftCommitBinding.TargetProof target,
      long draftEpoch,
      String notes,
      boolean captureSources)
      throws Exception {
    PreparedSelection prepared = prepareSelection(dsl, target, draftEpoch, notes, captureSources);
    var selection = prepared.selection().selection();
    var intent = selection.intent();
    var operation =
        IsolatedPublicationOperationFixtures.forSelection(
            AuthoredDraftPublishSelectionBinding.fromStored(
                selection.canonicalJson(), selection.digest()),
            prepared.world());
    if (captureSources) {
      operation =
          new SelectedDraftPublicationOwner(dsl)
              .reserve(intent, operation.account(), operation.world(), operation.inventory())
              .operation();
    } else {
      var attempt = new PublishAttempt();
      attempt.setTenantId(target.gameDesignVersionTenantKey());
      attempt.setPublishWorkflowId(operation.workflowId());
      attempt.setPublishType(PublishType.FULL_VERSION);
      attempt.setVersionId(target.gameDesignVersionRowId());
      var versionRow =
          dsl.fetchOne(
              "SELECT version_number FROM version WHERE id = ?", target.gameDesignVersionRowId());
      if (versionRow == null) {
        throw new IllegalStateException("Selected authored Version row is absent");
      }
      attempt.setVersionNumber(versionRow.get(0, Integer.class));
      attempt.setRequestDigest(selection.digest());
      new PublishAttemptRepository(dsl).save(attempt);
      var publications = new GameDesignPublicationOperationRepository(dsl);
      publications.reserve(operation);
    }
    return operation;
  }

  private static PreparedSelection prepareSelection(
      DSLContext dsl,
      DraftCommitBinding.TargetProof target,
      long draftEpoch,
      String notes,
      boolean captureSources)
      throws Exception {
    var seed = IsolatedPublicationOperationFixtures.fresh(target);
    var draft = seed.account().input().selection().selectedCommit();
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    // The original Draft Account/World result is stipulated upstream fixture evidence, not a new
    // GD Draft-authorization operation or live Account authority issued by this helper.
    coordinator.claim(draft);
    coordinator.claimApplicationSlot(draft);
    var ownerOutcomes = new java.util.ArrayList<DraftCommitCoordinatorRepository.OwnerOutcome>();
    if (captureSources) {
      coordinator.markOwnerInProgress(draft, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
      ownerOutcomes.add(new GameDesignSourceRepository(dsl).apply(draft).ownerOutcome());
    }
    var epochs =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new DraftCommitCoordinatorRepository.AppliedEpoch(
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch(),
                        new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
            .toList();
    var outcome =
        new DraftCommitCoordinatorRepository.OwnerOutcome(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
            draft.commitId(),
            draft.digest(),
            "ISOLATED-world-applied",
            seed.world().appliedResultBytes(),
            epochs);
    coordinator.markOwnerInProgress(draft, DraftCommitBinding.Owner.WORLD_MANAGEMENT);
    coordinator.recordOwnerOutcome(draft, outcome);
    ownerOutcomes.add(outcome);
    var proof = new DraftCommitCoordinatorRepository.CoordinatorProof(draft, ownerOutcomes);
    if (captureSources) {
      coordinator.advanceSourceVisibilityFence(draft, proof);
    } else {
      coordinator.advanceVisibilityFence(draft, proof);
    }
    coordinator.releaseApplicationSlot(draft);
    var intent =
        new AuthoredDraftPublishSelection.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            java.util.UUID.randomUUID().toString(),
            Long.toString(draftEpoch),
            notes,
            draft.requestId(),
            draft.commitId(),
            draft.digest());
    var selection = new AuthoredDraftPublishSelectionRepository(dsl, coordinator).reserve(intent);
    return new PreparedSelection(selection, seed.world());
  }

  private record PreparedSelection(
      AuthoredDraftPublishSelectionRepository.SelectionSnapshot selection,
      WorldPublishedStartLocationEvidence world) {}

  public record SourceBackedSelection(
      AuthoredDraftPublishSelectionRepository.SelectionSnapshot selection,
      WorldPublishedStartLocationEvidence world) {
    public SourceBackedSelection {
      java.util.Objects.requireNonNull(selection, "selection");
      java.util.Objects.requireNonNull(world, "world");
    }
  }

  /** Storage fixture commit only; does not substitute for actual command finalization proof. */
  public static PublishedReleaseBundle commitStorage(
      DSLContext dsl,
      VersionRepository versions,
      GameDesignPublicationOperation operation,
      Supplier<PublishedReleaseBundle> releaseWrite) {
    var attempts = new PublishAttemptRepository(dsl);
    var attempt = attempts.findByPublishWorkflowIdForUpdate(operation.workflowId()).orElseThrow();
    if (attempt.getStatus() == PublishAttemptStatus.SUCCEEDED) {
      attempts.requirePublishedOperation(attempt);
      return releaseWrite.get();
    }
    attempts.requirePublicationPending(attempt);
    var version =
        versions
            .findByTenantIdAndIdForUpdate(operation.tenantKey(), operation.versionId())
            .orElseThrow();
    var snapshot =
        new VersionAssetPublicationRepository(dsl)
            .freezeOrReadSnapshot(operation.tenantKey(), version.getVersionNumber());
    PublishedReleaseBundle bundle = releaseWrite.get();
    if (snapshot.versionId() != operation.versionId()
        || !snapshot.items().isEmpty()
        || bundle.getManifestSchemaVersion() == null
        || bundle.getManifestSchemaVersion() != 1
        || !"[]".equals(bundle.getArtifactDigestsJson())
        || !"[]".equals(bundle.getRequiredManifestAssetKeysJson())
        || bundle.getManifestHash() == null
        || !bundle.getManifestHash().matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalStateException(
          "Isolated publication fixture requires the exact empty asset candidate for its Version");
    }
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(Math.addExact(version.getVersionStateEpoch(), 1));
    versions.save(version);
    var artifact = new VersionAssetArtifact();
    artifact.setTenantId(operation.tenantKey());
    artifact.setVersionId(operation.versionId());
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(1);
    artifact.setExportedVersionNumber(version.getVersionNumber());
    artifact.setManifestHash(bundle.getManifestHash());
    artifact.setLastWorkflowId(operation.workflowId());
    artifact.setManifestSchemaVersion(bundle.getManifestSchemaVersion());
    artifact.setArtifactDigestsJson(bundle.getArtifactDigestsJson());
    artifact.setExportedManifestAssetKeysJson(bundle.getRequiredManifestAssetKeysJson());
    String manifestDigest = bundle.getManifestHash();
    artifact.setPublishedObjectProofsJson(
        "[{\"immutableObjectKey\":\"manifests/sha256/"
            + manifestDigest.substring("sha256:".length())
            + "\",\"contentDigest\":\""
            + manifestDigest
            + "\"}]");
    artifact.setCandidateSnapshotVersionId(snapshot.versionId());
    new VersionAssetArtifactRepository(dsl).save(artifact);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    attempts.save(attempt);
    attempts.sealPublication(attempt, true);
    return bundle;
  }
}
