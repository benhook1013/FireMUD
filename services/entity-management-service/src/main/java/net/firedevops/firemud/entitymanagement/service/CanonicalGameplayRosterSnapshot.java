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

  public net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse toWire() {
    var result =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(canonicalAccountUuid.toString())
            .setSnapshotUuid(snapshotUuid.toString())
            .setSnapshotDigest(snapshotDigest)
            .setTarget(target.toWire());
    for (var actor : actors) {
      result.addActors(
          net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
              .setCharacterUuid(actor.characterUuid().toString())
              .setDisplayName(actor.displayName())
              .setActorKind(
                  net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                      .CANONICAL_ROSTER_ACTOR_KIND_PLAYER));
    }
    return result.build();
  }

  public static CanonicalGameplayRosterSnapshot fromWire(
      net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse receipt,
      CanonicalGameplayRosterTarget target) {
    if (!target.toWire().equals(receipt.getTarget())) {
      throw new IllegalArgumentException("Retained roster target differs from original selection");
    }
    var actors =
        receipt.getActorsList().stream()
            .map(
                actor -> {
                  if (actor.getActorKind()
                      != net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                          .CANONICAL_ROSTER_ACTOR_KIND_PLAYER) {
                    throw new IllegalArgumentException("Retained roster actor kind is invalid");
                  }
                  return new CanonicalGameplayRosterActor(
                      UUID.fromString(actor.getCharacterUuid()), actor.getDisplayName());
                })
            .toList();
    var snapshot =
        new CanonicalGameplayRosterSnapshot(
            UUID.fromString(receipt.getCanonicalAccountUuid()),
            UUID.fromString(receipt.getSnapshotUuid()),
            receipt.getSnapshotDigest(),
            target,
            actors);
    if (!snapshot
        .snapshotDigest()
        .equals(
            CanonicalGameplayRosterSnapshotDigest.compute(
                snapshot.canonicalAccountUuid(), target, actors))) {
      throw new IllegalArgumentException("Retained roster digest is invalid");
    }
    return snapshot;
  }
}
