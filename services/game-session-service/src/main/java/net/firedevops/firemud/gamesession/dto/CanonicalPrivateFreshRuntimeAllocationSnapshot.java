package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable private runtime identity provenance, never a running instance or admission authority.
 */
public record CanonicalPrivateFreshRuntimeAllocationSnapshot(
    UUID allocationRequestId,
    UUID gameInstanceId,
    String targetNamespace,
    UUID tenantId,
    String worldSlug,
    String realmSlug,
    UUID realmId,
    long catalogRevision,
    UUID catalogRequestId,
    String catalogRequestDigest,
    String catalogReceiptDigest,
    UUID preparationRequestId,
    String preparationRequestDigest,
    UUID playtestLifecycleId,
    UUID playableStateNamespaceId,
    long playtestStateGeneration,
    UUID canonicalVersionId,
    String publishedReleaseBundleRef,
    String publishedReleaseBundleDigest,
    UUID selectionRequestId,
    long selectionHeadRevision,
    String selectionRequestDigest,
    String requestDigest,
    String receiptDigest) {
  public CanonicalPrivateFreshRuntimeAllocationSnapshot {
    for (UUID identity :
        new UUID[] {
          allocationRequestId,
          gameInstanceId,
          tenantId,
          realmId,
          catalogRequestId,
          preparationRequestId,
          playtestLifecycleId,
          playableStateNamespaceId,
          canonicalVersionId,
          selectionRequestId
        }) {
      if (identity == null || identity.equals(new UUID(0, 0)))
        throw new IllegalArgumentException("Private runtime allocation identities must be non-nil");
    }
    for (String digest :
        new String[] {
          catalogRequestDigest,
          catalogReceiptDigest,
          preparationRequestDigest,
          publishedReleaseBundleDigest,
          selectionRequestDigest,
          requestDigest,
          receiptDigest
        }) {
      if (digest == null || !digest.matches("sha256:[0-9a-f]{64}"))
        throw new IllegalArgumentException("Private runtime allocation requires SHA-256 digests");
    }
    Objects.requireNonNull(targetNamespace);
    Objects.requireNonNull(worldSlug);
    Objects.requireNonNull(realmSlug);
    Objects.requireNonNull(publishedReleaseBundleRef);
    if (targetNamespace.isBlank()
        || worldSlug.isBlank()
        || realmSlug.isBlank()
        || publishedReleaseBundleRef.isBlank()
        || catalogRevision < 1
        || selectionHeadRevision < 1
        || playtestStateGeneration != 1
        || gameInstanceId.equals(realmId)
        || gameInstanceId.equals(playtestLifecycleId)
        || gameInstanceId.equals(playableStateNamespaceId))
      throw new IllegalArgumentException("Unsupported private FRESH runtime allocation binding");
  }

  /** Comparing this historical provenance does not establish currentness or permit effects. */
  public boolean matches(CanonicalPrivateFreshTargetSnapshot target) {
    return target != null
        && targetNamespace.equals(target.targetNamespace())
        && tenantId.equals(target.tenantId())
        && worldSlug.equals(target.worldSlug())
        && realmSlug.equals(target.realmSlug())
        && realmId.equals(target.realmId())
        && catalogRevision == target.catalogRevision()
        && catalogRequestId.equals(target.creationRequestId())
        && catalogRequestDigest.equals(target.requestDigest())
        && catalogReceiptDigest.equals(target.receiptDigest())
        && preparationRequestId.equals(target.preparationRequestId())
        && playtestLifecycleId.equals(target.playtestLifecycleId())
        && playableStateNamespaceId.equals(target.playableStateNamespaceId())
        && playtestStateGeneration == target.playtestStateGeneration()
        && canonicalVersionId.equals(target.canonicalVersionId())
        && publishedReleaseBundleRef.equals(target.publishedReleaseBundleRef())
        && publishedReleaseBundleDigest.equals(target.publishedReleaseBundleDigest());
  }

  public boolean admissionAllowed() {
    return false;
  }
}
