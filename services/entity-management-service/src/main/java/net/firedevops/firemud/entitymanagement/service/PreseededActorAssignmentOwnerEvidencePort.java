package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;

/**
 * Game Session owner read for one frozen PRESEEDED_ONLY assignment target, bound to the exact
 * Account staging snapshot already read by Entity.
 *
 * <p>The production adapter must bind the supplied single Account staging snapshot to the Game
 * Session owner-validated canonical tenant, realm, active instance, published release,
 * PRESEEDED_ONLY policy/catalog revision, and namespace/scope target. It returns evidence
 * independent of the caller's expected-target fields; Entity exact-compares both the target and its
 * Account eligibility snapshot before persistence. CURRENT_AT_REVALIDATION is a point-in-time
 * Account observation, not a held JOIN authorization, PLAY grant, or admission decision; the
 * persisted actor and receipt are non-admitting. Account evidence alone does not satisfy this port.
 * The production adapter uses a selector-only Game Session owner read and the one Account staging
 * snapshot already read by Entity; it performs no second Account lookup.
 */
public interface PreseededActorAssignmentOwnerEvidencePort {
  PreseededActorAssignmentOwnerEvidence resolveCurrentEligibleTarget(
      PreseededActorAssignmentRequest request,
      RuntimeAccountIdentityEvidence accountIdentityEvidence,
      AccountActorStagingEligibilityEvidence stagingEligibilityEvidence);

  final class OwnerEvidenceUnavailableException extends IllegalStateException {
    public OwnerEvidenceUnavailableException() {
      super("PRESEEDED_ASSIGNMENT_OWNER_EVIDENCE_UNAVAILABLE");
    }

    public OwnerEvidenceUnavailableException(Throwable cause) {
      super("PRESEEDED_ASSIGNMENT_OWNER_EVIDENCE_UNAVAILABLE", cause);
    }
  }
}
