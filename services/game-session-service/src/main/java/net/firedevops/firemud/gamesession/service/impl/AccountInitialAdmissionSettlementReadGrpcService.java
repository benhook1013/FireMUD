package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.gamesession.service.AccountInitialAdmissionSettlementReadService;
import net.firedevops.firemud.gamesession.v1.AccountInitialAdmissionSettlementReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementRequest;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementResponse;
import org.jooq.exception.TooManyRowsException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Dedicated unregistered Account read; the separate World-only service is unchanged. */
public final class AccountInitialAdmissionSettlementReadGrpcService
    extends AccountInitialAdmissionSettlementReadServiceGrpc
        .AccountInitialAdmissionSettlementReadServiceImplBase {
  private final AccountInitialAdmissionSettlementReadService owner;
  private final AccountInitialAdmissionSettlementReadWorkloadGuard guard;
  private final String namespace;

  public AccountInitialAdmissionSettlementReadGrpcService(
      AccountInitialAdmissionSettlementReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.namespace = namespace;
    this.guard = new AccountInitialAdmissionSettlementReadWorkloadGuard(namespace);
  }

  @Override
  public void getAccountInitialAdmissionSettlement(
      GetAccountInitialAdmissionSettlementRequest request,
      StreamObserver<GetAccountInitialAdmissionSettlementResponse> observer) {
    Objects.requireNonNull(observer, "observer");
    try {
      guard.requireAccountCaller();
    } catch (AdminAuthorizationException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Settlement caller is not authorized")
              .asRuntimeException());
      return;
    }
    AccountInitialAdmissionSettlementReadInput input;
    try {
      if (request != null && request.getTargetNamespaceCount() == 1) {
        guard.requireAccountCaller(request.getTargetNamespace(0));
      }
      input = AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(request, namespace);
      guard.requireAccountCaller(input.targetNamespace());
    } catch (AdminAuthorizationException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Settlement caller is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Settlement request is malformed")
              .asRuntimeException());
      return;
    }
    try {
      var evidence = owner.read(input);
      var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
      AccountInitialAdmissionSettlementReadGrpcCodec.fromResponse(input, response);
      observer.onNext(response);
      observer.onCompleted();
    } catch (TooManyRowsException unproved) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact protected terminal is unavailable")
              .withCause(unproved)
              .asRuntimeException());
    } catch (CannotCreateTransactionException
        | DataAccessResourceFailureException
        | TransientDataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Settlement owner is unavailable")
              .withCause(unavailable)
              .asRuntimeException());
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException storageFailure) {
      if (hasTransientSqlState(storageFailure)) {
        observer.onError(
            Status.UNAVAILABLE
                .withDescription("Settlement owner is unavailable")
                .withCause(storageFailure)
                .asRuntimeException());
      } else {
        observer.onError(
            Status.INTERNAL
                .withDescription("Settlement owner read failed")
                .withCause(storageFailure)
                .asRuntimeException());
      }
    } catch (IllegalArgumentException | IllegalStateException unproved) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact protected terminal is unavailable")
              .withCause(unproved)
              .asRuntimeException());
    } catch (RuntimeException unexpected) {
      observer.onError(
          Status.INTERNAL
              .withDescription("Settlement owner read failed")
              .withCause(unexpected)
              .asRuntimeException());
    }
  }

  private static boolean hasTransientSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        if (sqlState != null
            && (sqlState.startsWith("08")
                || sqlState.startsWith("40")
                || sqlState.startsWith("57")
                || sqlState.equals("55P03"))) {
          return true;
        }
      }
    }
    return false;
  }
}
