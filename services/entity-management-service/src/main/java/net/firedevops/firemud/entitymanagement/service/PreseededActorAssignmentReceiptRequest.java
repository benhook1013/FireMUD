package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable operation/actor selector for Entity's assignment receipt owner read. */
public record PreseededActorAssignmentReceiptRequest(
    UUID assignmentUuid,
    UUID canonicalAccountUuid,
    UUID characterUuid,
    String intentDigest,
    PreseededActorAssignmentExpectedTarget expectedTarget) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public PreseededActorAssignmentReceiptRequest {
    requireNonNil(assignmentUuid, "assignmentUuid");
    requireNonNil(canonicalAccountUuid, "canonicalAccountUuid");
    requireNonNil(characterUuid, "characterUuid");
    if (intentDigest == null || !intentDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("intentDigest must be lowercase SHA-256 hex");
    }
    Objects.requireNonNull(expectedTarget, "expectedTarget");
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be non-nil");
    }
  }
}
