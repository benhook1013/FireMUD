package net.firedevops.firemud.common.account.startsession;

/** Explicit World-side seam for asking Account to retain its original activation source lock. */
public interface AccountStartSessionActivationProtectionAcquisitionClient {
  /**
   * Acquires or recovers Account's immutable first receipt for the exact activation statement.
   * Returned evidence is integrity-checked but does not prove World-side PREPARING currentness.
   */
  AccountStartSessionActivationProtectionEvidence acquire(
      AccountStartSessionActivationProtectionAcquisitionInput input);
}
