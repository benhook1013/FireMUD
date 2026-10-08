package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Request;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeRequest;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedDraftPublicationFreezeServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, same-namespace Game Design receiver for the selected World publication freeze.
 *
 * <p>Workload identity is checked before protobuf parsing. This class deliberately has no
 * registration annotation, so the new boundary remains unavailable until an owner integration
 * explicitly wires and proves it.
 */
public final class WorldSelectedDraftPublicationFreezeGrpcService
    extends WorldSelectedDraftPublicationFreezeServiceGrpc
        .WorldSelectedDraftPublicationFreezeServiceImplBase {
  private final WorldSelectedDraftPublicationFreezeService freezeService;
  private final String trustedNamespace;

  public WorldSelectedDraftPublicationFreezeGrpcService(
      WorldSelectedDraftPublicationFreezeService freezeService, String trustedNamespace) {
    this.freezeService = Objects.requireNonNull(freezeService, "freezeService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void beginVersionPublicationFreeze(
      BeginVersionPublicationFreezeRequest request,
      StreamObserver<BeginVersionPublicationFreezeResponse> responseObserver) {
    if (!requireAuthenticatedGameDesignPeer(responseObserver)) return;
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World selected freeze requires an independent owner operation")
              .asRuntimeException());
      return;
    }

    final Request decoded;
    try {
      decoded = WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("A complete canonical selected World freeze request is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(decoded.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("World freeze namespace must match the authenticated peer")
              .asRuntimeException());
      return;
    }

    final BeginVersionPublicationFreezeResponse response;
    try {
      response =
          WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freezeService.begin(decoded));
    } catch (StatusRuntimeException failure) {
      responseObserver.onError(failure);
      return;
    } catch (SecurityException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authenticated same-namespace Game Design workload is required")
              .asRuntimeException());
      return;
    } catch (WorldDesignPublicationFenceRepository.ConflictException
        | IllegalArgumentException inconsistent) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact World selected-freeze owner evidence is unavailable")
              .asRuntimeException());
      return;
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("World selected-freeze owner storage is unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("World selected-publication freeze failed")
              .asRuntimeException());
      return;
    }

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean requireAuthenticatedGameDesignPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      responseObserver.onError(
          Status.UNAUTHENTICATED
              .withDescription("Verified Game Design workload identity required")
              .asRuntimeException());
      return false;
    }
    String expected = "spiffe://firemud/ns/" + trustedNamespace + "/sa/game-design-service";
    if (expected.equals(peer.uri())
        && peer.isService("game-design-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription(
                "Only the verified same-namespace Game Design workload without end-user context is allowed")
            .asRuntimeException());
    return false;
  }
}
