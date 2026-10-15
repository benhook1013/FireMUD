package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;

/** Immutable Account receipt; transport correlations never enter mutation identity. */
public record AccountGameLogicIntakeSettlement(GameLogicGameplayRuleIntakeTerminal terminal) {
  public static final String SCHEMA = AccountGameLogicIntakeSettlementEvidence.SCHEMA;

  public AccountGameLogicIntakeSettlement {
    Objects.requireNonNull(terminal);
  }

  public byte[] canonicalBytes() {
    return new AccountGameLogicIntakeSettlementEvidence(terminal).canonicalBytes();
  }

  public String digest() {
    return new AccountGameLogicIntakeSettlementEvidence(terminal).digest();
  }

  public static AccountGameLogicIntakeSettlement fromStored(byte[] bytes) {
    return new AccountGameLogicIntakeSettlement(
        AccountGameLogicIntakeSettlementEvidence.fromStored(bytes).terminal());
  }
}
