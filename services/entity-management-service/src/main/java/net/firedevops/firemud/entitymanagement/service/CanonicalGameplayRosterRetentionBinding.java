package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Exact local snapshot identity. Construction supplies no authenticated cross-owner authority. */
public record CanonicalGameplayRosterRetentionBinding(
    UUID canonicalAccountUuid,
    CanonicalGameplayRosterSnapshotReference snapshot,
    CanonicalGameplayRosterTarget target) {
  public CanonicalGameplayRosterRetentionBinding {
    Objects.requireNonNull(canonicalAccountUuid, "canonicalAccountUuid");
    if (canonicalAccountUuid.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("canonicalAccountUuid must be non-nil");
    }
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(target, "target");
  }
}
