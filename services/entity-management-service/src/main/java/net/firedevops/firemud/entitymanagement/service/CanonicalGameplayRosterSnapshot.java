package net.firedevops.firemud.entitymanagement.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable point-in-time roster snapshot; its identity grants no admission or PLAY authority. */
public record CanonicalGameplayRosterSnapshot(
    UUID canonicalAccountUuid,
    UUID snapshotUuid,
    String snapshotDigest,
    CanonicalGameplayRosterTarget target,
    List<CanonicalGameplayRosterActor> actors) {
  public CanonicalGameplayRosterSnapshot {
    Objects.requireNonNull(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(snapshotUuid, "snapshotUuid");
    Objects.requireNonNull(target, "target");
    actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
    if (snapshotDigest == null || !snapshotDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("snapshotDigest must be a lowercase SHA-256 digest");
    }
    if (actors.size() > CanonicalGameplayRosterSnapshotDigest.MAX_ROSTER_SIZE) {
      throw new IllegalArgumentException("Roster snapshot exceeds its bounded size");
    }
  }
}
