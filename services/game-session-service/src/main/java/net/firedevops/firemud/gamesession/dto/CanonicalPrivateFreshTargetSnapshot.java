package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed internal projection of a private FRESH catalog identity that is still PREPARING.
 *
 * <p>This is not a public realm listing, completed World manifest, runtime, or admission target.
 */
public record CanonicalPrivateFreshTargetSnapshot(
    String targetNamespace,
    UUID tenantId,
    String worldSlug,
    String realmSlug,
    UUID realmId,
    String realmDisplayName,
    boolean visible,
    boolean publicProduction,
    String stateScope,
    UUID playableStateNamespaceId,
    String characterCreationPolicy,
    long catalogRevision,
    UUID creationRequestId,
    String requestDigest,
    String receiptDigest,
    UUID preparationRequestId,
    UUID playtestLifecycleId,
    long playtestStateGeneration,
    String preparationState,
    UUID sourceCatalogRealmId,
    UUID targetPolicyId,
    String targetPolicyDigest,
    UUID canonicalVersionId,
    String publishedReleaseBundleRef,
    String publishedReleaseBundleDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CanonicalPrivateFreshTargetSnapshot {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    requireNonNil(tenantId, "tenantId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    Objects.requireNonNull(realmSlug, "realmSlug");
    requireNonNil(realmId, "realmId");
    Objects.requireNonNull(realmDisplayName, "realmDisplayName");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(characterCreationPolicy, "characterCreationPolicy");
    requireNonNil(creationRequestId, "creationRequestId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(receiptDigest, "receiptDigest");
    requireNonNil(preparationRequestId, "preparationRequestId");
    requireNonNil(playtestLifecycleId, "playtestLifecycleId");
    Objects.requireNonNull(preparationState, "preparationState");
    requireNonNil(sourceCatalogRealmId, "sourceCatalogRealmId");
    requireNonNil(targetPolicyId, "targetPolicyId");
    Objects.requireNonNull(targetPolicyDigest, "targetPolicyDigest");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(publishedReleaseBundleRef, "publishedReleaseBundleRef");
    Objects.requireNonNull(publishedReleaseBundleDigest, "publishedReleaseBundleDigest");
    if (visible
        || publicProduction
        || !"ISOLATED".equals(stateScope)
        || catalogRevision < 1L
        || playtestStateGeneration != 1L
        || !"PREPARING".equals(preparationState)
        || playtestLifecycleId.equals(playableStateNamespaceId)
        || realmId.equals(sourceCatalogRealmId)
        || realmId.equals(targetPolicyId)) {
      throw new IllegalArgumentException(
          "Unsupported private FRESH target state; catalog identity must remain non-public and PREPARING");
    }
    if (!requestDigest.matches("sha256:[0-9a-f]{64}")
        || !receiptDigest.matches("sha256:[0-9a-f]{64}")
        || !targetPolicyDigest.matches("sha256:[0-9a-f]{64}")
        || !publishedReleaseBundleDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Private FRESH target digests must be lowercase SHA-256");
    }
    if (realmDisplayName.isBlank()
        || realmDisplayName.codePointCount(0, realmDisplayName.length()) > 128
        || characterCreationPolicy.isBlank()
        || publishedReleaseBundleRef.isBlank()
        || publishedReleaseBundleRef.length() > 1024) {
      throw new IllegalArgumentException("Private FRESH target policy projection is invalid");
    }
  }

  public boolean admissionAllowed() {
    return false;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
