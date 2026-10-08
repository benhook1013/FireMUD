package net.firedevops.firemud.entitymanagement.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentity;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/**
 * Point-in-time Account eligibility observation and frozen published target returned by the
 * owner-read adapter. {@code CURRENT_AT_REVALIDATION} means Account evidence was revalidated at
 * observation time; it is not a held authorization or PLAY grant. Numeric Game Design and Game
 * Session row identifiers are intentionally not part of this value.
 */
public record PreseededActorAssignmentOwnerEvidence(
    UUID assignmentUuid,
    UUID canonicalAccountUuid,
    Eligibility eligibility,
    Instant eligibilityEvaluatedAt,
    long membershipAuthorityGeneration,
    String eligibilityEvidenceDigest,
    UUID canonicalTenantUuid,
    UUID realmUuid,
    String worldSlug,
    String realmSlug,
    String gameInstanceId,
    long catalogRevision,
    UUID canonicalVersionUuid,
    String frozenPolicyDigest,
    UUID playableStateNamespaceId,
    PlayableStateScope playableStateScope,
    PublishedEntryPolicy entryPolicy,
    AccountPurpose accountPurpose,
    AccountCurrentness accountCurrentness,
    String accountAuthoritySnapshotDigest,
    String publishedOwnerProofDigest,
    String publishedReleaseBundleRef) {
  public enum Eligibility {
    ELIGIBLE,
    INELIGIBLE
  }

  public enum PublishedEntryPolicy {
    PRESEEDED_ONLY
  }

  public enum AccountPurpose {
    PRESEEDED_ACTOR_STAGING
  }

  public enum AccountCurrentness {
    OBSERVED,
    /** Point-in-time Account revalidation result; not a held authorization or PLAY grant. */
    CURRENT_AT_REVALIDATION,
    CHANGED_SINCE_OBSERVATION
  }

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String DIGEST_PATTERN = "[0-9a-f]{64}";
  private static final String SLUG_PATTERN = "[a-z0-9][a-z0-9-]{0,63}";
  private static final int MAX_GAME_INSTANCE_ID_LENGTH = 255;
  private static final int MAX_RELEASE_REF_LENGTH = 1024;

  public PreseededActorAssignmentOwnerEvidence {
    requireNonNil(assignmentUuid, "assignmentUuid");
    requireNonNil(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(eligibility, "eligibility");
    Objects.requireNonNull(eligibilityEvaluatedAt, "eligibilityEvaluatedAt");
    requirePositive(membershipAuthorityGeneration, "membershipAuthorityGeneration");
    requireDigest(eligibilityEvidenceDigest, "eligibilityEvidenceDigest");
    requireNonNil(canonicalTenantUuid, "canonicalTenantUuid");
    requireNonNil(realmUuid, "realmUuid");
    requireSlug(worldSlug, "worldSlug");
    requireSlug(realmSlug, "realmSlug");
    requireBoundedOpaqueId(gameInstanceId, "gameInstanceId", MAX_GAME_INSTANCE_ID_LENGTH);
    requirePositive(catalogRevision, "catalogRevision");
    requireNonNil(canonicalVersionUuid, "canonicalVersionUuid");
    requireDigest(frozenPolicyDigest, "frozenPolicyDigest");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    ActorIdentity.requireScope(playableStateScope);
    Objects.requireNonNull(entryPolicy, "entryPolicy");
    Objects.requireNonNull(accountPurpose, "accountPurpose");
    Objects.requireNonNull(accountCurrentness, "accountCurrentness");
    requireDigest(accountAuthoritySnapshotDigest, "accountAuthoritySnapshotDigest");
    requireDigest(publishedOwnerProofDigest, "publishedOwnerProofDigest");
    if (publishedReleaseBundleRef == null
        || publishedReleaseBundleRef.isBlank()
        || !publishedReleaseBundleRef.equals(publishedReleaseBundleRef.trim())
        || publishedReleaseBundleRef.length() > MAX_RELEASE_REF_LENGTH
        || publishedReleaseBundleRef.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("publishedReleaseBundleRef is incomplete");
    }
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

  private static void requireDigest(String value, String fieldName) {
    if (value == null || !value.matches(DIGEST_PATTERN)) {
      throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 digest");
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
