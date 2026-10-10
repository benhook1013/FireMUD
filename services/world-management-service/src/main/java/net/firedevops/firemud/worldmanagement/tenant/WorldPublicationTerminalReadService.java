package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone World owner read of its exact persisted publication terminal and phase. */
public final class WorldPublicationTerminalReadService {
  private final WorldPublicationTerminalRepository repository;
  private final String workloadNamespace;

  public WorldPublicationTerminalReadService(
      WorldPublicationTerminalRepository repository, String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Complete operation and terminal bytes are lookup identity only, never Account authority. */
  public GameDesignPublicationTerminalEvidence read(
      WorldPublicationTerminalReadEvidence.Request request) {
    requireAuthenticatedAccountPeer();
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("World publication terminal namespace differs from the owner")
          .asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent World publication terminal snapshot required")
          .asRuntimeException();
    }

    final GameDesignPublicationOperationBinding operation;
    final WorldPublicationTerminal.Request terminalRequest;
    try {
      operation = GameDesignPublicationOperationBinding.fromStored(request.originalOperation());
      terminalRequest =
          WorldPublicationTerminal.request(request.expectedGameDesignTerminalEvidence());
    } catch (RuntimeException malformed) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Canonical original publication operation and terminal are required")
          .asRuntimeException();
    }
    if (!Arrays.equals(operation.canonicalBytes(), request.originalOperation())
        || !Arrays.equals(terminalRequest.operationBytes(), request.originalOperation())
        || !Arrays.equals(
            terminalRequest.terminalBytes(), request.expectedGameDesignTerminalEvidence())
        || !workloadNamespace.equals(operation.world().request().targetNamespace())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("World terminal lookup differs from the exact original publication")
          .asRuntimeException();
    }

    try {
      GameDesignPublicationTerminalEvidence committed =
          repository
              .readCommitted(terminalRequest)
              .orElseThrow(
                  () ->
                      Status.FAILED_PRECONDITION
                          .withDescription("Exact committed World terminal is unavailable")
                          .asRuntimeException());
      if (!Arrays.equals(
          request.expectedGameDesignTerminalEvidence(), committed.canonicalBytes())) {
        throw Status.FAILED_PRECONDITION
            .withDescription("World terminal differs from the exact expected Game Design result")
            .asRuntimeException();
      }
      // readCommitted verifies the World owner phase against this exact original terminal.
      return committed;
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (WorldPublicationTerminalRepository.PublicationTerminalConflictException conflict) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact World publication terminal owner evidence is unavailable")
          .asRuntimeException();
    } catch (DataAccessException | org.jooq.exception.DataAccessException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("World publication terminal owner read is unavailable")
          .asRuntimeException();
    } catch (IllegalArgumentException inconsistent) {
      throw Status.FAILED_PRECONDITION
          .withDescription("World publication terminal owner evidence is inconsistent")
          .asRuntimeException();
    } catch (RuntimeException failure) {
      throw Status.INTERNAL
          .withDescription("World publication terminal owner read failed")
          .asRuntimeException();
    }
  }

  private void requireAuthenticatedAccountPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    String expected = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    if (!expected.equals(peer.uri())
        || !peer.isService("account-service")
        || !peer.isInNamespace(workloadNamespace)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Account workload required")
          .asRuntimeException();
    }
  }
}
