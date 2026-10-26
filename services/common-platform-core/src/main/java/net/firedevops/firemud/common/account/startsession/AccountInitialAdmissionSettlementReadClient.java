package net.firedevops.firemud.common.account.startsession;

/** Authenticated owner-read boundary; pure evidence values alone confer no terminal authority. */
@FunctionalInterface
public interface AccountInitialAdmissionSettlementReadClient {
  AccountInitialAdmissionSettlementReadEvidence read(
      AccountInitialAdmissionSettlementReadInput input);
}
