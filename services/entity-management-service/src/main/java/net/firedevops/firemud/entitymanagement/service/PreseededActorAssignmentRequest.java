package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Stable operation identity and the exact minimal core payload requested for assignment. */
public record PreseededActorAssignmentRequest(
    UUID assignmentUuid,
    UUID canonicalAccountUuid,
    PreseededActorCorePayload corePayload,
    PreseededActorAssignmentExpectedTarget expectedTarget) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public PreseededActorAssignmentRequest {
    requireNonNil(assignmentUuid, "assignmentUuid");
    requireNonNil(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(corePayload, "corePayload");
    Objects.requireNonNull(expectedTarget, "expectedTarget");
  }

  /**
   * Stable operation digest computed from caller intent, never from transient owner observations.
   */
  public String mutationIntentDigest() {
    return PreseededActorAssignmentGrantIntentDigest.compute(this);
  }

  private static void requireNonNil(UUID value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be non-nil");
    }
  }
}
