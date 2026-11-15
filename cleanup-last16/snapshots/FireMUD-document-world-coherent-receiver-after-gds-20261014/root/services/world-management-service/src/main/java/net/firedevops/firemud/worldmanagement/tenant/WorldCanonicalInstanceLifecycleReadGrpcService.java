package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstanceLifecycleReadServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact authenticated lifecycle read; explicitly gated, never default-enabled. */
public final class WorldCanonicalInstanceLifecycleReadGrpcService
    extends WorldCanonicalInstanceLifecycleReadServiceGrpc
        .WorldCanonicalInstanceLifecycleReadServiceImplBase {
  private final WorldCanonicalInstanceLifecycleReadRepository repository;
  private final String trustedNamespace;

  public WorldCanonicalInstanceLifecycleReadGrpcService(
      WorldCanonicalInstanceLifecycleReadRepository repository,
      @Value("${firemud.grpc.workload-namespace:}") String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readWorldCanonicalInstanceLifecycle(
      ReadWorldCanonicalInstanceLifecycleRequest request,
      StreamObserver<ReadWorldCanonicalInstanceLifecycleResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "Canonical World lifecycle read requires an independent owner operation")
              .asRuntimeException());
      return;
    }

    WorldCanonicalInstanceLifecycleEvidence.Request readRequest;
    try {
      readRequest = WorldCanonicalInstanceLifecycleGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("A complete canonical World lifecycle read request is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(readRequest.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("World lifecycle namespace must match the authenticated peer")
              .asRuntimeException());
      return;
    }

    WorldCanonicalInstanceLifecycleEvidence evidence;
    try {
      evidence = repository.read(readRequest).orElse(null);
    } catch (
        WorldCanonicalInstanceLifecycleReadRepository.InvalidLifecycleEvidenceException
            inconsistent) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical World lifecycle source evidence is inconsistent")
              .asRuntimeException());
      return;
    } catch (TransientDataAccessException | DataAccessException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Canonical World lifecycle storage is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Canonical World lifecycle read failed")
              .asRuntimeException());
      return;
    }
    if (evidence == null) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No exact materialized canonical World instance matches the request")
              .asRuntimeException());
      return;
    }

    ReadWorldCanonicalInstanceLifecycleResponse response;
    try {
      response = WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(readRequest, evidence);
    } catch (IllegalArgumentException | IllegalStateException invalidEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical World lifecycle response evidence is inconsistent")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean requireAuthenticatedGameSessionPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("game-session-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription(
                "Only the verified same-namespace Game Session workload without end-user context is allowed")
            .asRuntimeException());
    return false;
  }
}
