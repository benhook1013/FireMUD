package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldPublicationTerminalCompletionGrpcCodec;
import net.firedevops.firemud.common.world.WorldPublicationTerminalCompletionGrpcCodec.Request;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalCompletionServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Standalone authenticated Game Design completion adapter; intentionally not runtime-registered.
 */
public final class WorldPublicationTerminalCompletionGrpcService
    extends WorldPublicationTerminalCompletionServiceGrpc
        .WorldPublicationTerminalCompletionServiceImplBase {
  private final GameDesignPublicationTerminalClient gameDesignClient;
  private final WorldPublicationTerminalService terminalService;
  private final String trustedNamespace;

  public WorldPublicationTerminalCompletionGrpcService(
      WorldPublicationTerminalRepository repository,
      GameDesignPublicationTerminalClient gameDesignClient,
      String trustedNamespace) {
    Objects.requireNonNull(repository, "repository");
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient, "gameDesignClient");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
    terminalService =
        new WorldPublicationTerminalService(repository, this::authenticateAndVerifyAndHold);
  }

  @Override
  public void completeWorldPublicationTerminal(
      CompleteWorldPublicationTerminalRequest request,
      StreamObserver<CompleteWorldPublicationTerminalResponse> responseObserver) {
    GrpcPeerIdentity caller = GrpcPeerIdentity.current();
    if (!isAuthenticatedGameDesignPeer(caller)) {
      deny(responseObserver);
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World terminal completion cannot join an ambient transaction")
              .asRuntimeException());
      return;
    }

    Request completion;
    try {
      completion = WorldPublicationTerminalCompletionGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("A complete canonical World terminal completion is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(completion.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("World terminal namespace must match the authenticated peer")
              .asRuntimeException());
      return;
    }

    GameDesignPublicationTerminalEvidence committed;
    try {
      committed =
          terminalService.complete(completion.operationBytes(), completion.terminalEvidenceBytes());
    } catch (UpstreamTerminalUnavailableException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design has no definitive exact terminal result yet")
              .asRuntimeException());
      return;
    } catch (StatusRuntimeException unavailable) {
      Status.Code code = Status.fromThrowable(unavailable).getCode();
      Status resultStatus =
          code == Status.Code.UNAVAILABLE
                  || code == Status.Code.DEADLINE_EXCEEDED
                  || code == Status.Code.CANCELLED
                  || code == Status.Code.RESOURCE_EXHAUSTED
              ? Status.UNAVAILABLE
              : Status.FAILED_PRECONDITION;
      responseObserver.onError(
          resultStatus
              .withDescription("Game Design terminal authority could not be confirmed")
              .asRuntimeException());
      return;
    } catch (WorldPublicationTerminalRepository.PublicationTerminalConflictException conflict) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World terminal conflicts with exact retained owner evidence")
              .asRuntimeException());
      return;
    } catch (WorldPublicationTerminalService.TerminalDeniedException denied) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design terminal evidence is not definitive or exact")
              .asRuntimeException());
      return;
    } catch (org.springframework.dao.DataAccessException | DataAccessException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("World terminal owner storage is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("World terminal completion failed").asRuntimeException());
      return;
    }

    try {
      responseObserver.onNext(
          WorldPublicationTerminalCompletionGrpcCodec.toResponse(completion, committed));
      responseObserver.onCompleted();
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World terminal commit differs from the complete request")
              .asRuntimeException());
    }
  }

  private WorldPublicationTerminalService.VerifiedTerminal authenticateAndVerifyAndHold(
      byte[] operationBytes, byte[] terminalBytes) {
    GrpcPeerIdentity caller = GrpcPeerIdentity.current();
    if (!isAuthenticatedGameDesignPeer(caller)) {
      throw new WorldPublicationTerminalService.TerminalDeniedException(
          "Verified same-namespace Game Design workload is required");
    }
    byte[] retainedOperation = operationBytes.clone();
    byte[] selectedTerminal = terminalBytes.clone();
    var readRequest =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            trustedNamespace, retainedOperation);
    GameDesignPublicationTerminalReadEvidence.ReadResult readback;
    try {
      readback = gameDesignClient.read(readRequest);
    } catch (StatusRuntimeException failure) {
      Status.Code code = Status.fromThrowable(failure).getCode();
      if (code == Status.Code.UNAVAILABLE
          || code == Status.Code.DEADLINE_EXCEEDED
          || code == Status.Code.CANCELLED
          || code == Status.Code.RESOURCE_EXHAUSTED) {
        throw new UpstreamTerminalUnavailableException(failure);
      }
      throw failure;
    } catch (RuntimeException invalidEvidence) {
      throw new WorldPublicationTerminalService.TerminalDeniedException(
          "Game Design terminal read did not return valid definitive evidence");
    }
    if (readback == null) {
      throw new WorldPublicationTerminalService.TerminalDeniedException(
          "Game Design terminal read returned no response");
    }
    if (!Arrays.equals(readRequest.canonicalBytes(), readback.request().canonicalBytes())) {
      throw new WorldPublicationTerminalService.TerminalDeniedException(
          "Game Design terminal read changed the complete original operation");
    }
    if (readback.status() == GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN) {
      throw new UpstreamTerminalUnavailableException(null);
    }
    if (readback.terminalEvidence().isEmpty()) {
      throw new WorldPublicationTerminalService.TerminalDeniedException(
          "Game Design terminal read omitted definitive terminal evidence");
    }
    GameDesignPublicationTerminalEvidence evidence = readback.terminalEvidence().orElseThrow();
    if (!Arrays.equals(retainedOperation, evidence.operationBytes())
        || !Arrays.equals(selectedTerminal, evidence.canonicalBytes())) {
      throw new WorldPublicationTerminalRepository.PublicationTerminalConflictException(
          "Authenticated Game Design terminal read differs from the submitted exact evidence");
    }
    var worldRequest = WorldPublicationTerminal.Request.fromStored(evidence.canonicalBytes());
    return new WorldPublicationTerminalService.VerifiedTerminal(
        worldRequest,
        new WorldPublicationTerminalService.HeldTerminalAuthority() {
          private boolean open = true;

          @Override
          public void requireHeld() {
            if (!open
                || !caller.equals(GrpcPeerIdentity.current())
                || !isAuthenticatedGameDesignPeer(GrpcPeerIdentity.current())
                || !Arrays.equals(retainedOperation, evidence.operationBytes())
                || !Arrays.equals(selectedTerminal, evidence.canonicalBytes())) {
              throw new WorldPublicationTerminalService.TerminalDeniedException(
                  "Authenticated Game Design identity and exact terminal must remain stable through World commit");
            }
          }

          @Override
          public void close() {
            open = false;
          }
        });
  }

  private boolean isAuthenticatedGameDesignPeer(GrpcPeerIdentity peer) {
    return peer != null
        && peer.isService("game-design-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext();
  }

  private static void deny(StreamObserver<?> responseObserver) {
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription(
                "Only the verified same-namespace Game Design workload without end-user context is allowed")
            .asRuntimeException());
  }

  private static final class UpstreamTerminalUnavailableException extends RuntimeException {
    private UpstreamTerminalUnavailableException(Throwable cause) {
      super("Game Design terminal authority is not definitive", cause);
    }
  }
}
