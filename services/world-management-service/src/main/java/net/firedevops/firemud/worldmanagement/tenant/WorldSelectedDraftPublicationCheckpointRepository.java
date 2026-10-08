package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;

/**
 * Captures the owner-derived checkpoint for one exact, currently applied fresh World graph.
 *
 * <p>This internal seam is intended to run as the {@link WorldDesignPublicationFenceRepository}
 * freeze callback. It does not implement Game Design publication selection, caller authentication,
 * or a public freeze route, and it does not change the generic digest's highest-commit contract.
 */
final class WorldSelectedDraftPublicationCheckpointRepository {
  private static final int REQUIRED_DIGEST_SCHEMA_VERSION = 3;

  private final WorldDesignPublicationFenceRepository fence;
  private final WorldDraftGraphApplicationRepository applications;
  private final WorldDraftTopologyCommitRepository topology;
  private final WorldDraftDesignDigestService digestService;

  WorldSelectedDraftPublicationCheckpointRepository(
      WorldDesignPublicationFenceRepository fence,
      WorldDraftGraphApplicationRepository applications,
      WorldDraftTopologyCommitRepository topology,
      WorldDraftDesignDigestService digestService) {
    this.fence = Objects.requireNonNull(fence, "fence");
    this.applications = Objects.requireNonNull(applications, "applications");
    this.topology = Objects.requireNonNull(topology, "topology");
    this.digestService = Objects.requireNonNull(digestService, "digestService");
  }

  Checkpoint capture(
      WorldDesignPublicationFenceEvidence freeze, WorldDraftTopologyCommitPlan selectedPlan) {
    Objects.requireNonNull(freeze, "freeze");
    Objects.requireNonNull(selectedPlan, "selectedPlan");
    if (!freeze.ownerBinding().equals(selectedPlan.ownerBinding())) {
      throw new ConflictException(
          "World checkpoint selection differs from the exact publication-freeze owner");
    }

    // Reacquiring this exact row inside claimFreeze's callback is reentrant and keeps the same
    // owner lock held while APPLIED history, current rows/epochs, and the schema-3 digest are read.
    var owner = fence.lockOpenAndResolve(freeze.ownerBinding());
    var applied = applications.readAppliedForPublicationCheckpoint(freeze, selectedPlan);
    var stored = topology.readUnderFrozenLock(selectedPlan);
    if (!stored.binding().equals(applied.application().operation().binding())
        || !stored.ownerBinding().equals(freeze.ownerBinding())) {
      throw new ConflictException(
          "World stored topology differs from the exact APPLIED selected graph application");
    }

    var digest =
        digestService.getDraftDesignDigest(
            Long.toString(owner.localTenantKey()), Long.toString(owner.localVersionKey()));
    if (!Long.toString(owner.localTenantKey()).equals(digest.tenantId())
        || !Long.toString(owner.localVersionKey()).equals(digest.scopeValue())
        || digest.digestSchemaVersion() != REQUIRED_DIGEST_SCHEMA_VERSION) {
      throw new ConflictException(
          "World selected graph digest differs from the exact local schema-3 Draft scope");
    }

    return new Checkpoint(
        applied.application().operation().commitId().toString(),
        digest.contentDigest(),
        digest.digestSchemaVersion());
  }
}
