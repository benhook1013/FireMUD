package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseState;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionAbortOwner;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionAbortOwner.AbortDeniedException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionReadOwner;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.shared.v1.ErrorDetail;

/** Unregistered negative-state read and abort transport; positive admission methods stay absent. */
public final class AccountGameplayAdmissionLeaseGrpcService
    extends AccountGameplayAdmissionLeaseServiceGrpc.AccountGameplayAdmissionLeaseServiceImplBase {
  private final AccountGameplayAdmissionAbortOwner owner;
  private final AccountGameplayAdmissionReadOwner readOwner;

  public AccountGameplayAdmissionLeaseGrpcService(
      AccountGameplayAdmissionAbortOwner owner, AccountGameplayAdmissionReadOwner readOwner) {
    this.owner = Objects.requireNonNull(owner);
    this.readOwner = Objects.requireNonNull(readOwner);
  }

  @Override
  public void readGameplayAdmissionLease(
      ReadGameplayAdmissionLeaseRequest request,
      StreamObserver<ReadGameplayAdmissionLeaseResponse> observer) {
    ReadGameplayAdmissionLeaseResponse response;
    try {
      var read = readOwner.read(request);
      if (read.state() == State.COMMITTED)
        throw Status.FAILED_PRECONDITION
            .withDescription("Exact Account admission lease readback unavailable")
            .asRuntimeException();
      response =
          ReadGameplayAdmissionLeaseResponse.newBuilder()
              .setOperation(
                  AccountGameplayAdmissionLeaseWireCodec.encodeOperation(
                      read.evidence(),
                      read.state() == State.PENDING
                          ? GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_PENDING
                          : GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED,
                      read.bindingDecisionId(),
                      read.orphanCleanupId()))
              .build();
    } catch (StatusRuntimeException failure) {
      observer.onError(sanitizedReadFailure(failure));
      return;
    } catch (IllegalArgumentException failure) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Malformed Account admission lease read request")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Account admission lease read unavailable")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static StatusRuntimeException sanitizedReadFailure(StatusRuntimeException failure) {
    return switch (Status.fromThrowable(failure).getCode()) {
      case UNAUTHENTICATED ->
          Status.UNAUTHENTICATED
              .withDescription("Verified workload identity required")
              .asRuntimeException();
      case PERMISSION_DENIED ->
          Status.PERMISSION_DENIED
              .withDescription("Game Session workload required")
              .asRuntimeException();
      case INVALID_ARGUMENT ->
          Status.INVALID_ARGUMENT
              .withDescription("Malformed Account admission lease read request")
              .asRuntimeException();
      case FAILED_PRECONDITION ->
          Status.FAILED_PRECONDITION
              .withDescription("Exact Account admission lease readback unavailable")
              .asRuntimeException();
      default ->
          Status.UNAVAILABLE
              .withDescription("Account admission lease read unavailable")
              .asRuntimeException();
    };
  }

  @Override
  public void abortGameplayAdmissionLease(
      AbortGameplayAdmissionLeaseRequest request,
      StreamObserver<AbortGameplayAdmissionLeaseResponse> observer) {
    AbortGameplayAdmissionLeaseResponse response;
    try {
      var aborted = owner.abort(request);
      if (aborted.state() != State.ABORTED)
        throw new IllegalStateException("Abort result required");
      response =
          AbortGameplayAdmissionLeaseResponse.newBuilder()
              .setOperation(
                  AccountGameplayAdmissionLeaseWireCodec.encodeOperation(
                      aborted.evidence(),
                      GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED,
                      aborted.bindingDecisionId(),
                      aborted.orphanCleanupId()))
              .build();
    } catch (AbortDeniedException failure) {
      response =
          AbortGameplayAdmissionLeaseResponse.newBuilder()
              .setDenied(
                  ErrorDetail.newBuilder()
                      .setCode(failure.code())
                      .setMessage("Account admission abort denied"))
              .build();
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
      return;
    } catch (IllegalArgumentException failure) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Malformed Account admission abort request")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Account admission abort unavailable")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }
}
