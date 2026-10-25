package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository.Result;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone World transport adapter; deliberately absent from default service registration. */
public final class CanonicalInitialPlayerLocationGrpcService
    extends CanonicalInitialPlayerLocationServiceGrpc
        .CanonicalInitialPlayerLocationServiceImplBase {
  private final String trustedNamespace;
  private final CanonicalInitialPlayerLocationService service;

  public CanonicalInitialPlayerLocationGrpcService(
      String trustedNamespace, CanonicalInitialPlayerLocationService service) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException(
          "World workload namespace must be one canonical DNS label");
    }
    this.trustedNamespace = trustedNamespace;
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public void placeInitialPlayer(
      CanonicalInitialPlayerLocationRequest request,
      StreamObserver<CanonicalInitialPlayerLocationResponse> responseObserver) {
    if (!requirePeer(responseObserver)) return;
    CanonicalInitialPlayerLocationGrpcCodec.Selection selection;
    try {
      selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete closed placement selector is required");
      return;
    }
    final CanonicalInitialPlayerLocationResponse response;
    try {
      Result result = service.place(selection);
      response = CanonicalInitialPlayerLocationGrpcCodec.toResponse(selection, result);
    } catch (StatusRuntimeException rejected) {
      reportOwnerFailure(
          responseObserver, rejected, "World initial-player placement is unavailable");
      return;
    } catch (WorldCanonicalInitialPlayerLocationRepository.OperationBindingConflictException
        | WorldCanonicalInitialPlayerLocationRepository.InvalidInitialLocationEvidenceException
        | WorldCanonicalInitialPlayerLocationRepository.InvalidInitialLocationReadbackException
        | WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException rejected) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Exact placement owner evidence is unavailable");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.UNAVAILABLE, "World initial-player placement is unavailable");
      return;
    }

    try {
      responseObserver.onNext(response);
    } catch (RuntimeException observerFailure) {
      responseObserver.onError(observerFailure);
      return;
    }
    responseObserver.onCompleted();
  }

  @Override
  public void readInitialPlayerLocation(
      CanonicalInitialPlayerLocationRequest request,
      StreamObserver<CanonicalInitialPlayerLocationResponse> responseObserver) {
    if (!requirePeer(responseObserver)) return;
    CanonicalInitialPlayerLocationGrpcCodec.Selection selection;
    try {
      selection = CanonicalInitialPlayerLocationGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete closed placement selector is required");
      return;
    }
    final Optional<CanonicalInitialPlayerLocationResponse> response;
    try {
      response =
          service
              .read(selection)
              .map(
                  retained ->
                      CanonicalInitialPlayerLocationGrpcCodec.toResponse(selection, retained));
    } catch (StatusRuntimeException rejected) {
      reportOwnerFailure(
          responseObserver, rejected, "World initial-player location read is unavailable");
      return;
    } catch (WorldCanonicalInitialPlayerLocationRepository.OperationBindingConflictException
        | WorldCanonicalInitialPlayerLocationRepository.InvalidInitialLocationEvidenceException
        | WorldCanonicalInitialPlayerLocationRepository.InvalidInitialLocationReadbackException
        | WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException rejected) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Exact retained placement evidence is unavailable");
      return;
    } catch (RuntimeException failure) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "World initial-player location read is unavailable");
      return;
    }

    if (response.isEmpty()) {
      fail(
          responseObserver,
          Status.NOT_FOUND,
          "No exact retained initial placement matches the selector");
      return;
    }

    try {
      responseObserver.onNext(response.orElseThrow());
    } catch (RuntimeException observerFailure) {
      responseObserver.onError(observerFailure);
      return;
    }
    responseObserver.onCompleted();
  }

  private boolean requirePeer(StreamObserver<?> responseObserver) {
    if (CanonicalInitialPlayerLocationPeerInterceptor.currentVerifiedPeer() == null) {
      fail(
          responseObserver,
          Status.UNAUTHENTICATED,
          "Exact same-namespace Game Session peer required");
      return false;
    }
    try {
      CanonicalInitialPlayerLocationPeerInterceptor.requireVerifiedPeer(trustedNamespace);
    } catch (StatusRuntimeException rejected) {
      responseObserver.onError(rejected);
      return false;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "World owner operation requires independent SQL");
      return false;
    }
    return true;
  }

  private static void fail(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }

  private static void reportOwnerFailure(
      StreamObserver<?> observer, StatusRuntimeException rejected, String unavailableDescription) {
    Status.Code code = Status.fromThrowable(rejected).getCode();
    if (code == Status.Code.FAILED_PRECONDITION || code == Status.Code.NOT_FOUND) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "Exact World initial-placement owner evidence is unavailable");
    } else {
      fail(observer, Status.UNAVAILABLE, unavailableDescription);
    }
  }
}
