package net.firedevops.firemud.gamesession.dto;

import java.util.UUID;

/** Retained original LOGIN coordinates, never ingress, authentication or installation authority. */
public record InitialGameplayLoginAttemptCoordinates(
    UUID requestId, UUID callerContextId, long expectedAttemptFence) {
  public InitialGameplayLoginAttemptCoordinates {
    requireV4(requestId);
    requireV4(callerContextId);
    if (expectedAttemptFence <= 0) {
      throw new IllegalArgumentException("Positive original LOGIN attempt fence required");
    }
  }

  private static void requireV4(UUID id) {
    if (id == null || id.version() != 4 || id.variant() != 2) {
      throw new IllegalArgumentException("Original LOGIN coordinates require UUIDv4 identities");
    }
  }
}
