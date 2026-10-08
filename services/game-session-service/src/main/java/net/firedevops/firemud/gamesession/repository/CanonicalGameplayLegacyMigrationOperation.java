package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;

/** Package-private owner state loaded only from Game Session's V33 operation row. */
record CanonicalGameplayLegacyMigrationOperation(
    UUID operationId,
    UUID cohortId,
    UUID legacyWriterFence,
    CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity,
    State state,
    BigInteger sourceSnapshotRevision,
    BigInteger canonicalSnapshotRevision,
    BigInteger publicationInventoryRevision,
    String sourceSnapshotDigest,
    String canonicalSnapshotDigest,
    String rebuildDigest,
    BigInteger namespaceIndexReadbackRevision,
    String namespaceIndexReadbackDigest,
    String evidenceDigest,
    String blockedReason) {
  CanonicalGameplayLegacyMigrationOperation {
    requireNonNil(operationId, "operationId");
    requireNonNil(cohortId, "cohortId");
    requireNonNil(legacyWriterFence, "legacyWriterFence");
    Objects.requireNonNull(storageIdentity, "storageIdentity");
    Objects.requireNonNull(state, "state");
    if (state == State.FENCED || (state == State.BLOCKED && sourceSnapshotRevision == null)) {
      if (sourceSnapshotRevision != null
          || canonicalSnapshotRevision != null
          || publicationInventoryRevision != null
          || sourceSnapshotDigest != null
          || canonicalSnapshotDigest != null
          || rebuildDigest != null
          || namespaceIndexReadbackRevision != null
          || namespaceIndexReadbackDigest != null
          || evidenceDigest != null
          || (state == State.FENCED && blockedReason != null)
          || (state == State.BLOCKED && (blockedReason == null || blockedReason.isBlank()))) {
        throw new IllegalArgumentException(
            "Pre-snapshot operation cannot carry later-stage evidence");
      }
    } else {
      requirePositive(sourceSnapshotRevision, "sourceSnapshotRevision");
      requireNonNegative(canonicalSnapshotRevision, "canonicalSnapshotRevision");
      requireDigest(sourceSnapshotDigest, "sourceSnapshotDigest");
      requireDigest(canonicalSnapshotDigest, "canonicalSnapshotDigest");
      if (state == State.BLOCKED && (blockedReason == null || blockedReason.isBlank())) {
        throw new IllegalArgumentException("BLOCKED operation must retain its reason");
      }
      if (state == State.REBUILT || state == State.READBACK_VERIFIED || state == State.VERIFIED) {
        requireDigest(rebuildDigest, "rebuildDigest");
      }
      if (state == State.READBACK_VERIFIED || state == State.VERIFIED) {
        requirePositive(namespaceIndexReadbackRevision, "namespaceIndexReadbackRevision");
        requireDigest(namespaceIndexReadbackDigest, "namespaceIndexReadbackDigest");
      }
      if (state == State.VERIFIED) {
        requirePositive(publicationInventoryRevision, "publicationInventoryRevision");
        requireDigest(evidenceDigest, "evidenceDigest");
      }
    }
  }

  enum State {
    FENCED,
    SNAPSHOTTED,
    REBUILT,
    READBACK_VERIFIED,
    BLOCKED,
    VERIFIED
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

  private static void requireNonNegative(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  private static void requireDigest(String value, String name) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
  }
}
