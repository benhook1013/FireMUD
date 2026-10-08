package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Exact member-level account-index work; REQUIRED is not evidence of execution or readback. */
public record CanonicalGameplayBindingAccountIndexObligation(
    UUID transitionId,
    int ordinal,
    CanonicalGameplayBindingRef bindingRef,
    UUID accountId,
    UUID tenantId,
    Action action,
    BigInteger bindingGeneration,
    BigInteger expectedPriorGeneration,
    UUID accountIndexFence,
    ExecutionPhase executionPhase,
    Status status,
    BigInteger inventoryRevision,
    ProjectionState projectionState,
    String projectedMember,
    BigInteger projectionInventoryRevision) {
  public CanonicalGameplayBindingAccountIndexObligation {
    requireNonNil(transitionId, "transitionId");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    Objects.requireNonNull(bindingRef, "bindingRef");
    requireNonNil(accountId, "accountId");
    requireNonNil(tenantId, "tenantId");
    Objects.requireNonNull(action, "action");
    requirePositive(bindingGeneration, "bindingGeneration");
    if (expectedPriorGeneration != null && expectedPriorGeneration.signum() < 0) {
      throw new IllegalArgumentException("expectedPriorGeneration must not be negative");
    }
    requireNonNil(accountIndexFence, "accountIndexFence");
    Objects.requireNonNull(executionPhase, "executionPhase");
    Objects.requireNonNull(status, "status");
    requirePositive(inventoryRevision, "inventoryRevision");
    Objects.requireNonNull(projectionState, "projectionState");
    switch (projectionState) {
      case REQUIRED -> {
        if (projectedMember != null || projectionInventoryRevision != null) {
          throw new IllegalArgumentException(
              "unobserved account-index obligation cannot carry Redis readback evidence");
        }
      }
      case PRESENT_AWAITING_COVERAGE -> {
        if (projectedMember == null || projectedMember.isBlank()) {
          throw new IllegalArgumentException(
              "present account-index projection requires the exact Redis member");
        }
        requirePositive(projectionInventoryRevision, "projectionInventoryRevision");
      }
      case ABSENT_AWAITING_COVERAGE -> {
        if (projectedMember != null) {
          throw new IllegalArgumentException(
              "absent account-index projection cannot carry a present member");
        }
        requirePositive(projectionInventoryRevision, "projectionInventoryRevision");
      }
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

  public enum Action {
    ADD_OR_RETAIN,
    REMOVE
  }

  public enum ExecutionPhase {
    BEFORE_FINAL_CAS,
    AFTER_FINAL_CAS
  }

  public enum Status {
    REQUIRED
  }

  /**
   * Local Redis observation is retained separately from the still-required v2 owner
   * acknowledgement. It is never admission evidence or account-wide coverage proof.
   */
  public enum ProjectionState {
    REQUIRED,
    PRESENT_AWAITING_COVERAGE,
    ABSENT_AWAITING_COVERAGE
  }
}
