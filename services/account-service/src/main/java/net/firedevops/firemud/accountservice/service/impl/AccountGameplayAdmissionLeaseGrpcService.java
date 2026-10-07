package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseState;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionAbortOwner;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionAbortOwner.AbortDeniedException;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.shared.v1.ErrorDetail;

/** Unregistered abort-only transport. Every other inherited method remains unimplemented. */
public final class AccountGameplayAdmissionLeaseGrpcService
    extends AccountGameplayAdmissionLeaseServiceGrpc.AccountGameplayAdmissionLeaseServiceImplBase {
  private final AccountGameplayAdmissionAbortOwner owner;

  public AccountGameplayAdmissionLeaseGrpcService(AccountGameplayAdmissionAbortOwner owner) {
    this.owner = Objects.requireNonNull(owner);
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
