package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentity;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/**
 * Caller-echoed complete target used only as an assignment precondition and grant correlation.
 * Owner evidence remains the sole source of authority for these values.
 */
public record PreseededActorAssignmentExpectedTarget(
    UUID canonicalTenantUuid,
    UUID realmUuid,
    String worldSlug,
    String realmSlug,
    String gameInstanceId,
    long catalogRevision,
    UUID canonicalVersionUuid,
    String frozenPolicyDigest,
    UUID playableStateNamespaceId,
    String publishedReleaseBundleRef,
    PlayableStateScope playableStateScope) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String DIGEST_PATTERN = "[0-9a-f]{64}";
  private static final String SLUG_PATTERN = "[a-z0-9][a-z0-9-]{0,63}";
  private static final int MAX_GAME_INSTANCE_ID_LENGTH = 255;
  private static final int MAX_RELEASE_REF_LENGTH = 1024;

  public PreseededActorAssignmentExpectedTarget {
    requireNonNil(canonicalTenantUuid, "canonicalTenantUuid");
    requireNonNil(realmUuid, "realmUuid");
    requireSlug(worldSlug, "worldSlug");
    requireSlug(realmSlug, "realmSlug");
    requireBoundedOpaqueId(gameInstanceId, "gameInstanceId", MAX_GAME_INSTANCE_ID_LENGTH);
    requirePositive(catalogRevision, "catalogRevision");
    requireNonNil(canonicalVersionUuid, "canonicalVersionUuid");
    if (frozenPolicyDigest == null || !frozenPolicyDigest.matches(DIGEST_PATTERN)) {
      throw new IllegalArgumentException("frozenPolicyDigest must be a lowercase SHA-256 digest");
    }
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    if (publishedReleaseBundleRef == null
        || publishedReleaseBundleRef.isBlank()
        || !publishedReleaseBundleRef.equals(publishedReleaseBundleRef.trim())
        || publishedReleaseBundleRef.length() > MAX_RELEASE_REF_LENGTH
        || publishedReleaseBundleRef.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("publishedReleaseBundleRef is incomplete");
    }
    ActorIdentity.requireScope(playableStateScope);
  }

  /** Compares every caller-echoed target selector with the independent owner-resolved target. */
  public boolean exactlyMatches(PreseededActorAssignmentOwnerEvidence ownerEvidence) {
    return ownerEvidence != null
        && canonicalTenantUuid.equals(ownerEvidence.canonicalTenantUuid())
        && realmUuid.equals(ownerEvidence.realmUuid())
        && worldSlug.equals(ownerEvidence.worldSlug())
        && realmSlug.equals(ownerEvidence.realmSlug())
        && gameInstanceId.equals(ownerEvidence.gameInstanceId())
        && catalogRevision == ownerEvidence.catalogRevision()
        && canonicalVersionUuid.equals(ownerEvidence.canonicalVersionUuid())
        && frozenPolicyDigest.equals(ownerEvidence.frozenPolicyDigest())
        && playableStateNamespaceId.equals(ownerEvidence.playableStateNamespaceId())
        && publishedReleaseBundleRef.equals(ownerEvidence.publishedReleaseBundleRef())
        && playableStateScope == ownerEvidence.playableStateScope();
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
      throw new IllegalArgumentException(fieldName + " must be a canonical realm selector");
    }
  }

  private static void requireBoundedOpaqueId(String value, String fieldName, int maxLength) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.trim())
        || value.length() > maxLength
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(fieldName + " is incomplete");
    }
  }
}
