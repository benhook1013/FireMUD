package net.firedevops.firemud.common.account.startsession;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;

/** Full immutable Account settlement identity and the exact World terminal receipt it retained. */
public record AccountStartSessionActivationProtectionSettlementEvidence(
    UUID settlementRequestId,
    WorldOriginalStartSessionActivationTerminalEvidence terminal,
    Instant settledAt) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AccountStartSessionActivationProtectionSettlementEvidence {
    Objects.requireNonNull(settlementRequestId, "settlement correlation ID required");
    if (NIL_UUID.equals(settlementRequestId)) {
      throw new IllegalArgumentException("Settlement correlation ID must be non-nil");
    }
    Objects.requireNonNull(terminal, "complete World terminal receipt required");
    Objects.requireNonNull(settledAt, "Account settlement timestamp required");
    if (settledAt.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Account settlement timestamp must preserve PostgreSQL microseconds");
    }
    if (terminal.outcome() != WorldCanonicalInstanceActivation.Outcome.COMMITTED
        && terminal.outcome() != WorldCanonicalInstanceActivation.Outcome.ABORTED) {
      throw new IllegalArgumentException("Exact terminal activation outcome required");
    }
  }

  public UUID accountProtectionId() {
    return terminal.accountProtectionEvidence().accountProtectionId();
  }

  public long accountProtectionFence() {
    return terminal.accountProtectionEvidence().accountProtectionFence();
  }
}
