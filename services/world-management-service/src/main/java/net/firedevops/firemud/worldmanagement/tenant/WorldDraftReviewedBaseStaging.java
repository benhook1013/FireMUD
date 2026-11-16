package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;

/**
 * Unregistered read-only validation seam for one existing REGION delta against its reviewed base.
 *
 * <p>This committed read runs before any future write transaction. No current graph is replaced, no
 * SQL mutation is staged, and no COMMIT_ORDER or authorization is acquired. A historical graph is
 * provenance only; future storage must apply the separate delta under V25 OPEN with current scoped
 * CAS and the original protected operation. Game Design companion production remains an integration
 * prerequisite. This class has no public caller or transport registration.
 */
final class WorldDraftReviewedBaseStaging {
  private final WorldDraftGraphApplicationRepository applications;

  WorldDraftReviewedBaseStaging(WorldDraftGraphApplicationRepository applications) {
    this.applications = Objects.requireNonNull(applications, "applications");
  }

  StagedDelta readAndStage(
      String receiverNamespace,
      WorldDraftTerminalOperation originalOperation,
      WorldDraftReviewedBaseCompanion companion) {
    Objects.requireNonNull(originalOperation, "originalOperation");
    Objects.requireNonNull(companion, "companion");
    if (!GrpcPeerIdentity.isValidNamespace(receiverNamespace)
        || !receiverNamespace.equals(originalOperation.ownerBinding().targetNamespace())
        || !receiverNamespace.equals(companion.baseVisibility().request().targetNamespace())) {
      throw new IllegalArgumentException("Reviewed base differs from the exact World namespace");
    }
    var account =
        DraftAuthorizationFenceBinding.fromStored(originalOperation.accountBindingBytes());
    var binding = originalOperation.binding();
    if (!binding.equals(companion.reviewedIncomingBinding())
        || !Arrays.equals(
            binding.canonicalBytes(), companion.reviewedIncomingBinding().canonicalBytes())
        || !Arrays.equals(binding.canonicalBytes(), account.gameDesignBinding())
        || !Arrays.equals(binding.canonicalBytes(), account.normalizedInput())
        || !binding.requestId().equals(account.requestId())
        || !binding.commitId().equals(account.commitId())
        || !binding.target().canonicalTenantId().equals(account.tenantId())
        || !binding.target().canonicalVersionId().equals(account.versionId())
        || !binding.baseCommitId().equals(account.baseCommitId())
        || !binding.digest().equals(account.inputDigest())) {
      throw new IllegalArgumentException(
          "Reviewed base differs from the unchanged complete Account input");
    }
    var delta =
        WorldDraftExistingRegionCommitPlan.create(binding, originalOperation.ownerBinding());
    companion.baseVisibility().requireValid();
    // The existing repository independently checks retained APPLIED identity, bytes and epochs.
    // There is deliberately no owner-local, latest-pointer or earlier-lineage fallback.
    var historicalBase =
        applications
            .readSynchronized(companion.baseVisibility())
            .orElseThrow(
                () -> new ConflictException("Exact synchronized World reviewed base unavailable"));
    if (!binding.target().canonicalTenantId().equals(historicalBase.tenantId())
        || !binding.target().canonicalVersionId().equals(historicalBase.versionId())) {
      throw new ConflictException("World reviewed-base snapshot differs from the canonical target");
    }
    var selected =
        historicalBase.family(Family.REGION).stream()
            .filter(row -> row.template().templateId().equals(delta.regionTemplateId()))
            .toList();
    if (selected.size() != 1
        || !selected.getFirst().scope().equals(selected.getFirst().template())) {
      throw new ConflictException("Reviewed base lacks the exact scoped REGION template");
    }
    return new StagedDelta(originalOperation, companion, historicalBase, delta);
  }

  /**
   * Historical retained graph and incoming delta stay separate; neither is a current graph write.
   */
  record StagedDelta(
      WorldDraftTerminalOperation originalOperation,
      WorldDraftReviewedBaseCompanion companion,
      WorldCanonicalAuthoredGraph historicalBase,
      WorldDraftExistingRegionCommitPlan delta) {}
}
