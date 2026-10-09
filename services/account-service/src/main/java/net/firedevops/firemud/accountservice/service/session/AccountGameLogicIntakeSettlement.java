package net.firedevops.firemud.accountservice.service.session;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;

/** Immutable Account receipt; transport correlations never enter mutation identity. */
public record AccountGameLogicIntakeSettlement(GameLogicGameplayRuleIntakeTerminal terminal) {
  public static final String SCHEMA = "account-game-logic-intake-settlement/v1";

  public AccountGameLogicIntakeSettlement {
    Objects.requireNonNull(terminal);
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, terminal.canonicalBytes());
    return out.toByteArray();
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  public static AccountGameLogicIntakeSettlement fromStored(byte[] bytes) {
    if (bytes == null
        || bytes.length > GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES + 1024)
      throw new IllegalArgumentException("Oversized settlement receipt");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    reader.expect(SCHEMA);
    var receipt =
        new AccountGameLogicIntakeSettlement(
            GameLogicGameplayRuleIntakeTerminal.fromStored(reader.bytes()));
    reader.requireEnd();
    if (!Arrays.equals(bytes, receipt.canonicalBytes()))
      throw new IllegalArgumentException("Noncanonical settlement");
    return receipt;
  }
}
