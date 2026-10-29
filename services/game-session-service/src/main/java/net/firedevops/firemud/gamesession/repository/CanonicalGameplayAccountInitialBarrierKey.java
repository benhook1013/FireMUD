package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;

/** Key for the original-attempt INITIAL OPEN operation on one tenant gameplay slot. */
final class CanonicalGameplayAccountInitialBarrierKey {
  private CanonicalGameplayAccountInitialBarrierKey() {}

  static String forTarget(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      UUID initializerId,
      UUID originalRequestId) {
    Objects.requireNonNull(assignment, "persisted initial target assignment");
    return CanonicalGameplaySessionRedisKeys.initialAccountAdmissionBarrier(
        assignment, initializerId, originalRequestId);
  }
}
