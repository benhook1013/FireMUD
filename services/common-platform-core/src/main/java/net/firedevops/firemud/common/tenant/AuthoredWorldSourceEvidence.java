package net.firedevops.firemud.common.tenant;

import java.util.Objects;
import java.util.UUID;

/** Closed immutable Game Design evidence for one registered authored-world selector source. */
public record AuthoredWorldSourceEvidence(
    int schemaVersion,
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
    String provenanceKind,
    String evidenceDigest) {
  private static final int SCHEMA_VERSION = 1;

  public AuthoredWorldSourceEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported authored-world source schema version");
    }
    Objects.requireNonNull(requestDigest, "requestDigest");
    if (!GameTenantCreationDigest.isDigest(requestDigest)) {
      throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256 digest");
    }
    String expectedRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            targetNamespace,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    if (!expectedRequestDigest.equals(requestDigest)) {
      throw new IllegalArgumentException("Request digest does not match the registration tuple");
    }
    Objects.requireNonNull(evidenceDigest, "evidenceDigest");
    if (!GameTenantCreationDigest.isDigest(evidenceDigest)) {
      throw new IllegalArgumentException("evidenceDigest must be a lowercase SHA-256 digest");
    }
    String expectedEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            targetNamespace,
            registrationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind);
    if (!expectedEvidenceDigest.equals(evidenceDigest)) {
      throw new IllegalArgumentException("Evidence digest does not match the immutable tuple");
    }
  }
}
