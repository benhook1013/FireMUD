package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Exact selected actor lookup precondition for a non-admitting PRESEEDED assignment read. */
public record CanonicalGameplayRosterSelectedAssignmentReadRequest(
    UUID requestUuid,
    UUID canonicalAccountUuid,
    UUID selectedCharacterUuid,
    CanonicalGameplayRosterTarget expectedTarget) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CanonicalGameplayRosterSelectedAssignmentReadRequest {
    requireNonNil(requestUuid, "requestUuid");
    requireNonNil(canonicalAccountUuid, "canonicalAccountUuid");
    requireNonNil(selectedCharacterUuid, "selectedCharacterUuid");
    Objects.requireNonNull(expectedTarget, "expectedTarget");
  }

  private static void requireNonNil(UUID value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be non-nil");
    }
  }
}
