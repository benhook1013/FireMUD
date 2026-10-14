package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Minimal historical assignment reference returned for one exact selected actor and target. */
public record CanonicalGameplayRosterSelectedAssignmentReference(
    UUID requestUuid,
    UUID canonicalAccountUuid,
    UUID selectedCharacterUuid,
    CanonicalGameplayRosterTarget target,
    CanonicalGameplayRosterSnapshotReference snapshot,
    UUID assignmentUuid,
    String intentDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CanonicalGameplayRosterSelectedAssignmentReference {
    requireNonNil(requestUuid, "requestUuid");
    requireNonNil(canonicalAccountUuid, "canonicalAccountUuid");
    requireNonNil(selectedCharacterUuid, "selectedCharacterUuid");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(snapshot, "snapshot");
    requireNonNil(assignmentUuid, "assignmentUuid");
    if (intentDigest == null || !intentDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("intentDigest must be lowercase SHA-256 hex");
    }
  }

  private static void requireNonNil(UUID value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be non-nil");
    }
  }
}
