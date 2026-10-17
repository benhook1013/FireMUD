package net.firedevops.firemud.common.account.startsession;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Lookup-only selector for exact historical settlement of one Account activation protection. */
public record AccountStartSessionActivationProtectionSettlementInput(
    String targetNamespace,
    UUID settlementRequestId,
    UUID accountProtectionId,
    long accountProtectionFence) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AccountStartSessionActivationProtectionSettlementInput {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical Account target namespace required");
    }
    requireNonNil(settlementRequestId, "Settlement request correlation ID");
    requireNonNil(accountProtectionId, "Account protection ID");
    if (accountProtectionFence <= 0L) {
      throw new IllegalArgumentException("Account protection fence must be positive");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label + " required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
