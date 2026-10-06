package net.firedevops.firemud.gamedesign.repository;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;

/** Immutable, non-authoritative Game Design preparation for one exact fresh tenant request. */
public record FreshTenantCreationReservation(
    int schemaVersion,
    String targetNamespace,
    UUID creationRequestId,
    String requestDigest,
    UUID operationId,
    UUID canonicalTenantId,
    String sourceGameTenantKey,
    String name,
    String description) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  public FreshTenantCreationReservation {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported fresh tenant reservation schema version");
    }
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    if (!DNS_LABEL.matcher(targetNamespace).matches()) {
      throw new IllegalArgumentException("targetNamespace must be one lowercase DNS label");
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
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
