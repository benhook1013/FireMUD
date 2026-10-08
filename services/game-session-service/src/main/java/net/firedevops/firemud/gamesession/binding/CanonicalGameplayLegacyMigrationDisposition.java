package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Typed receipt required before global account-index coverage may acquire its account fence. */
public record CanonicalGameplayLegacyMigrationDisposition(
    UUID cohortId,
    UUID ownerOperationId,
    UUID legacyWriterFence,
    BigInteger sourceSnapshotRevision,
    BigInteger canonicalInventoryRevision,
    BigInteger namespaceIndexReadbackRevision,
    String evidenceDigest) {
  public CanonicalGameplayLegacyMigrationDisposition {
    requireNonNil(cohortId, "cohortId");
    requireNonNil(ownerOperationId, "ownerOperationId");
    requireNonNil(legacyWriterFence, "legacyWriterFence");
    requirePositive(sourceSnapshotRevision, "sourceSnapshotRevision");
    requirePositive(canonicalInventoryRevision, "canonicalInventoryRevision");
    requirePositive(namespaceIndexReadbackRevision, "namespaceIndexReadbackRevision");
    if (evidenceDigest == null || !evidenceDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("evidenceDigest must be a canonical SHA-256 digest");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
