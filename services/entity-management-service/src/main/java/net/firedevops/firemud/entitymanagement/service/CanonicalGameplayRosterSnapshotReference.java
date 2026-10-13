package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable identity and digest of one previously returned roster snapshot. */
public record CanonicalGameplayRosterSnapshotReference(UUID snapshotUuid, String snapshotDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CanonicalGameplayRosterSnapshotReference {
    Objects.requireNonNull(snapshotUuid, "snapshotUuid");
    if (NIL_UUID.equals(snapshotUuid)) {
      throw new IllegalArgumentException("snapshotUuid must be non-nil");
    }
    if (snapshotDigest == null || !snapshotDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("snapshotDigest must be lowercase SHA-256 hex");
    }
  }
}
