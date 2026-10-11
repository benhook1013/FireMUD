package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;

/** The exact canonical launch association and its owner-local runtime row locked together. */
public record CanonicalInitialAdmissionLaunchTarget(
    CanonicalGameInstanceLaunchAssociation association,
    UUID realmId,
    long gameInstanceId,
    long runtimeVersionId) {
  public CanonicalInitialAdmissionLaunchTarget {
    Objects.requireNonNull(association, "association");
    Objects.requireNonNull(realmId, "realmId");
    if (gameInstanceId <= 0L || runtimeVersionId <= 0L) {
      throw new IllegalArgumentException("Canonical launch target identifiers must be positive");
    }
    if (association.launchBindingEvidence().descriptor().versionId() != runtimeVersionId) {
      throw new IllegalArgumentException(
          "Current runtime version does not match the canonical launch descriptor");
    }
  }

  /** Canonical content version from retained publication evidence, never from the numeric row. */
  public UUID canonicalVersionId() {
    return association.launchBindingEvidence().releaseAttestation().canonicalVersionId();
  }
}
