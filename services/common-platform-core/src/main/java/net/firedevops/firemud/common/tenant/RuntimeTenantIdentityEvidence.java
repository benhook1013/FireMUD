package net.firedevops.firemud.common.tenant;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
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
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final int MAX_SOURCE_KEY_CODE_POINTS = 36;
  private static final int MAX_SOURCE_KEY_UTF16_LENGTH = 72;

  public RuntimeTenantIdentityEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported runtime tenant identity schema version");
    }
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    requireNonNil(requestId, "requestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    if (sourceGameRowId <= 0) {
      throw new IllegalArgumentException("sourceGameRowId must be positive");
    }
    Objects.requireNonNull(sourceGameTenantKey, "sourceGameTenantKey");
    if (sourceGameTenantKey.isBlank()
        || sourceGameTenantKey.length() > MAX_SOURCE_KEY_UTF16_LENGTH
        || sourceGameTenantKey.codePointCount(0, sourceGameTenantKey.length())
            > MAX_SOURCE_KEY_CODE_POINTS
        || !StandardCharsets.UTF_8.newEncoder().canEncode(sourceGameTenantKey)) {
      throw new IllegalArgumentException("sourceGameTenantKey is outside the owner key bounds");
    }
    if (!"NEW_GAME_ROW".equals(provenanceKind)
        && !"RETAINED_GAME_V30".equals(provenanceKind)) {
      throw new IllegalArgumentException("Runtime tenant provenance kind is not recognized");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
