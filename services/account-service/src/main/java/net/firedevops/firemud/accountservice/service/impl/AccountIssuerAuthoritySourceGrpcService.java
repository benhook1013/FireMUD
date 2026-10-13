package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceServiceGrpc;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
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
    final AccountIssuerSourceSnapshotReadOwner.ReadResult result;
    try {
      result = owner.read(request);
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

    final AccountIssuerAuthoritySourceSnapshot response;
    try {
      response =
          AccountIssuerSourceSnapshotGrpcCodec.toResponse(
              result.evidence(), result.request(), result.authenticatedCallerWorkload());
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
