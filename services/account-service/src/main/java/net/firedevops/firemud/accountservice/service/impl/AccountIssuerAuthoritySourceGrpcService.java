package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceServiceGrpc;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ExactIssuerAuthorityEventReadback;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.account.v1.ReadExactIssuerAuthorityEventRequest;
import net.firedevops.firemud.accountservice.repository.AccountIssuerSourceSnapshotReadOwner;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec;

/** Unregistered adapter for the protected Account issuer-source owner read. */
public final class AccountIssuerAuthoritySourceGrpcService
    extends AccountIssuerAuthoritySourceServiceGrpc.AccountIssuerAuthoritySourceServiceImplBase {
  private final AccountIssuerSourceSnapshotReadOwner owner;

  public AccountIssuerAuthoritySourceGrpcService(AccountIssuerSourceSnapshotReadOwner owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public void readCurrentIssuerAuthoritySource(
      ReadCurrentIssuerAuthoritySourceRequest request,
      StreamObserver<AccountIssuerAuthoritySourceSnapshot> observer) {
    respond(
        () -> owner.read(request),
        result ->
            AccountIssuerSourceSnapshotGrpcCodec.toResponse(
                result.evidence(), result.request(), result.authenticatedCallerWorkload()),
        observer);
  }

  @Override
  public void readExactIssuerAuthorityEvent(
      ReadExactIssuerAuthorityEventRequest request,
      StreamObserver<ExactIssuerAuthorityEventReadback> observer) {
    respond(
        () -> owner.readExactEvent(request),
        result ->
            AccountIssuerSourceSnapshotGrpcCodec.toExactEventResponse(
                result.readback().currentSnapshot(),
                result.readback().selectedEvent(),
                result.request(),
                result.authenticatedCallerWorkload()),
        observer);
  }

  private static <T, R> void respond(
      Supplier<T> readOwner, Function<T, R> encode, StreamObserver<R> observer) {
    final T result;
    try {
      result = readOwner.get();
    } catch (StatusRuntimeException failure) {
      observer.onError(sanitize(failure));
      return;
    } catch (IllegalArgumentException failure) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Malformed Account issuer source request")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Account issuer source is temporarily unavailable")
              .asRuntimeException());
      return;
    }

    final R response;
    try {
      response = encode.apply(result);
    } catch (RuntimeException invalidSource) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Account issuer source snapshot is unavailable or inconsistent")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static StatusRuntimeException sanitize(StatusRuntimeException failure) {
    return switch (Status.fromThrowable(failure).getCode()) {
      case UNAUTHENTICATED ->
          Status.UNAUTHENTICATED
              .withDescription("Verified Game Session workload identity required")
              .asRuntimeException();
      case PERMISSION_DENIED ->
          Status.PERMISSION_DENIED
              .withDescription("Same-namespace Game Session workload required")
              .asRuntimeException();
      case INVALID_ARGUMENT ->
          Status.INVALID_ARGUMENT
              .withDescription("Malformed Account issuer source request")
              .asRuntimeException();
      case FAILED_PRECONDITION ->
          Status.FAILED_PRECONDITION
              .withDescription("Account issuer source snapshot is unavailable or conflicts")
              .asRuntimeException();
      default ->
          Status.UNAVAILABLE
              .withDescription("Account issuer source is temporarily unavailable")
              .asRuntimeException();
    };
  }
}
