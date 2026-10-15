package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationRequest;
import net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalCompletionServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Standalone Game Design-authenticated terminal handoff; deliberately not registered at runtime.
 */
public final class WorldPublicationTerminalCompletionGrpcService
    extends WorldPublicationTerminalCompletionServiceGrpc
        .WorldPublicationTerminalCompletionServiceImplBase {
  private final WorldPublicationTerminalService owner;
  private final String workloadNamespace;

  public WorldPublicationTerminalCompletionGrpcService(
      WorldPublicationTerminalService owner, String workloadNamespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void completeVersionPublication(
      CompleteVersionPublicationRequest request,
      StreamObserver<CompleteVersionPublicationResponse> observer) {
    if (!requireAuthenticatedGameDesignPeer(observer)) return;

    final WorldPublicationTerminalCompletionGrpcCodec.Request decoded;
    try {
      decoded = WorldPublicationTerminalCompletionGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          observer,
          Status.INVALID_ARGUMENT,
          "Canonical World publication terminal completion is required");
      return;
    }
    if (!workloadNamespace.equals(decoded.targetNamespace())) {
      fail(
          observer,
          Status.PERMISSION_DENIED,
          "World publication terminal namespace differs from the owner");
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "World publication terminal requires independent owner operations");
      return;
    }

    final GameDesignPublicationTerminalEvidence committed;
    try {
      committed = owner.complete(decoded.originalOperation(), decoded.gameDesignTerminalEvidence());
    } catch (StatusRuntimeException failure) {
      fail(
          observer,
          Status.fromCode(failure.getStatus().getCode()),
          "Game Design publication terminal request failed");
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE, "World publication terminal storage is unavailable");
      return;
    } catch (IllegalArgumentException | IllegalStateException | SecurityException rejected) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "World could not verify or commit the exact Game Design publication terminal");
      return;
    } catch (RuntimeException failure) {
      fail(observer, Status.INTERNAL, "World publication terminal completion failed");
      return;
    }

    final CompleteVersionPublicationResponse response;
    try {
      response = WorldPublicationTerminalCompletionGrpcCodec.toResponse(decoded, committed);
    } catch (RuntimeException inconsistent) {
      fail(
          observer,
          Status.INTERNAL,
          "World publication terminal did not produce an exact canonical readback");
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private boolean requireAuthenticatedGameDesignPeer(StreamObserver<?> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      fail(observer, Status.UNAUTHENTICATED, "Verified workload identity required");
      return false;
    }
    String expectedUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    if (expectedUri.equals(peer.uri())
        && peer.isService("game-design-service")
        && peer.isInNamespace(workloadNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    fail(
        observer,
        Status.PERMISSION_DENIED,
        "Only the exact same-namespace Game Design workload without end-user context is allowed");
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
