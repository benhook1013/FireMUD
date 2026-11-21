package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldState;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationRequest;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldFixtureInitialAdmissionHoldServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manual, unregistered fixture-only adapter; never a Human hold or default runtime route. */
public final class WorldFixtureInitialAdmissionHoldGrpcService
    extends WorldFixtureInitialAdmissionHoldServiceGrpc
        .WorldFixtureInitialAdmissionHoldServiceImplBase {
  private final WorldFixtureInitialAdmissionHoldOwner owner;
  private final String namespace;

  public WorldFixtureInitialAdmissionHoldGrpcService(
      WorldFixtureInitialAdmissionHoldOwner owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void acquireFixtureInitialAdmissionHold(
      FixtureInitialAdmissionHoldOperationRequest request,
      StreamObserver<FixtureInitialAdmissionHoldOperationResponse> responseObserver) {
    handle(request, responseObserver, Operation.ACQUIRE);
  }

  @Override
  public void readFixtureInitialAdmissionHoldIdentity(
      FixtureInitialAdmissionHoldOperationRequest request,
      StreamObserver<FixtureInitialAdmissionHoldOperationResponse> responseObserver) {
    handle(request, responseObserver, Operation.IDENTITY);
  }

  @Override
  public void readFixtureInitialAdmissionHoldState(
      FixtureInitialAdmissionHoldOperationRequest request,
      StreamObserver<FixtureInitialAdmissionHoldOperationResponse> responseObserver) {
    handle(request, responseObserver, Operation.STATE);
  }

  private void handle(
      FixtureInitialAdmissionHoldOperationRequest request,
      StreamObserver<FixtureInitialAdmissionHoldOperationResponse> observer,
      Operation operation) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || GrpcPeerCertificateEvidence.current() == null) {
      observer.onError(
          Status.UNAUTHENTICATED
              .withDescription("Authenticated fixture peer required")
              .asRuntimeException());
      return;
    }
    if (!peer.isInNamespace(namespace)
        || !peer.isService("game-session-service")
        || SessionContext.hasAuthenticatedCallerContext()) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Fixture Game Session peer required")
              .asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Independent owner operation required")
              .asRuntimeException());
      return;
    }
    try {
      var lookup =
          FixtureInitialAdmissionGrpcCodec.fromWorldRequest(request, operation == Operation.STATE);
      if (!namespace.equals(lookup.request().selector().targetNamespace())) {
        observer.onError(
            Status.PERMISSION_DENIED
                .withDescription("Fixture namespace differs")
                .asRuntimeException());
        return;
      }
      owner.requireReadCorrelation(lookup.request(), lookup.readRequestId());
      HoldIdentity identity;
      WorldFixtureInitialAdmissionHoldState state = null;
      switch (operation) {
        case ACQUIRE -> identity = owner.acquire(lookup.request());
        case IDENTITY ->
            identity =
                owner
                    .readIdentity(lookup.request())
                    .orElseThrow(
                        () ->
                            Status.NOT_FOUND
                                .withDescription("Fixture hold not retained")
                                .asRuntimeException());
        case STATE -> {
          state =
              owner
                  .readState(lookup.request(), lookup.identity())
                  .orElseThrow(
                      () ->
                          Status.NOT_FOUND
                              .withDescription("Fixture hold not retained")
                              .asRuntimeException());
          identity = state.holdIdentity();
        }
        default -> throw new IllegalStateException("Unsupported fixture operation");
      }
      observer.onNext(FixtureInitialAdmissionGrpcCodec.toWorldResponse(lookup, identity, state));
      observer.onCompleted();
    } catch (io.grpc.StatusRuntimeException status) {
      observer.onError(status);
    } catch (SecurityException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Fixed fixture recipient differs")
              .asRuntimeException());
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Invalid fixture owner request")
              .asRuntimeException());
    } catch (DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Fixture owner storage unavailable")
              .asRuntimeException());
    } catch (IllegalStateException refused) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Fixture owner evidence unavailable")
              .asRuntimeException());
    }
  }

  private enum Operation {
    ACQUIRE,
    IDENTITY,
    STATE
  }
}
