package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.Status;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Explicit, unregistered verifier for authenticated Game Design terminal readback. Construction
 * does not initialize or register the transport; the owning composition must deliberately do so.
 */
public final class WorldPublicationTerminalClientVerifier
    implements WorldPublicationTerminalService.TerminalAuthorityVerifier {
  private final GameDesignPublicationTerminalClient client;
  private final String workloadNamespace;

  public WorldPublicationTerminalClientVerifier(
      GameDesignPublicationTerminalClient client, String workloadNamespace) {
    this.client = Objects.requireNonNull(client, "client");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World terminal verifier namespace must be canonical");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public WorldPublicationTerminalService.VerifiedTerminal authenticateAndVerifyAndHold(
      byte[] operationBytes, byte[] terminalBytes) {
    Objects.requireNonNull(operationBytes, "operationBytes");
    Objects.requireNonNull(terminalBytes, "terminalBytes");
    ReadRequest request = ReadRequest.create(workloadNamespace, operationBytes);
    var response = client.read(request);
    if (!Arrays.equals(request.canonicalBytes(), response.request().canonicalBytes())
        || response.status() == Status.UNKNOWN) {
      throw new WorldPublicationTerminalService.TerminalDeniedException(
          "Game Design has no exact sealed terminal result for this World publication operation");
    }
    var evidence = response.terminalEvidence().orElseThrow();
    if (!Arrays.equals(operationBytes, evidence.operationBytes())
        || !Arrays.equals(terminalBytes, evidence.canonicalBytes())) {
      throw new WorldPublicationTerminalService.TerminalDeniedException(
          "Authenticated Game Design terminal readback differs from the submitted exact evidence");
    }
    WorldPublicationTerminal.Request worldRequest =
        WorldPublicationTerminal.Request.fromStored(evidence.canonicalBytes());
    return new WorldPublicationTerminalService.VerifiedTerminal(
        worldRequest,
        new WorldPublicationTerminalService.HeldTerminalAuthority() {
          // Game Design terminal evidence is immutable and the remote client authenticated its
          // exact operation/result. No RPC or time-limited permission is held under World locks.
          @Override
          public void requireHeld() {}

          @Override
          public void close() {}
        });
  }
}
