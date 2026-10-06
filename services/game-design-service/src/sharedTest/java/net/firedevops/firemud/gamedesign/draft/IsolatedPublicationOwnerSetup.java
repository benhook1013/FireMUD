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
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.jooq.DSLContext;

/**
 * ISOLATED upstream authority; executes actual GD synchronized selection/attempt/operation writes.
 */
public final class IsolatedPublicationOwnerSetup {
  private IsolatedPublicationOwnerSetup() {}

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
    var seed = IsolatedPublicationOperationFixtures.fresh(target);
    var draft = seed.account().input().selection().selectedCommit();
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    // The original Draft Account/World result is stipulated upstream fixture evidence, not a new
    // GD Draft-authorization operation or live Account authority issued by this helper.
    coordinator.claim(draft);
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
    coordinator.advanceVisibilityFence(
        draft, new DraftCommitCoordinatorRepository.CoordinatorProof(draft, List.of(outcome)));
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
    new GameDesignPublicationOperationRepository(dsl).reserve(operation);
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
    PublishedReleaseBundle bundle = releaseWrite.get();
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
    artifact.setPublishedObjectProofsJson("[]");
    new VersionAssetArtifactRepository(dsl).save(artifact);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    attempts.save(attempt);
    attempts.sealPublication(attempt, true);
    return bundle;
  }
}
