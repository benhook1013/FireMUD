package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;

/** Retained Entity projection and original immutable assignment provenance; never admission. */
public record PreseededActorRuntimeReference(
    RuntimeEntityId runtimeEntityId,
    long projectionGeneration,
    long projectionFence,
    PreseededActorAssignmentReceipt assignmentReceipt) {
  public PreseededActorRuntimeReference {
    Objects.requireNonNull(runtimeEntityId, "runtimeEntityId");
    Objects.requireNonNull(assignmentReceipt, "assignmentReceipt");
    if (projectionGeneration <= 0
        || projectionFence <= 0
        || !"ASSIGNED".equals(assignmentReceipt.status())) {
      throw new IllegalArgumentException("Committed runtime projection provenance is required");
    }
  }
}
