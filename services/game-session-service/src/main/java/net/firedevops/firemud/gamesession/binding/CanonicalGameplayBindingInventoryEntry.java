package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** One exact canonical binding row in a repeatable Game Session inventory snapshot. */
public record CanonicalGameplayBindingInventoryEntry(
    CanonicalGameplayBindingIdentity identity,
    CanonicalGameplayBindingRef bindingRef,
    BigInteger bindingGeneration,
    UUID accountIndexFence,
    UUID issuerReservationId,
    UUID transitionId,
    Lifecycle lifecycle,
    IndexState accountIndexState,
    IndexState issuerIndexState,
    BigInteger inventoryRevision) {
  public CanonicalGameplayBindingInventoryEntry {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(bindingRef, "bindingRef");
    requirePositive(bindingGeneration, "bindingGeneration");
    requireNonNil(accountIndexFence, "accountIndexFence");
    requireNonNil(issuerReservationId, "issuerReservationId");
    requireNonNil(transitionId, "transitionId");
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(accountIndexState, "accountIndexState");
    Objects.requireNonNull(issuerIndexState, "issuerIndexState");
    requirePositive(inventoryRevision, "inventoryRevision");
    if (!identity.bindingRef().equals(bindingRef)) {
      throw new IllegalArgumentException("stored bindingRef does not match canonical identity");
    }
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

  public enum Lifecycle {
    CANDIDATE_PREPARED,
    PROVISIONAL,
    ACTIVE,
    TERMINAL_UNRESOLVED,
    TERMINAL
  }

  public enum IndexState {
    REPAIR_REQUIRED,
    ACKNOWLEDGED
  }
}
