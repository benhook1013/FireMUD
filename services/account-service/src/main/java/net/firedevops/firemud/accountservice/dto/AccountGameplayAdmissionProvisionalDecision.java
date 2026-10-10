package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;

/**
 * Exact non-authorizing PROVISIONAL observation returned by the Game Session owner. It proves
 * neither durable Account state nor current Account/World authority.
 */
public record AccountGameplayAdmissionProvisionalDecision(
    UUID bindingDecisionId, AccountGameplayAdmissionLeaseEvidence evidence) {
  public AccountGameplayAdmissionProvisionalDecision {
    if (bindingDecisionId == null
        || bindingDecisionId.version() != 4
        || bindingDecisionId.variant() != 2
        || new UUID(0L, 0L).equals(bindingDecisionId)) {
      throw new IllegalArgumentException("Canonical nonnil UUIDv4 decision ID is required");
    }
    Objects.requireNonNull(evidence, "Original Account lease evidence is required");
  }

  @Override
  public String toString() {
    return "AccountGameplayAdmissionProvisionalDecision[status=PROVISIONAL]";
  }
}
