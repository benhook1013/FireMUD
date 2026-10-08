package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Issuer-index obligation joined to one exact capacity reservation. */
public record CanonicalGameplayBindingIssuerIndexObligation(
    UUID transitionId,
    CanonicalGameplayBindingRef bindingRef,
    BigInteger bindingGeneration,
    UUID reservationId,
    Status status,
    BigInteger inventoryRevision) {
  public CanonicalGameplayBindingIssuerIndexObligation {
    requireNonNil(transitionId, "transitionId");
    Objects.requireNonNull(bindingRef, "bindingRef");
    requirePositive(bindingGeneration, "bindingGeneration");
    requireNonNil(reservationId, "reservationId");
    Objects.requireNonNull(status, "status");
    requirePositive(inventoryRevision, "inventoryRevision");
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  public enum Status {
    REQUIRED
  }
}
