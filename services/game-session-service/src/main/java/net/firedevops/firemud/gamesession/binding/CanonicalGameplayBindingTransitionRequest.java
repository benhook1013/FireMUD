package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Caller-owned transition identity and exact prior-binding compare tuple. */
public record CanonicalGameplayBindingTransitionRequest(
    UUID transitionId,
    UUID candidateAccountIndexFence,
    CanonicalGameplayBindingIdentity candidate,
    CanonicalIssuerReservationFenceEvidence issuerReservation,
    CanonicalGameplayBindingRef expectedPriorBindingRef,
    BigInteger expectedPriorBindingGeneration) {
  public CanonicalGameplayBindingTransitionRequest {
    requireNonNil(transitionId, "transitionId");
    requireNonNil(candidateAccountIndexFence, "candidateAccountIndexFence");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(issuerReservation, "issuerReservation");
    if ((expectedPriorBindingRef == null) != (expectedPriorBindingGeneration == null)) {
      throw new IllegalArgumentException(
          "expected prior binding ref and generation must be supplied together");
    }
    if (expectedPriorBindingGeneration != null && expectedPriorBindingGeneration.signum() <= 0) {
      throw new IllegalArgumentException("expectedPriorBindingGeneration must be positive");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }
}
