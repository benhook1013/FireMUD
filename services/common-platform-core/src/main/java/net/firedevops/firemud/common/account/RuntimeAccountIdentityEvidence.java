package net.firedevops.firemud.common.account;

import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Read-only Account owner evidence for one persisted canonical account identity.
 *
 * <p>The retained numeric row values are private provenance evidence, not gameplay identity. This
 * record does not establish membership, actor ownership, namespace ownership, or admission.
 */
public record RuntimeAccountIdentityEvidence(
    int schemaVersion,
    String targetNamespace,
    UUID requestId,
    UUID canonicalAccountId,
    long sourceAccountRowId,
    String accountUuidProvenance,
    long sourceNumericRowId) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public RuntimeAccountIdentityEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported runtime account identity schema version");
    }
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    requireNonNil(requestId, "requestId");
    requireNonNil(canonicalAccountId, "canonicalAccountId");
    if (sourceAccountRowId <= 0L || sourceNumericRowId <= 0L) {
      throw new IllegalArgumentException("Account source row identities must be positive");
    }
    if (sourceAccountRowId != sourceNumericRowId) {
      throw new IllegalArgumentException("Account source row identities must match exactly");
    }
    if (!isRecognizedProvenance(accountUuidProvenance)) {
      throw new IllegalArgumentException("Account UUID provenance is not recognized");
    }
  }

  private static boolean isRecognizedProvenance(String provenance) {
    return "ACCOUNT_V29_MIGRATION".equals(provenance)
        || "ACCOUNT_REPOSITORY_INSERT".equals(provenance)
        || "ACCOUNT_DATABASE_INSERT".equals(provenance);
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a nonnil UUID");
    }
  }
}
