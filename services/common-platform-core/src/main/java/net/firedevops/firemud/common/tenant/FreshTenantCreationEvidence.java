package net.firedevops.firemud.common.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Immutable evidence that Game Design created and read back one exact fresh tenant operation. */
public record FreshTenantCreationEvidence(
    int schemaVersion,
    String targetNamespace,
    UUID creationRequestId,
    UUID operationId,
    String requestDigest,
    UUID canonicalTenantId,
    long sourceGameRowId,
    String sourceGameTenantKey,
    String provenanceKind,
    String evidenceDigest) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public FreshTenantCreationEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported fresh tenant evidence schema version");
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
    if (sourceGameRowId <= 0) {
      throw new IllegalArgumentException("sourceGameRowId must be positive");
    }
    Objects.requireNonNull(sourceGameTenantKey, "sourceGameTenantKey");
    if (sourceGameTenantKey.isBlank()
        || sourceGameTenantKey.length() > 72
        || sourceGameTenantKey.codePointCount(0, sourceGameTenantKey.length()) > 36) {
      throw new IllegalArgumentException("sourceGameTenantKey exceeds the owner key bounds");
    }
    GameTenantCreationDigest.utf8ByteLength(targetNamespace);
    GameTenantCreationDigest.utf8ByteLength(sourceGameTenantKey);
    if (!"NEW_GAME_ROW".equals(provenanceKind)) {
      throw new IllegalArgumentException("Fresh tenant provenance must be NEW_GAME_ROW");
    }
    Objects.requireNonNull(evidenceDigest, "evidenceDigest");
    String expectedEvidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            targetNamespace,
            creationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind);
    if (!expectedEvidenceDigest.equals(evidenceDigest)) {
      throw new IllegalArgumentException("Evidence digest does not match the immutable tuple");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
