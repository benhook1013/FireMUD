package net.firedevops.firemud.common.tenant;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Canonical request and receipt digests for Game Design authored-world source evidence. */
public final class AuthoredWorldSourceDigest {
  private static final String REQUEST_DOMAIN = "game-design-authored-world-source-request/v1";
  private static final String RECEIPT_DOMAIN = "game-design-authored-world-source-receipt/v1";
  private static final Pattern CANONICAL_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AuthoredWorldSourceDigest() {}

  public static String requestDigest(
      String targetNamespace,
      UUID registrationRequestId,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName) {
    validateRequestTuple(
        targetNamespace,
        registrationRequestId,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        worldDisplayName);
    return GameTenantCreationDigest.digest(
        REQUEST_DOMAIN,
        targetNamespace,
        registrationRequestId.toString(),
        canonicalTenantId.toString(),
        tenantSlug,
        worldSlug,
        worldDisplayName);
  }

  public static String evidenceDigest(
      String targetNamespace,
      UUID registrationRequestId,
      UUID operationId,
      String requestDigest,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    validateRequestTuple(
        targetNamespace,
        registrationRequestId,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        worldDisplayName);
    requireNonNil(operationId, "operationId");
    if (!GameTenantCreationDigest.isDigest(requestDigest)) {
      throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256 digest");
    }
    validateSourceGameIdentity(sourceGameRowId, sourceGameTenantKey, provenanceKind);
    return GameTenantCreationDigest.digest(
        RECEIPT_DOMAIN,
        targetNamespace,
        registrationRequestId.toString(),
        operationId.toString(),
        requestDigest,
        canonicalTenantId.toString(),
        tenantSlug,
        worldSlug,
        worldDisplayName,
        Long.toString(sourceGameRowId),
        sourceGameTenantKey,
        provenanceKind);
  }

  public static void validateReadSelector(
      String targetNamespace, UUID canonicalTenantId, String worldSlug) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    GameTenantCreationDigest.utf8ByteLength(targetNamespace);
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    validateSlug(worldSlug, "worldSlug");
  }

  private static void validateRequestTuple(
      String targetNamespace,
      UUID registrationRequestId,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    GameTenantCreationDigest.utf8ByteLength(targetNamespace);
    requireNonNil(registrationRequestId, "registrationRequestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    validateSlug(tenantSlug, "tenantSlug");
    validateSlug(worldSlug, "worldSlug");
    validateDisplayName(worldDisplayName);
  }

  private static void validateSlug(String value, String label) {
    Objects.requireNonNull(value, label);
    int byteLength = GameTenantCreationDigest.utf8ByteLength(value);
    if (byteLength == 0 || byteLength > 120 || !CANONICAL_SLUG.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a canonical routing selector");
    }
  }

  private static void validateDisplayName(String value) {
    Objects.requireNonNull(value, "worldDisplayName");
    if (value.isBlank() || value.length() > 200 || value.codePointCount(0, value.length()) > 100) {
      throw new IllegalArgumentException(
          "worldDisplayName must be nonblank and contain at most 100 Unicode code points");
    }
    GameTenantCreationDigest.utf8ByteLength(value);
  }

  static void validateSourceGameIdentity(
      long sourceGameRowId, String sourceGameTenantKey, String provenanceKind) {
    if (sourceGameRowId <= 0) {
      throw new IllegalArgumentException("sourceGameRowId must be positive");
    }
    validateSourceGameTenantKey(sourceGameTenantKey);
    validateProvenanceKind(provenanceKind);
  }

  private static void validateSourceGameTenantKey(String value) {
    Objects.requireNonNull(value, "sourceGameTenantKey");
    if (value.isBlank() || value.length() > 72 || value.codePointCount(0, value.length()) > 36) {
      throw new IllegalArgumentException("sourceGameTenantKey is outside the owner key bounds");
    }
    GameTenantCreationDigest.utf8ByteLength(value);
  }

  private static void validateProvenanceKind(String value) {
    if (!"NEW_GAME_ROW".equals(value) && !"RETAINED_GAME_V29".equals(value)) {
      throw new IllegalArgumentException("Authored-world source provenance kind is not recognized");
    }
  }

  static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
