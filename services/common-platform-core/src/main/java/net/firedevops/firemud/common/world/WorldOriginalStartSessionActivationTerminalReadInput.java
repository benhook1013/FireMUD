package net.firedevops.firemud.common.world;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact lookup-only World read selector derived from retained Account activation evidence. */
public final class WorldOriginalStartSessionActivationTerminalReadInput {
  private final UUID readRequestId;
  private final AccountStartSessionActivationProtectionEvidence protectionEvidence;

  public WorldOriginalStartSessionActivationTerminalReadInput(
      UUID readRequestId, AccountStartSessionActivationProtectionEvidence protectionEvidence) {
    if (readRequestId == null || new UUID(0L, 0L).equals(readRequestId)) {
      throw new IllegalArgumentException("Non-nil World terminal-read correlation ID required");
    }
    this.readRequestId = readRequestId;
    this.protectionEvidence =
        Objects.requireNonNull(protectionEvidence, "Account protection evidence required");
  }

  public UUID readRequestId() {
    return readRequestId;
  }

  public AccountStartSessionActivationProtectionEvidence protectionEvidence() {
    return protectionEvidence;
  }

  public String targetNamespace() {
    return protectionEvidence
        .request()
        .originalTuple()
        .preAuthorizationTuple()
        .action()
        .scope()
        .targetNamespace();
  }

  public void requireTargetNamespace(String expectedNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(targetNamespace())) {
      throw new IllegalArgumentException(
          "World terminal read namespace differs from retained evidence");
    }
  }
}
