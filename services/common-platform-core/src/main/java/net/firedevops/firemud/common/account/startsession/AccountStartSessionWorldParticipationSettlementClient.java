package net.firedevops.firemud.common.account.startsession;

/**
 * Authenticated lookup-only client for Account's exact immutable World participation settlement.
 */
public interface AccountStartSessionWorldParticipationSettlementClient {
  AccountStartSessionWorldParticipationSettlementEvidence settle(
      AccountStartSessionWorldParticipationSettlementInput input);
}
