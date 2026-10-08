package net.firedevops.firemud.common.tenant;

import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Immutable Game Design owner evidence for one persisted runtime tenant identity. */
public record RuntimeTenantIdentityEvidence(
    int schemaVersion,
    String targetNamespace,
    UUID requestId,
    UUID canonicalTenantId,
    long sourceGameRowId,
    String sourceGameTenantKey,
    String provenanceKind) {
  private static final int SCHEMA_VERSION = 1;

  public RuntimeTenantIdentityEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported runtime tenant identity schema version");
    }
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    AuthoredWorldSourceDigest.requireNonNil(requestId, "requestId");
    AuthoredWorldSourceDigest.requireNonNil(canonicalTenantId, "canonicalTenantId");
    AuthoredWorldSourceDigest.validateSourceGameIdentity(
        sourceGameRowId, sourceGameTenantKey, provenanceKind);
  }
}
