package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone, unregistered Account receiver for authenticated historical World settlement. */
public final class AccountStartSessionActivationProtectionSettlementGrpcService
    extends AccountStartSessionActivationProtectionSettlementServiceGrpc
        .AccountStartSessionActivationProtectionSettlementServiceImplBase {
  private final AccountStartSessionActivationProtectionSettlementService owner;
  private final String workloadNamespace;

  public AccountStartSessionActivationProtectionSettlementGrpcService(
      AccountStartSessionActivationProtectionSettlementService owner, String workloadNamespace) {
    this.owner = Objects.requireNonNull(owner, "activation settlement owner is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void settleOriginalStartSessionActivationProtection(
      SettleOriginalStartSessionActivationProtectionRequest request,
      StreamObserver<SettleOriginalStartSessionActivationProtectionResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    ActivationProtectionPeers.WorldPeerStatus peerStatus =
        ActivationProtectionPeers.worldPeerStatus(workloadNamespace);
    if (peerStatus != ActivationProtectionPeers.WorldPeerStatus.VERIFIED) {
      observer.onError(
          (peerStatus == ActivationProtectionPeers.WorldPeerStatus.MISSING
                  ? Status.UNAUTHENTICATED
                  : Status.PERMISSION_DENIED)
              .asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
      return;
    }
    if (request == null
        || request.getTargetNamespaceCount() != 1
        || !workloadNamespace.equals(request.getTargetNamespace(0))) {
      observer.onError(
          request != null
                  && request.getTargetNamespaceCount() == 1
                  && !workloadNamespace.equals(request.getTargetNamespace(0))
              ? Status.PERMISSION_DENIED.asRuntimeException()
              : Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    final AccountStartSessionActivationProtectionSettlementInput input;
    try {
      input =
          AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(
              request, workloadNamespace);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.withCause(malformed).asRuntimeException());
      return;
    }

    try {
      var evidence = owner.settle(input);
      observer.onNext(
          AccountStartSessionActivationProtectionSettlementGrpcCodec.toResponse(input, evidence));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
    } catch (CannotCreateTransactionException unavailable) {
      observer.onError(Status.UNAVAILABLE.withCause(unavailable).asRuntimeException());
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      observer.onError(
          (hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL)
              .withCause(failure)
              .asRuntimeException());
    } catch (IllegalArgumentException | IllegalStateException mismatch) {
      observer.onError(Status.FAILED_PRECONDITION.withCause(mismatch).asRuntimeException());
    } catch (RuntimeException failure) {
      observer.onError(Status.INTERNAL.withCause(failure).asRuntimeException());
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
}
