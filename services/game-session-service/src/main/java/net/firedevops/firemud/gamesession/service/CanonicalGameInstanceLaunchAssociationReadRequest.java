package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;

/** Exact independent World read selector for one committed Game Session launch association. */
public record CanonicalGameInstanceLaunchAssociationReadRequest(
    UUID readRequestId,
    String targetNamespace,
    UUID canonicalTenantId,
    String worldSlug,
    UUID gameInstanceUuid,
    String controlPlaneRequestId,
    String launchDescriptorId,
    String expectedDescriptorRequestDigest,
    String expectedDescriptorResultDigest,
    String expectedReleaseAttestationEvidenceDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public CanonicalGameInstanceLaunchAssociationReadRequest {
    requireNonNil(readRequestId, "readRequestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(gameInstanceUuid, "gameInstanceUuid");
    AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
    requireText(controlPlaneRequestId, "controlPlaneRequestId");
    requireText(launchDescriptorId, "launchDescriptorId");
    requireDigest(expectedDescriptorRequestDigest, "expectedDescriptorRequestDigest");
    requireDigest(expectedDescriptorResultDigest, "expectedDescriptorResultDigest");
    requireDigest(
        expectedReleaseAttestationEvidenceDigest, "expectedReleaseAttestationEvidenceDigest");
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " must be non-blank and bounded");
    }
  }

  private static void requireDigest(String value, String name) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
  }
}
