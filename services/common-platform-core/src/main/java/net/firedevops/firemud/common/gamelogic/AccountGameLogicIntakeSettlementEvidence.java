package net.firedevops.firemud.common.gamelogic;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Immutable original Account settlement; transport correlation is outside its identity. */
public record AccountGameLogicIntakeSettlementEvidence(
    GameLogicGameplayRuleIntakeTerminal terminal) {
  public static final String SCHEMA = "account-game-logic-intake-settlement/v1";
  // Preserve the original stored receipt envelope over the 16 MiB terminal budget.
  public static final int MAX_CANONICAL_BYTES =
      GameLogicGameplayRuleIntakeTerminal.MAX_CANONICAL_BYTES + 1024;
  public static final int MAX_WIRE_BYTES = 24 * 1024 * 1024;

  public AccountGameLogicIntakeSettlementEvidence {
    Objects.requireNonNull(terminal);
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, terminal.canonicalBytes());
    if (out.size() > MAX_CANONICAL_BYTES)
      throw new IllegalArgumentException("Oversized settlement receipt");
    return out.toByteArray();
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  public static AccountGameLogicIntakeSettlementEvidence fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CANONICAL_BYTES)
      throw new IllegalArgumentException("Oversized settlement receipt");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    reader.expect(SCHEMA);
    var receipt =
        new AccountGameLogicIntakeSettlementEvidence(
            GameLogicGameplayRuleIntakeTerminal.fromStored(reader.bytes()));
    reader.requireEnd();
    if (!Arrays.equals(bytes, receipt.canonicalBytes()))
      throw new IllegalArgumentException("Noncanonical settlement");
    return receipt;
  }
}
