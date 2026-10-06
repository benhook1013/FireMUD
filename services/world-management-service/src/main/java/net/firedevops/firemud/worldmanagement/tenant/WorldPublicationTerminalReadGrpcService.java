package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublicationTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalReadServiceGrpc;
import org.jooq.exception.DataAccessException;

/** Standalone authenticated exact owner read; deliberately not registered as a runtime RPC. */
public final class WorldPublicationTerminalReadGrpcService
    extends WorldPublicationTerminalReadServiceGrpc.WorldPublicationTerminalReadServiceImplBase {
  private final WorldPublicationTerminalRepository repository;
  private final String trustedNamespace;

  public WorldPublicationTerminalReadGrpcService(
      WorldPublicationTerminalRepository repository, String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readWorldPublicationTerminal(
      ReadWorldPublicationTerminalRequest request,
      StreamObserver<ReadWorldPublicationTerminalResponse> responseObserver) {
    if (!requireAuthenticatedAccountPeer(responseObserver)) return;

    WorldPublicationTerminalReadEvidence.Request readRequest;
    try {
      readRequest = WorldPublicationTerminalReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("A complete canonical World publication terminal read is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(readRequest.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("World terminal namespace must match the authenticated peer")
              .asRuntimeException());
      return;
    }

    Optional<GameDesignPublicationTerminalEvidence> stored;
    try {
      stored =
          repository.readCommitted(
              WorldPublicationTerminal.Request.fromStored(
                  readRequest.expectedTerminalEvidenceBytes()));
    } catch (WorldPublicationTerminalRepository.PublicationTerminalConflictException conflict) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World terminal differs from exact committed owner evidence")
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
          Status.INTERNAL.withDescription("World terminal owner read failed").asRuntimeException());
      return;
    }

    ReadResult result;
    if (stored.isEmpty()) {
      result =
          new ReadResult(
              readRequest, WorldPublicationTerminalReadEvidence.Status.UNKNOWN, Optional.empty());
    } else {
      GameDesignPublicationTerminalEvidence evidence = stored.orElseThrow();
      WorldPublicationTerminalReadEvidence.Status status =
          evidence.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
              ? WorldPublicationTerminalReadEvidence.Status.PUBLISHED
              : WorldPublicationTerminalReadEvidence.Status.ABORTED;
      result = new ReadResult(readRequest, status, Optional.of(evidence));
    }

    try {
      responseObserver.onNext(
          WorldPublicationTerminalReadGrpcCodec.toResponse(readRequest, result));
      responseObserver.onCompleted();
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World terminal response differs from exact owner readback")
              .asRuntimeException());
    }
  }

  private boolean requireAuthenticatedAccountPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("account-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription(
                "Only the verified same-namespace Account workload without end-user context is allowed")
            .asRuntimeException());
    return false;
  }
}
