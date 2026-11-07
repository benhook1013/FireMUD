package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec.Held;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivatePreparationProtectionRepository;
import net.firedevops.firemud.gamesession.security.PrivatePreparationProtectionWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.AcquirePrivatePreparationProtectionRequest;
import net.firedevops.firemud.gamesession.v1.PrivatePreparationProtectionResponse;
import net.firedevops.firemud.gamesession.v1.PrivatePreparationProtectionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePreparationProtectionRequest;

/** Manually composed pending source owner only. No actor authority, release, or default route. */
public final class PrivatePreparationProtectionGrpcService
    extends PrivatePreparationProtectionServiceGrpc.PrivatePreparationProtectionServiceImplBase {
  private final GameSessionPrivatePreparationProtectionRepository owner;
  private final PrivatePreparationProtectionWorkloadGuard guard;
  private final String namespace;

  public PrivatePreparationProtectionGrpcService(
      GameSessionPrivatePreparationProtectionRepository owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    guard = new PrivatePreparationProtectionWorkloadGuard(namespace);
  }

  @Override
  public void acquirePrivatePreparationProtection(
      AcquirePrivatePreparationProtectionRequest wire,
      StreamObserver<PrivatePreparationProtectionResponse> observer) {
    serve(
        observer,
        () -> {
          var request = PrivatePreparationProtectionGrpcCodec.parse(wire);
          if (!namespace.equals(request.expectedSource().snapshot().scope().targetNamespace()))
            throw Status.PERMISSION_DENIED.asRuntimeException();
          var retained = owner.acquire(request.requestId(), request.expectedSource().snapshot());
          var held =
              new Held(
                  retained.requestId(),
                  retained.holdId(),
                  retained.fence(),
                  retained.sourceEvidence());
          return PrivatePreparationProtectionGrpcCodec.response(request, held);
        });
  }

  @Override
  public void readCurrentPrivatePreparationProtection(
      ReadCurrentPrivatePreparationProtectionRequest wire,
      StreamObserver<PrivatePreparationProtectionResponse> observer) {
    serve(
        observer,
        () -> {
          var request = PrivatePreparationProtectionGrpcCodec.parse(wire);
          var retained = owner.readCurrent(request.requestId(), request.holdId(), request.fence());
          if (!namespace.equals(retained.source().scope().targetNamespace()))
            throw Status.PERMISSION_DENIED.asRuntimeException();
          return PrivatePreparationProtectionGrpcCodec.response(
              request,
              new Held(
                  retained.requestId(),
                  retained.holdId(),
                  retained.fence(),
                  retained.sourceEvidence()));
        });
  }

  private void serve(
      StreamObserver<PrivatePreparationProtectionResponse> observer,
      Supplier<PrivatePreparationProtectionResponse> action) {
    Objects.requireNonNull(observer);
    try {
      guard.requireWorldCaller();
      observer.onNext(action.get());
      observer.onCompleted();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
    } catch (RuntimeException failure) {
      boolean unavailable = false;
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof java.sql.SQLException sql
            && sql.getSQLState() != null
            && (sql.getSQLState().startsWith("08") || sql.getSQLState().equals("40001")))
          unavailable = true;
      }
      fail(observer, unavailable ? Status.UNAVAILABLE : Status.FAILED_PRECONDITION);
    }
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription("Private preparation protection unavailable")
            .asRuntimeException());
  }
}
