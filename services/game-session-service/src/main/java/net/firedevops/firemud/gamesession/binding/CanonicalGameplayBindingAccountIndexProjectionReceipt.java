package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable exact-member Redis readback evidence retained while the canonical v2 account-index
 * acknowledgement is still outstanding.
 */
public record CanonicalGameplayBindingAccountIndexProjectionReceipt(
    UUID transitionId,
    int obligationOrdinal,
    CanonicalGameplayBindingRef bindingRef,
    UUID accountId,
    UUID tenantId,
    BigInteger bindingGeneration,
    UUID accountIndexFence,
    BigInteger bindingInventoryRevision,
    BigInteger projectionInventoryRevision,
    String member,
    State state) {
  public CanonicalGameplayBindingAccountIndexProjectionReceipt {
    requireNonNil(transitionId, "transitionId");
    if (obligationOrdinal < 0) {
      throw new IllegalArgumentException("obligationOrdinal must not be negative");
    }
    Objects.requireNonNull(bindingRef, "bindingRef");
    requireNonNil(accountId, "accountId");
    requireNonNil(tenantId, "tenantId");
    requirePositive(bindingGeneration, "bindingGeneration");
    requireNonNil(accountIndexFence, "accountIndexFence");
    requirePositive(bindingInventoryRevision, "bindingInventoryRevision");
    requirePositive(projectionInventoryRevision, "projectionInventoryRevision");
    Objects.requireNonNull(member, "member");
    Objects.requireNonNull(state, "state");
    if (state != State.PRESENT_AWAITING_COVERAGE) {
      throw new IllegalArgumentException("only retained exact PRESENT readback is supported here");
    }
    var expected =
        new CanonicalGameplayAccountIndexMember(bindingRef, bindingGeneration, accountIndexFence)
            .value();
    if (!expected.equals(member)) {
      throw new IllegalArgumentException("member does not match the exact durable binding tuple");
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

  public enum State {
    PRESENT_AWAITING_COVERAGE
  }
}
