package net.firedevops.firemud.common.account.startsession;

/**
 * Explicit caller-side seam for Account's original StartSession protection acquisition.
 *
 * <p>Returned Account evidence does not prove the caller's current Game Session claim or expiry;
 * the consumer must independently compare both against its own retained owner state.
 */
public interface AccountStartSessionAdmissionProtectionAcquisitionClient {
  AccountStartSessionAdmissionProtectionEvidence acquire(
      AccountStartSessionAdmissionProtectionAcquisitionInput input);
}
