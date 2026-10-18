package net.firedevops.firemud.common.account.startsession;

/** Authenticated lookup-only client for Account's exact immutable activation settlement. */
public interface AccountStartSessionActivationProtectionSettlementClient {
  AccountStartSessionActivationProtectionSettlementEvidence settle(
      AccountStartSessionActivationProtectionSettlementInput input);
}
