package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;

/** Durable Account storage readback only; even COMMITTED is not authenticated admission proof. */
public record AccountGameplayAdmissionLeaseOperation(
    AccountGameplayAdmissionLeaseEvidence evidence,
    State state,
    UUID bindingDecisionId,
    UUID orphanCleanupId) {
  public AccountGameplayAdmissionLeaseOperation {
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(state);
    if ((bindingDecisionId != null
            && (bindingDecisionId.version() != 4 || bindingDecisionId.variant() != 2))
        || (orphanCleanupId != null
            && (orphanCleanupId.version() != 4 || orphanCleanupId.variant() != 2))) {
      throw new IllegalArgumentException("Canonical non-nil UUIDv4 terminal identity required");
    }
    if ((state == State.PENDING && (bindingDecisionId != null || orphanCleanupId != null))
        || (state == State.COMMITTED && (bindingDecisionId == null || orphanCleanupId != null))
        || (state == State.ABORTED && orphanCleanupId == null)) {
      throw new IllegalArgumentException("Incomplete admission operation storage state");
    }
  }

  public enum State {
    PENDING,
    COMMITTED,
    ABORTED
  }

  /**
   * ABORTED retains exact binding/token evidence as pending work, never completed cleanup. A null
   * decision is unknown or unobserved, never authoritative proof that no decision exists.
   */
  public boolean hasPendingOrphanCleanup() {
    return state == State.ABORTED;
  }

  @Override
  public String toString() {
    return "AccountGameplayAdmissionLeaseOperation[state=" + state + "]";
  }
}
