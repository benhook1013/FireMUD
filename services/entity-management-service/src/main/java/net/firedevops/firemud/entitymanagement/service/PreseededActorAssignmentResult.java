package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Persisted outcome for a digest-bound pre-seeded assignment attempt. {@code ASSIGNED} means only
 * that Entity persisted the actor; it is not a PLAY grant or runtime-admission result.
 */
public record PreseededActorAssignmentResult(
    UUID assignmentUuid, Outcome outcome, UUID characterUuid, String intentDigest) {
  public enum Outcome {
    ASSIGNED,
    IDEMPOTENCY_CONFLICT
  }

  public PreseededActorAssignmentResult {
    Objects.requireNonNull(assignmentUuid, "assignmentUuid");
    Objects.requireNonNull(outcome, "outcome");
    if (intentDigest == null || !intentDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("intentDigest must be a lowercase SHA-256 digest");
    }
    if ((outcome == Outcome.ASSIGNED) != (characterUuid != null)) {
      throw new IllegalArgumentException("Only assigned outcomes carry a character UUID");
    }
  }
}
