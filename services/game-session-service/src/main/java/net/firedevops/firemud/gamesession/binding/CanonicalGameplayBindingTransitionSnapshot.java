package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Durable prepared candidate; PREPARED is explicitly non-admissible. */
public record CanonicalGameplayBindingTransitionSnapshot(
    UUID transitionId,
    CanonicalGameplayBindingIdentity candidate,
    BigInteger bindingGeneration,
    CanonicalGameplayBindingRef bindingRef,
    BigInteger inventoryRevision,
    UUID issuerReservationId,
    UUID reservationFence,
    UUID candidateAccountIndexFence,
    CanonicalGameplayBindingRef expectedPriorBindingRef,
    BigInteger expectedPriorBindingGeneration,
    Status status,
    CanonicalGameplayAccountCoverageEvidence accountCoverageEvidence) {
  public CanonicalGameplayBindingTransitionSnapshot {
    Objects.requireNonNull(transitionId, "transitionId");
    Objects.requireNonNull(candidate, "candidate");
    requirePositive(bindingGeneration, "bindingGeneration");
    Objects.requireNonNull(bindingRef, "bindingRef");
    requirePositive(inventoryRevision, "inventoryRevision");
    Objects.requireNonNull(issuerReservationId, "issuerReservationId");
    Objects.requireNonNull(reservationFence, "reservationFence");
    Objects.requireNonNull(candidateAccountIndexFence, "candidateAccountIndexFence");
    if (candidateAccountIndexFence.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("candidateAccountIndexFence must not be nil");
    }
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(accountCoverageEvidence, "accountCoverageEvidence");
    if (accountCoverageEvidence
            instanceof CanonicalGameplayAccountCoverageEvidence.Historical historical
        && !historical.accountId().equals(candidate.accountId())) {
      throw new IllegalArgumentException(
          "historical account coverage evidence belongs to another account");
    }
    if ((expectedPriorBindingRef == null) != (expectedPriorBindingGeneration == null)) {
      throw new IllegalArgumentException(
          "expected prior binding ref and generation must be supplied together");
    }
    if (expectedPriorBindingGeneration != null && expectedPriorBindingGeneration.signum() <= 0) {
      throw new IllegalArgumentException("expectedPriorBindingGeneration must be positive");
    }
    if (!bindingRef.equals(candidate.bindingRef())) {
      throw new IllegalArgumentException("bindingRef must be derived from candidate identity");
    }
  }

  private static void requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  public enum Status {
    PREPARED,
    PROVISIONAL,
    COMMITTED,
    ABORTED,
    AMBIGUOUS
  }
}
