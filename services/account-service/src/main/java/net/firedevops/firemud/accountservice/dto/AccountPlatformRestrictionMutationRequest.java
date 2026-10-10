package net.firedevops.firemud.accountservice.dto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountRestrictionAuthorityEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable source-operation correlation for an Account-owned restriction revision.
 *
 * <p>This value carries no authorization decision. It binds one source request to one category
 * result and its exact expected Account/category revisions; callers must establish the applicable
 * Account policy or moderation authority before using the persistence primitive.
 */
public record AccountPlatformRestrictionMutationRequest(
    UUID requestId,
    UUID accountUuid,
    Category category,
    long expectedAccountGeneration,
    long expectedAccountSourceVersion,
    long expectedCategoryRevision,
    long expectedEnforcementEpoch,
    UUID expectedResultId,
    RestrictionState desiredState,
    SourceKind sourceKind,
    UUID sourceRequestId,
    String sourceDigest) {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String REQUEST_DOMAIN = "account-platform-restriction-request/v1";

  public AccountPlatformRestrictionMutationRequest {
    requireUuid(requestId, "requestId");
    requireUuid(accountUuid, "accountUuid");
    Objects.requireNonNull(category, "restriction category is required");
    Objects.requireNonNull(desiredState, "restriction result is required");
    Objects.requireNonNull(sourceKind, "source kind is required");
    requireUuid(sourceRequestId, "sourceRequestId");
    if (expectedAccountGeneration <= 0L
        || expectedAccountSourceVersion <= 0L
        || expectedCategoryRevision <= 0L
        || expectedEnforcementEpoch <= 0L) {
      throw new IllegalArgumentException(
          "Positive expected Account/category revisions are required");
    }
    requireUuid(expectedResultId, "expectedResultId");
    if (sourceDigest == null || !sourceDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Canonical source request digest is required");
    }
    validateOwnerSource(category, desiredState, sourceKind);
  }

  /** Canonical immutable bytes used for request-id conflict detection and receipt readback. */
  public byte[] canonicalRequestBytes() {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(requestPreimage()));
    } catch (IOException | tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException("Restriction request cannot be canonicalized", exception);
    }
  }

  public String requestDigest() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] domain = REQUEST_DOMAIN.getBytes(StandardCharsets.UTF_8);
      byte[] request = canonicalRequestBytes();
      digest.update((byte) (domain.length >>> 24));
      digest.update((byte) (domain.length >>> 16));
      digest.update((byte) (domain.length >>> 8));
      digest.update((byte) domain.length);
      digest.update(domain);
      digest.update((byte) (request.length >>> 24));
      digest.update((byte) (request.length >>> 16));
      digest.update((byte) (request.length >>> 8));
      digest.update((byte) request.length);
      digest.update(request);
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Closed event preimage in the existing Account security-state source stream. */
  public Map<String, Object> eventPreimage(
      String generation,
      String sourceVersion,
      String sequence,
      UUID resultId,
      long revision,
      long enforcementEpoch) {
    requireUuid(resultId, "resultId");
    if (revision <= 0L || enforcementEpoch <= 0L) {
      throw new IllegalArgumentException("Positive restriction result revisions are required");
    }
    String stream =
        AccountSecurityStateAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + accountUuid;
    return Map.ofEntries(
        Map.entry("schemaVersion", AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_TYPE),
        Map.entry(
            "eventId",
            AccountSecurityStateAuthorityEventV1Codec.RESTRICTION_EVENT_ID_PREFIX + requestId),
        Map.entry("requestId", requestId.toString()),
        Map.entry("accountId", accountUuid.toString()),
        Map.entry("sourceScope", "account/" + accountUuid),
        Map.entry("outboxStreamKey", stream),
        Map.entry("outboxSequence", sequence),
        Map.entry("accountAuthorityGeneration", generation),
        Map.entry("sourceVersion", sourceVersion),
        Map.entry(
            "accountSecurityCutoff",
            Map.of(
                "accountAuthorityGeneration", generation,
                "outboxStreamKey", stream,
                "outboxSequence", sequence)),
        Map.entry("restrictionCategory", category.storageValue()),
        Map.entry("restrictionRevision", Long.toString(revision)),
        Map.entry("restrictionEnforcementEpoch", Long.toString(enforcementEpoch)),
        Map.entry("restrictionState", desiredState.name()),
        Map.entry("restrictionResultId", resultId.toString()),
        Map.entry("restrictionRequestDigest", requestDigest()),
        Map.entry("restrictionSourceKind", sourceKind.name()),
        Map.entry("restrictionSourceRequestId", sourceRequestId.toString()),
        Map.entry("restrictionSourceDigest", sourceDigest));
  }

  public AccountRestrictionAuthorityEvent sealEvent(
      String generation,
      String sourceVersion,
      String sequence,
      UUID resultId,
      long revision,
      long enforcementEpoch) {
    return AccountSecurityStateAuthorityEventV1Codec.sealRestriction(
        eventPreimage(generation, sourceVersion, sequence, resultId, revision, enforcementEpoch));
  }

  private Map<String, Object> requestPreimage() {
    return Map.ofEntries(
        Map.entry("schemaVersion", REQUEST_DOMAIN),
        Map.entry("requestId", requestId.toString()),
        Map.entry("accountId", accountUuid.toString()),
        Map.entry("category", category.storageValue()),
        Map.entry("expectedAccountGeneration", Long.toString(expectedAccountGeneration)),
        Map.entry("expectedAccountSourceVersion", Long.toString(expectedAccountSourceVersion)),
        Map.entry("expectedCategoryRevision", Long.toString(expectedCategoryRevision)),
        Map.entry("expectedEnforcementEpoch", Long.toString(expectedEnforcementEpoch)),
        Map.entry("expectedResultId", expectedResultId.toString()),
        Map.entry("desiredState", desiredState.name()),
        Map.entry("sourceKind", sourceKind.name()),
        Map.entry("sourceRequestId", sourceRequestId.toString()),
        Map.entry("sourceDigest", sourceDigest));
  }

  private static void validateOwnerSource(
      Category category, RestrictionState state, SourceKind sourceKind) {
    if (category == Category.ACCOUNT_SECURITY_LOCK) {
      if (sourceKind == SourceKind.LOGGING_ADMIN_MODERATION
          || (sourceKind == SourceKind.ACCOUNT_SECURITY_POLICY
              && state != RestrictionState.RESTRICTED)
          || (sourceKind == SourceKind.ACCOUNT_SECURITY_RECOVERY
              && state != RestrictionState.NONRESTRICTED)) {
        throw new IllegalArgumentException(
            "Account security-lock changes require the matching Account policy or recovery source");
      }
    } else if (sourceKind != SourceKind.LOGGING_ADMIN_MODERATION) {
      throw new IllegalArgumentException(
          "Platform access-ban changes require the Logging & Admin moderation source");
    }
  }

  private static void requireUuid(UUID value, String field) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical non-nil " + field + " is required");
    }
  }

  public enum RestrictionState {
    RESTRICTED,
    NONRESTRICTED
  }

  public enum SourceKind {
    ACCOUNT_SECURITY_POLICY,
    ACCOUNT_SECURITY_RECOVERY,
    LOGGING_ADMIN_MODERATION
  }
}
