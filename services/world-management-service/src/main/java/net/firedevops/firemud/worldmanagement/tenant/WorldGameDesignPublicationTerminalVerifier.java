package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered World verifier for the immutable Game Design publication terminal.
 *
 * <p>The authenticated read proves only the exact historical Game Design result. Its local
 * authority token stays valid through the World commit but is not an Account source lock, a
 * settlement, or permission to release Account participation. Account source protection remains
 * governed by its original pending publication order and owner-side writer guards.
 */
final class WorldGameDesignPublicationTerminalVerifier
    implements WorldPublicationTerminalService.TerminalAuthorityVerifier {
  private final String workloadNamespace;
  private final GameDesignPublicationTerminalReadClient terminalReadClient;

  WorldGameDesignPublicationTerminalVerifier(
      String workloadNamespace, GameDesignPublicationTerminalReadClient terminalReadClient) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.terminalReadClient = Objects.requireNonNull(terminalReadClient, "terminalReadClient");
  }

  @Override
  public WorldPublicationTerminalService.VerifiedTerminal authenticateAndVerifyAndHold(
      byte[] operationBytes, byte[] terminalBytes) {
    requireAuthenticatedGameDesignCaller();
    requireNoAmbientTransaction();
    Objects.requireNonNull(operationBytes, "operationBytes");
    Objects.requireNonNull(terminalBytes, "terminalBytes");

    GameDesignPublicationOperationBinding operation =
        GameDesignPublicationOperationBinding.fromStored(operationBytes);
    if (!Arrays.equals(operationBytes, operation.canonicalBytes())) {
      throw new IllegalArgumentException("Canonical original Game Design operation required");
    }
    GameDesignPublicationTerminalReadEvidence.Request request =
        GameDesignPublicationTerminalReadEvidence.Request.create(workloadNamespace, operation);
    GameDesignPublicationTerminalReadEvidence read = terminalReadClient.read(request);
    if (read == null
        || !request.equals(read.request())
        || !Arrays.equals(request.originalOperation(), operationBytes)) {
      throw new SecurityException(
          "Game Design terminal read differs from the exact original operation request");
    }

    GameDesignPublicationTerminalEvidence terminal = read.terminalEvidence();
    if (terminal == null
        || !Arrays.equals(operationBytes, terminal.operationBytes())
        || !Arrays.equals(terminalBytes, terminal.canonicalBytes())) {
      throw new SecurityException(
          "Authenticated Game Design terminal differs from the submitted exact operation or result");
    }
    WorldPublicationTerminal.Request worldRequest =
        WorldPublicationTerminal.Request.fromStored(terminal.canonicalBytes());
    return new WorldPublicationTerminalService.VerifiedTerminal(
        worldRequest, new ExactTerminalReadAuthority(operationBytes, terminal.canonicalBytes()));
  }

  private void requireAuthenticatedGameDesignCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-design-service")
        || !peer.isInNamespace(workloadNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException(
          "World publication terminal requires the authenticated same-namespace Game Design workload");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World publication terminal must read Game Design evidence outside owner transactions");
    }
  }

  private static final class ExactTerminalReadAuthority
      implements WorldPublicationTerminalService.HeldTerminalAuthority {
    private final byte[] operationBytes;
    private final byte[] terminalBytes;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private ExactTerminalReadAuthority(byte[] operationBytes, byte[] terminalBytes) {
      this.operationBytes = operationBytes.clone();
      this.terminalBytes = terminalBytes.clone();
    }

    @Override
    public void requireHeld() {
      if (!open.get()
          || operationBytes.length == 0
          || terminalBytes.length == 0
          || !Arrays.equals(
              operationBytes,
              WorldPublicationTerminal.Request.fromStored(terminalBytes).operationBytes())) {
        throw new IllegalStateException(
            "Exact authenticated Game Design terminal read authority is no longer valid");
      }
    }

    @Override
    public void close() {
      open.set(false);
    }
  }
}
