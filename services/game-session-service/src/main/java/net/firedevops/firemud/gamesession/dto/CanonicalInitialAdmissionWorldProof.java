package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact World-owned ACTIVE lifecycle and initial-admission hold read returned outside SQL. */
public record CanonicalInitialAdmissionWorldProof(
    String initialAdmissionRequestId,
    String requestDigest,
    String targetNamespace,
    UUID canonicalTenantId,
    String worldSlug,
    UUID realmId,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID canonicalGameInstanceId,
    UUID canonicalVersionId,
    String lifecycleState,
    long activeLifecycleEpoch,
    CanonicalInitialAdmissionRequest.OriginKind originKind,
    long expectedCatalogRevision,
    Long expectedPriorPointerVersion,
    UUID holdId,
    UUID holdFence,
    String holdBindingDigest) {
  private static final Pattern WORLD_BINDING_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public CanonicalInitialAdmissionWorldProof {
    Objects.requireNonNull(initialAdmissionRequestId, "initialAdmissionRequestId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    Objects.requireNonNull(realmId, "realmId");
    Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(playableStateScope, "playableStateScope");
    Objects.requireNonNull(canonicalGameInstanceId, "canonicalGameInstanceId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    Objects.requireNonNull(originKind, "originKind");
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(holdFence, "holdFence");
    if (!WORLD_BINDING_DIGEST
        .matcher(Objects.requireNonNull(holdBindingDigest, "holdBindingDigest"))
        .matches()) {
      throw new IllegalArgumentException("World hold proof has an invalid binding digest");
    }
    if (activeLifecycleEpoch <= 0L
        || expectedCatalogRevision <= 0L
        || !"ACTIVE".equals(lifecycleState)) {
      throw new IllegalArgumentException("World proof must contain an exact positive ACTIVE epoch");
    }
    if ((originKind == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER
            && expectedPriorPointerVersion != null)
        || (originKind == CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED
            && (expectedPriorPointerVersion == null || expectedPriorPointerVersion <= 0L))) {
      throw new IllegalArgumentException("World hold proof has an invalid tagged origin");
    }
  }

  public void requireMatches(CanonicalInitialAdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    if (!initialAdmissionRequestId.equals(request.initialAdmissionRequestId())
        || !requestDigest.equals(request.requestDigest())
        || !targetNamespace.equals(request.targetNamespace())
        || !canonicalTenantId.equals(request.canonicalTenantId())
        || !worldSlug.equals(request.worldSlug())
        || !realmId.equals(request.realmId())
        || !playableStateNamespaceId.equals(request.playableStateNamespaceId())
        || !playableStateScope.equals(request.playableStateScope())
        || !canonicalGameInstanceId.equals(request.canonicalGameInstanceId())
        || !canonicalVersionId.equals(request.canonicalVersionId())
        || activeLifecycleEpoch != request.activeLifecycleEpoch()
        || originKind != request.originKind()
        || expectedCatalogRevision != request.expectedCatalogRevision()
        || !Objects.equals(expectedPriorPointerVersion, request.expectedPriorPointerVersion())
        || !holdId.equals(request.holdId())
        || !holdFence.equals(request.holdFence())
        || !holdBindingDigest.equals(request.holdBindingDigest())) {
      throw new IllegalArgumentException(
          "World ACTIVE/hold proof does not match the exact initial admission request");
    }
  }
}
