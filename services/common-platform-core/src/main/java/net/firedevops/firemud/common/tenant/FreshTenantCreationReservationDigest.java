package net.firedevops.firemud.common.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Immutable digest for the non-authoritative Game Design tenant reservation identity. */
public final class FreshTenantCreationReservationDigest {
  private static final int SCHEMA_VERSION = 1;
  private static final String RESERVATION_DOMAIN = "game-design-tenant-creation-reservation/v1";

  private FreshTenantCreationReservationDigest() {}

  public static String evidenceDigest(
      int schemaVersion,
      String targetNamespace,
      UUID creationRequestId,
      String requestDigest,
      UUID operationId,
      UUID canonicalTenantId,
      String sourceGameTenantKey,
      String name,
      String description) {
    validate(
        schemaVersion,
        targetNamespace,
        creationRequestId,
        requestDigest,
        operationId,
        canonicalTenantId,
        sourceGameTenantKey,
        name,
        description);
    return GameTenantCreationDigest.digest(
        RESERVATION_DOMAIN,
        Integer.toString(schemaVersion),
        targetNamespace,
        creationRequestId.toString(),
        requestDigest,
        operationId.toString(),
        canonicalTenantId.toString(),
        sourceGameTenantKey,
        name,
        description == null ? "absent" : "present",
        description == null ? "" : description);
  }

  static void validate(
      int schemaVersion,
      String targetNamespace,
      UUID creationRequestId,
      String requestDigest,
      UUID operationId,
      UUID canonicalTenantId,
      String sourceGameTenantKey,
      String name,
      String description) {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported reservation schema version");
    }
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    requireNonNil(creationRequestId, "creationRequestId");
    requireNonNil(operationId, "operationId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    if (!GameTenantCreationDigest.isDigest(requestDigest)) {
      throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256 digest");
    }
    Objects.requireNonNull(sourceGameTenantKey, "sourceGameTenantKey");
    Objects.requireNonNull(name, "name");
    if (sourceGameTenantKey.isBlank()
        || sourceGameTenantKey.length() > 72
        || sourceGameTenantKey.codePointCount(0, sourceGameTenantKey.length()) > 36) {
      throw new IllegalArgumentException("sourceGameTenantKey exceeds the owner key bounds");
    }
    if (name.length() > 200 || name.codePointCount(0, name.length()) > 100) {
      throw new IllegalArgumentException("name exceeds the owner name bounds");
    }
    if (description != null
        && (description.length() > 510
            || description.codePointCount(0, description.length()) > 255
            || GameTenantCreationDigest.utf8ByteLength(description) > 1020)) {
      throw new IllegalArgumentException("description exceeds the owner description bounds");
    }
    String expectedRequestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace, creationRequestId, sourceGameTenantKey, name, description);
    if (!expectedRequestDigest.equals(requestDigest)) {
      throw new IllegalArgumentException(
          "requestDigest does not match the exact reservation input");
    }
    GameTenantCreationDigest.utf8ByteLength(sourceGameTenantKey);
    GameTenantCreationDigest.utf8ByteLength(name);
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
