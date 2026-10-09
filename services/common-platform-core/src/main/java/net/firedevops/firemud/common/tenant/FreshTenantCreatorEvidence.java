package net.firedevops.firemud.common.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable creator-qualification evidence bound to one exact fresh tenant source operation.
 *
 * <p>This record validates a closed digest-bound tuple. The Account UUID and authorization
 * operation fields are data, not authenticated proof by themselves; the Account-owned producer must
 * supply and protect their authority.
 */
public record FreshTenantCreatorEvidence(
    int schemaVersion,
    FreshTenantCreationEvidence creationEvidence,
    UUID initiatingAccountId,
    UUID accountAuthorizationOperationId,
    String accountAuthorizationDigest,
    String evidenceDigest) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public FreshTenantCreatorEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported fresh tenant creator evidence version");
    }
    Objects.requireNonNull(creationEvidence, "creationEvidence");
    requireNonNil(initiatingAccountId, "initiatingAccountId");
    requireNonNil(accountAuthorizationOperationId, "accountAuthorizationOperationId");
    if (!GameTenantCreationDigest.isDigest(accountAuthorizationDigest)) {
      throw new IllegalArgumentException(
          "accountAuthorizationDigest must be a lowercase SHA-256 digest");
    }
    Objects.requireNonNull(evidenceDigest, "evidenceDigest");
    String expectedDigest =
        FreshTenantCreatorDigest.evidenceDigest(
            schemaVersion,
            creationEvidence,
            initiatingAccountId,
            accountAuthorizationOperationId,
            accountAuthorizationDigest);
    if (!expectedDigest.equals(evidenceDigest)) {
      throw new IllegalArgumentException(
          "Creator evidence digest does not match the immutable qualification tuple");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
