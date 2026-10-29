package net.firedevops.firemud.common.account.startsession;

/** Account-owned admission-protection acquisition transport. */
@FunctionalInterface
public interface AccountStartSessionAdmissionProtectionAcquisitionClient {
  /** Acquires or exactly replays the Account-owned protection for this complete canonical input. */
  AccountStartSessionAdmissionProtectionEvidence acquire(
      AccountStartSessionAdmissionProtectionRequest request);
}
