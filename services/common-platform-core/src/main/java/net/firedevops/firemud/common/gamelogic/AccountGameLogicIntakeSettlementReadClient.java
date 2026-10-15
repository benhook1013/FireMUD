package net.firedevops.firemud.common.gamelogic;

/** Account-owned settlement production/recovery, invoked outside SQL by Game Design. */
@FunctionalInterface
public interface AccountGameLogicIntakeSettlementReadClient {
  AccountGameLogicIntakeSettlementReadEvidence read(
      AccountGameLogicIntakeSettlementReadEvidence.Request request);
}
