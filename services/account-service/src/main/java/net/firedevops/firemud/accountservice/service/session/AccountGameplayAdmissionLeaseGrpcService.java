package net.firedevops.firemud.accountservice.service.session;

import com.google.protobuf.MessageLite;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ConfirmOriginalNewGameplayAdmissionRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.IssueOriginalNewGameplayCredentialRequest;
import net.firedevops.firemud.account.v1.OriginalNewAdmissionConfirmationResponse;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionLeaseOwner;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayCredentialOwner;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone, unregistered receiver for Account's NEW lease and ORIGINAL-NEW credential calls. */
public final class AccountGameplayAdmissionLeaseGrpcService
    extends AccountGameplayAdmissionLeaseServiceGrpc.AccountGameplayAdmissionLeaseServiceImplBase {
  private static final String FAILURE_DESCRIPTION =
      "Account gameplay admission lease operation denied or unavailable";

  private final AccountGameplayAdmissionLeaseOwner owner;
  private final AccountOriginalNewBoundGameplayCredentialOwner originalNewCredentialOwner;
  private final AccountGameplayAdmissionLeaseWorkloadGuard workloadGuard;

  AccountGameplayAdmissionLeaseGrpcService(
      AccountGameplayAdmissionLeaseOwner owner,
      AccountGameplayAdmissionLeaseWorkloadGuard workloadGuard) {
    this(owner, null, workloadGuard);
  }

  AccountGameplayAdmissionLeaseGrpcService(
      AccountGameplayAdmissionLeaseOwner owner,
      AccountOriginalNewBoundGameplayCredentialOwner originalNewCredentialOwner,
      AccountGameplayAdmissionLeaseWorkloadGuard workloadGuard) {
    this.owner = Objects.requireNonNull(owner, "Account gameplay admission lease owner required");
    this.originalNewCredentialOwner = originalNewCredentialOwner;
    this.workloadGuard =
        Objects.requireNonNull(workloadGuard, "Account gameplay admission lease guard required");
  }

  @Override
  public void acquireGameplayAdmissionLease(
      AcquireGameplayAdmissionLeaseRequest request,
      StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
    receive(
        request,
        observer,
        AccountGameplayAdmissionLeaseGrpcCodec.MAX_ACQUIRE_REQUEST_WIRE_BYTES,
        () -> {
          var parsed = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(request);
          workloadGuard.requireAcquisitionNamespace(parsed);
          return () -> owner.acquire(request);
        });
  }

  @Override
  public void finalizeGameplayAdmissionLease(
      FinalizeGameplayAdmissionLeaseRequest request,
      StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
    receive(
        request,
        observer,
        AccountGameplayAdmissionLeaseGrpcCodec.MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        () -> {
          var parsed = AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(request);
          workloadGuard.requireOriginalLeaseNamespace(parsed);
          return () -> owner.finalizeLease(request);
        });
  }

  @Override
  public void readGameplayAdmissionLease(
      ReadGameplayAdmissionLeaseRequest request,
      StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
    receive(
        request,
        observer,
        AccountGameplayAdmissionLeaseGrpcCodec.MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        () -> {
          var parsed = AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(request);
          workloadGuard.requireOriginalLeaseNamespace(parsed);
          return () -> owner.read(request);
        });
  }

  @Override
  public void abortGameplayAdmissionLease(
      AbortGameplayAdmissionLeaseRequest request,
      StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
    receive(
        request,
        observer,
        AccountGameplayAdmissionLeaseGrpcCodec.MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        () -> {
          var parsed = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(request);
          workloadGuard.requireOriginalLeaseNamespace(parsed);
          return () -> owner.abort(request);
        });
  }

  @Override
  public void issueOriginalNewGameplayCredential(
      IssueOriginalNewGameplayCredentialRequest request,
      StreamObserver<AccountGameplayAdmissionLeaseResponse> observer) {
    receive(
        request,
        observer,
        AccountGameplayAdmissionLeaseGrpcCodec.MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES,
        () -> {
          var parsed =
              AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialRequest(
                  request);
          workloadGuard.requireOriginalNewCredentialNamespace(parsed);
          if (originalNewCredentialOwner == null) {
            throw Status.UNAVAILABLE
                .withDescription("Account original-new credential owner is unavailable")
                .asRuntimeException();
          }
          return () -> originalNewCredentialOwner.issue(parsed);
        });
  }

  @Override
  public void confirmOriginalNewGameplayAdmission(
      ConfirmOriginalNewGameplayAdmissionRequest request,
      StreamObserver<OriginalNewAdmissionConfirmationResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    try {
      workloadGuard.requireGameSessionCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > AccountGameplayAdmissionLeaseGrpcCodec.MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    final AccountGameplayAdmissionLeaseGrpcCodec.OriginalFinalAdmissionConfirmationRequest parsed;
    try {
      parsed =
          AccountGameplayAdmissionLeaseGrpcCodec.parseConfirmOriginalNewGameplayAdmissionRequest(
              request);
      workloadGuard.requireOriginalFinalAdmissionConfirmationNamespace(parsed);
      if (originalNewCredentialOwner == null) throw new IllegalStateException();
    } catch (StatusRuntimeException rejected) {
      fail(observer, rejected.getStatus());
      return;
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    }

    final AccountOriginalNewBoundGameplayCredentialOwner.OriginalAdmissionConfirmation confirmed;
    try {
      confirmed =
          originalNewCredentialOwner.confirmOriginalFinalAdmission(
              parsed.presentedInitialGsACompactJwt(), parsed.originalLeaseEvidence());
    } catch (StatusRuntimeException failure) {
      fail(observer, failure.getStatus());
      return;
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (RuntimeException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    }

    final OriginalNewAdmissionConfirmationResponse response;
    try {
      response =
          AccountGameplayAdmissionLeaseGrpcCodec.toConfirmOriginalNewGameplayAdmissionResponse(
              parsed,
              confirmed.lineageId(),
              confirmed.originalLeaseId(),
              parsed.originalLeaseEvidence().leaseFence().longValueExact(),
              confirmed.admissionAtMillis(),
              confirmed.continuityExpiresAtMillis(),
              confirmed.authenticatedConfirmationSha256());
    } catch (RuntimeException invalidOwnerResult) {
      fail(observer, Status.INTERNAL);
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private <T> void receive(
      T request,
      StreamObserver<AccountGameplayAdmissionLeaseResponse> observer,
      int maximumBytes,
      Supplier<Supplier<AccountGameplayAdmissionLeaseResponse>> validateAndCall) {
    Objects.requireNonNull(observer, "response observer is required");

    final Supplier<AccountGameplayAdmissionLeaseResponse> ownerCall;
    try {
      // This is deliberately the first request-related operation.
      workloadGuard.requireGameSessionCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }

    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    if (request == null || serializedSize(request) > maximumBytes) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }

    try {
      ownerCall = validateAndCall.get();
    } catch (StatusRuntimeException invalidOrDenied) {
      fail(observer, invalidOrDenied.getStatus());
      return;
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException malformed) {
      fail(observer, Status.INTERNAL);
      return;
    }

    final AccountGameplayAdmissionLeaseResponse response;
    try {
      response = Objects.requireNonNull(ownerCall.get(), "Account owner response required");
    } catch (StatusRuntimeException failure) {
      fail(observer, failure.getStatus());
      return;
    } catch (CannotCreateTransactionException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (DataAccessResourceFailureException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
      return;
    } catch (IllegalArgumentException ownerPrecondition) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (RuntimeException failure) {
      fail(observer, Status.INTERNAL);
      return;
    }

    // Keep observer callbacks out of the owner failure boundary. A broken observer must not
    // trigger a second terminal callback or be translated into an owner failure status.
    observer.onNext(response);
    observer.onCompleted();
  }

  private static int serializedSize(Object request) {
    return request instanceof MessageLite message ? message.getSerializedSize() : Integer.MAX_VALUE;
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) return true;
      }
    }
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription(FAILURE_DESCRIPTION)
            .asRuntimeException());
  }
}
