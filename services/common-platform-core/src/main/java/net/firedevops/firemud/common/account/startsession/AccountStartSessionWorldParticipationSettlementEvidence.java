package net.firedevops.firemud.common.account.startsession;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;

/** Full immutable Account settlement identity and the exact World terminal it retained. */
public record AccountStartSessionWorldParticipationSettlementEvidence(
    UUID settlementRequestId, WorldStartSessionExecutionTerminal terminal, Instant settledAt) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AccountStartSessionWorldParticipationSettlementEvidence {
    Objects.requireNonNull(settlementRequestId, "settlement correlation ID required");
    if (NIL_UUID.equals(settlementRequestId)) {
      throw new IllegalArgumentException("Settlement correlation ID must be non-nil");
    }
    Objects.requireNonNull(terminal, "complete World terminal required");
    Objects.requireNonNull(settledAt, "Account settlement timestamp required");
    if (settledAt.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Account settlement timestamp must preserve PostgreSQL microseconds");
    }
  }

  public UUID accountWorldParticipationId() {
    return terminal.accountWorldParticipationId();
  }

  public long accountWorldParticipationFence() {
    return terminal.accountWorldParticipationFence();
  }
}
