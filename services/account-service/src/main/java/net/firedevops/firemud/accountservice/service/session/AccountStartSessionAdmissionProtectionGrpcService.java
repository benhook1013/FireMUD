package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone, unregistered receiver for the existing Account admission-protection acquirer. */
public final class AccountStartSessionAdmissionProtectionGrpcService
    extends AccountStartSessionAdmissionProtectionServiceGrpc
        .AccountStartSessionAdmissionProtectionServiceImplBase {
  private final AccountStartSessionAdmissionProtectionAcquisitionService owner;
  private final AccountStartSessionAdmissionProtectionWorkloadGuard workloadGuard;

  AccountStartSessionAdmissionProtectionGrpcService(
      AccountStartSessionAdmissionProtectionAcquisitionService owner,
      AccountStartSessionAdmissionProtectionWorkloadGuard workloadGuard) {
    this.owner = Objects.requireNonNull(owner, "Account protection acquisition owner required");
    this.workloadGuard =
        Objects.requireNonNull(workloadGuard, "Account protection workload guard required");
  }

  @Override
  public void acquireStartSessionAdmissionProtection(
      AcquireStartSessionAdmissionProtectionRequest request,
      StreamObserver<AcquireStartSessionAdmissionProtectionResponse> observer) {
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
            > AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.MAX_REQUEST_WIRE_BYTES) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }

    final AccountStartSessionAdmissionProtectionRequest decoded;
    try {
      decoded = AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(request);
      workloadGuard.requireTargetNamespace(decoded);
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }

    try {
      var evidence = owner.acquire(decoded);
      observer.onNext(
          AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(decoded, evidence));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      fail(observer, failure.getStatus());
    } catch (AccountStartSessionOperatorAuthorizationService.NotFoundException missing) {
      fail(observer, Status.NOT_FOUND);
    } catch (CannotCreateTransactionException unavailable) {
      fail(observer, Status.UNAVAILABLE);
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
    } catch (IllegalArgumentException mismatch) {
      fail(observer, Status.FAILED_PRECONDITION);
    } catch (RuntimeException failure) {
      fail(observer, Status.INTERNAL);
    }
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
            .withDescription("Account admission-protection acquisition denied or unavailable")
            .asRuntimeException());
  }
}
