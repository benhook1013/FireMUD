package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Exact account-wide coverage carrier bound to a durable binding transition. */
public sealed interface CanonicalGameplayAccountCoverageEvidence
    permits CanonicalGameplayAccountCoverageEvidence.NoActiveFlow,
        CanonicalGameplayAccountCoverageEvidence.Historical {
  State state();

  static NoActiveFlow noActiveFlow() {
    return new NoActiveFlow();
  }

  static Historical historical(
      UUID operationId,
      BigInteger operationFence,
      UUID historicalAccountAdmissionFence,
      UUID coverageFence,
      UUID accountId,
      BigInteger inventorySnapshotRevision,
      BigInteger coverageGeneration) {
    return new Historical(
        operationId,
        operationFence,
        historicalAccountAdmissionFence,
        coverageFence,
        accountId,
        inventorySnapshotRevision,
        coverageGeneration);
  }

  enum State {
    NO_ACTIVE_ACCOUNT_WIDE_FLOW,
    HISTORICAL
  }

  record NoActiveFlow() implements CanonicalGameplayAccountCoverageEvidence {
    @Override
    public State state() {
      return State.NO_ACTIVE_ACCOUNT_WIDE_FLOW;
    }
  }

  record Historical(
      UUID operationId,
      BigInteger operationFence,
      UUID historicalAccountAdmissionFence,
      UUID coverageFence,
      UUID accountId,
      BigInteger inventorySnapshotRevision,
      BigInteger coverageGeneration)
      implements CanonicalGameplayAccountCoverageEvidence {
    public Historical {
      requireNonNil(operationId, "operationId");
      requirePositive(operationFence, "operationFence");
      requireNonNil(historicalAccountAdmissionFence, "historicalAccountAdmissionFence");
      requireNonNil(coverageFence, "coverageFence");
      requireNonNil(accountId, "accountId");
      requirePositive(inventorySnapshotRevision, "inventorySnapshotRevision");
      requirePositive(coverageGeneration, "coverageGeneration");
    }

    @Override
    public State state() {
      return State.HISTORICAL;
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
}
