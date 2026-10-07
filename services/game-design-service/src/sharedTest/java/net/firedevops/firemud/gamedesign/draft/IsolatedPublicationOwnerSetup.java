package net.firedevops.firemud.gamedesign.draft;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
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
    return retainSourceBacked(dsl, target, draftEpoch, "ISOLATED remote owner transaction proof");
  }

  /** Actual source inheritance precedes selection; only the remote owner inputs are isolated. */
  public static GameDesignPublicationOperation retainSourceBacked(
      DSLContext dsl, DraftCommitBinding.TargetProof target, long draftEpoch, String notes)
      throws Exception {
    return retain(dsl, target, draftEpoch, notes, true);
  }

  private static GameDesignPublicationOperation retain(
      DSLContext dsl,
      DraftCommitBinding.TargetProof target,
      long draftEpoch,
      String notes,
      boolean captureSources)
      throws Exception {
    var sources = new net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository(dsl);
    var reviewed = new GameDesignReviewedBaseRepository(dsl);
    var evidence =
        captureSources
            ? reviewed.resolve(
                target,
                net.firedevops.firemud.common.authoring.DraftBaseReference.parse(
                    "genesis:" + sources.readGenesis(target).orElseThrow().policy().receiptId()))
            : null;
    var seed =
        captureSources
            ? IsolatedPublicationOperationFixtures.fresh(
                target, evidence.reference().canonicalValue())
            : IsolatedPublicationOperationFixtures.fresh(target);
    var draft = seed.account().input().selection().selectedCommit();
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    // The original Draft Account/World result is stipulated upstream fixture evidence, not a new
    // GD Draft-authorization operation or live Account authority issued by this helper.
    if (captureSources) {
      sources.claimReviewed(draft, evidence);
    } else {
      coordinator.claim(draft);
    }
    coordinator.claimApplicationSlot(draft);
    var epochs =
        draft.affectedUnits().stream()
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
    var proof = new DraftCommitCoordinatorRepository.CoordinatorProof(draft, List.of(outcome));
    if (captureSources) {
      coordinator.advanceSourceVisibilityFence(draft, proof);
    } else {
      coordinator.advanceVisibilityFence(draft, proof);
    }
    coordinator.releaseApplicationSlot(draft);
    var selection =
        new AuthoredDraftPublishSelectionRepository(dsl, coordinator)
            .reserve(
                new AuthoredDraftPublishSelection.PublishIntent(
                    target.canonicalTenantId(),
                    target.canonicalVersionId(),
                    java.util.UUID.randomUUID().toString(),
                    Long.toString(draftEpoch),
                    notes,
                    draft.requestId(),
                    draft.commitId(),
                    draft.digest()))
            .selection();
    var operation =
        IsolatedPublicationOperationFixtures.forSelection(
            AuthoredDraftPublishSelectionBinding.fromStored(
                selection.canonicalJson(), selection.digest()),
            seed.world());
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
    GameDesignPublicationOperationRepository operations =
        new GameDesignPublicationOperationRepository(dsl);
    if (captureSources) {
      // Freeze both exact selected source snapshots and verify their operation-bound readback.
      operations.reserveSourceBacked(operation);
    } else {
      operations.reserve(operation);
    }
    return operation;
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
