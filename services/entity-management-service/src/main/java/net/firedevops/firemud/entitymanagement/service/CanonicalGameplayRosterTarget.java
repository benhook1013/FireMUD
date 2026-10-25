package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentity;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/** Complete caller-echoed target precondition, independently resolved by the trusted owner port. */
public record CanonicalGameplayRosterTarget(
    UUID tenantUuid,
    UUID realmUuid,
    String worldSlug,
    String realmSlug,
    UUID gameInstanceUuid,
    long catalogRevision,
    long pointerVersion,
    long activeWorldEpoch,
    UUID canonicalVersionUuid,
    String publishedPolicyDigest,
    String publishedReleaseBundleRef,
    String admissionPointerSnapshotDigest,
    String publishedOwnerProofDigest,
    UUID playableStateNamespaceId,
    PlayableStateScope playableStateScope,
    CanonicalGameplayRosterEntryPolicy entryPolicy) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String DIGEST_PATTERN = "[0-9a-f]{64}";
  private static final String SLUG_PATTERN = "[a-z0-9][a-z0-9-]{0,63}";
  private static final int MAX_RELEASE_REF_LENGTH = 1024;

  public CanonicalGameplayRosterTarget {
    requireNonNil(tenantUuid, "tenantUuid");
    requireNonNil(realmUuid, "realmUuid");
    requireNonNil(gameInstanceUuid, "gameInstanceUuid");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    requireSlug(worldSlug, "worldSlug");
    requireSlug(realmSlug, "realmSlug");
    requirePositive(catalogRevision, "catalogRevision");
    requirePositive(pointerVersion, "pointerVersion");
    requirePositive(activeWorldEpoch, "activeWorldEpoch");
    requireNonNil(canonicalVersionUuid, "canonicalVersionUuid");
    requireDigest(publishedPolicyDigest, "publishedPolicyDigest");
    requireDigest(admissionPointerSnapshotDigest, "admissionPointerSnapshotDigest");
    requireDigest(publishedOwnerProofDigest, "publishedOwnerProofDigest");
    requireReleaseRef(publishedReleaseBundleRef);
    ActorIdentity.requireScope(playableStateScope);
    Objects.requireNonNull(entryPolicy, "entryPolicy");
  }

  public static CanonicalGameplayRosterTarget fromWire(
      net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget target) {
    return new CanonicalGameplayRosterTarget(
        UUID.fromString(target.getTenantUuid()),
        UUID.fromString(target.getRealmUuid()),
        target.getWorldSlug(),
        target.getRealmSlug(),
        UUID.fromString(target.getGameInstanceUuid()),
        target.getCatalogRevision(),
        target.getPointerVersion(),
        target.getActiveWorldEpoch(),
        UUID.fromString(target.getCanonicalVersionUuid()),
        target.getPublishedPolicyDigest(),
        target.getPublishedReleaseBundleRef(),
        target.getAdmissionPointerSnapshotDigest(),
        target.getPublishedOwnerProofDigest(),
        UUID.fromString(target.getPlayableStateNamespaceUuid()),
        target.getPlayableStateScope(),
        CanonicalGameplayRosterEntryPolicy.valueOf(
            target.getEntryPolicy().name().replace("CANONICAL_ROSTER_ENTRY_POLICY_", "")));
  }

  public net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget toWire() {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(tenantUuid.toString())
        .setRealmUuid(realmUuid.toString())
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .setGameInstanceUuid(gameInstanceUuid.toString())
        .setCatalogRevision(catalogRevision)
        .setPointerVersion(pointerVersion)
        .setActiveWorldEpoch(activeWorldEpoch)
        .setCanonicalVersionUuid(canonicalVersionUuid.toString())
        .setPublishedPolicyDigest(publishedPolicyDigest)
        .setPublishedReleaseBundleRef(publishedReleaseBundleRef)
        .setAdmissionPointerSnapshotDigest(admissionPointerSnapshotDigest)
        .setPublishedOwnerProofDigest(publishedOwnerProofDigest)
        .setPlayableStateNamespaceUuid(playableStateNamespaceId.toString())
        .setPlayableStateScope(playableStateScope)
        .setEntryPolicy(
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy.valueOf(
                "CANONICAL_ROSTER_ENTRY_POLICY_" + entryPolicy.name()))
        .build();
  }

  private static void requireNonNil(UUID value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be non-nil");
    }
  }

  private static void requirePositive(long value, String fieldName) {
    if (value <= 0L) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
  }

  private static void requireSlug(String value, String fieldName) {
    if (value == null || !value.matches(SLUG_PATTERN)) {
      throw new IllegalArgumentException(fieldName + " must be a canonical selector");
    }
  }

  private static void requireDigest(String value, String fieldName) {
    if (value == null || !value.matches(DIGEST_PATTERN)) {
      throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 digest");
    }
  }

  private static void requireReleaseRef(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.trim())
        || value.length() > MAX_RELEASE_REF_LENGTH
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("publishedReleaseBundleRef is incomplete");
    }
  }
}
