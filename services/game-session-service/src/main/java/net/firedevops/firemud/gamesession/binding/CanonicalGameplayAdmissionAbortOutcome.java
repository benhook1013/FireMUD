package net.firedevops.firemud.gamesession.binding;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;

/** Result of reconciling the original Account lease during a first-NEW abort attempt. */
public record CanonicalGameplayAdmissionAbortOutcome(
    UUID transitionId,
    State state,
    UUID orphanCleanupId,
    AccountGameplayAdmissionLeaseGrpcCodec.OperationEvidence accountOperation) {
  public CanonicalGameplayAdmissionAbortOutcome {
    requireNonNil(transitionId, "transitionId");
    Objects.requireNonNull(state, "state");
    if (accountOperation == null) {
      if (state != State.ABORTED) {
        throw new IllegalArgumentException("Only a retained ABORTED source may omit an RPC result");
      }
      requireNonNil(orphanCleanupId, "orphanCleanupId");
    } else {
      State accountState =
          switch (accountOperation.state()) {
            case ABORTED -> State.ABORTED;
            case COMMITTED -> State.ACCOUNT_COMMITTED;
            case PENDING -> State.PENDING;
          };
      if (accountState != state
          || accountOperation.originalLeaseEvidence().leaseKind()
              != net.firedevops.firemud.common.account.admission
                  .AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING) {
        throw new IllegalArgumentException("Account result is not the exact NEW abort operation");
      }
      if (state == State.ABORTED) {
        requireNonNil(orphanCleanupId, "orphanCleanupId");
        if (!transitionId.equals(accountOperation.bindingDecisionId())) {
          throw new IllegalArgumentException(
              "ABORTED result changed the exact transition identity");
        }
        if (!orphanCleanupId.equals(accountOperation.orphanCleanupId())
            || accountOperation.originalAckReceipt() != null) {
          throw new IllegalArgumentException(
              "ABORTED result differs from Account cleanup evidence");
        }
      } else if (state == State.ACCOUNT_COMMITTED) {
        if (orphanCleanupId != null
            || !transitionId.equals(accountOperation.bindingDecisionId())
            || accountOperation.originalAckReceipt() == null) {
          throw new IllegalArgumentException("COMMITTED result must retain its exact ACK receipt");
        }
      } else if (orphanCleanupId != null
          || accountOperation.bindingDecisionId() != null
          || accountOperation.orphanCleanupId() != null
          || accountOperation.originalAckReceipt() != null) {
        throw new IllegalArgumentException("A non-aborted result cannot claim terminal evidence");
      }
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  public enum State {
    ABORTED,
    ACCOUNT_COMMITTED,
    PENDING
  }
}
