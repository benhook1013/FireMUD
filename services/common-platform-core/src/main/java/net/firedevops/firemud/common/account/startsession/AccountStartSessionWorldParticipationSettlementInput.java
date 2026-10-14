package net.firedevops.firemud.common.account.startsession;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Lookup-only selector for exact historical settlement of one Account World participation. */
public record AccountStartSessionWorldParticipationSettlementInput(
    String targetNamespace,
    UUID settlementRequestId,
    UUID accountWorldParticipationId,
    long accountWorldParticipationFence) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AccountStartSessionWorldParticipationSettlementInput {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical Account target namespace required");
    }
    requireNonNil(settlementRequestId, "Settlement request correlation ID");
    requireNonNil(accountWorldParticipationId, "Account World participation ID");
    if (accountWorldParticipationFence <= 0L) {
      throw new IllegalArgumentException("Account World participation fence must be positive");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label + " required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
