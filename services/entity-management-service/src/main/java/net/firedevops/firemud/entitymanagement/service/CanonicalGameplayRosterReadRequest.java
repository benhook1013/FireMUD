package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import java.util.UUID;

/** Exact-account canonical roster lookup with an independently checked target precondition. */
public record CanonicalGameplayRosterReadRequest(
    UUID requestUuid, UUID canonicalAccountUuid, CanonicalGameplayRosterTarget expectedTarget) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CanonicalGameplayRosterReadRequest {
    requireNonNil(requestUuid, "requestUuid");
    requireNonNil(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(expectedTarget, "expectedTarget");
  }

  private static void requireNonNil(UUID value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be non-nil");
    }
  }
}
