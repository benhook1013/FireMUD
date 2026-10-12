package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationHistoricalReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Standalone unregistered same-namespace Account historical tuple-to-participation receiver. */
public final class AccountStartSessionWorldParticipationHistoricalReadGrpcService
    extends AccountStartSessionWorldParticipationHistoricalReadServiceGrpc
        .AccountStartSessionWorldParticipationHistoricalReadServiceImplBase {
  private final AccountStartSessionWorldParticipationHistoricalReadService owner;
  private final String workloadNamespace;

  public AccountStartSessionWorldParticipationHistoricalReadGrpcService(
      AccountStartSessionWorldParticipationHistoricalReadService owner, String workloadNamespace) {
    this.owner = Objects.requireNonNull(owner, "historical participation owner is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Resolves retained coordinates only after authenticating World without end-user context. */
  @Override
  public void readHistoricalStartSessionWorldParticipation(
      ReadHistoricalStartSessionWorldParticipationRequest request,
      StreamObserver<ReadHistoricalStartSessionWorldParticipationResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (request == null) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    // Bind the raw selector before decoding any other caller-controlled request fields.
    if (!workloadNamespace.equals(request.getTargetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }

    final net.firedevops.firemud.common.account.startsession
            .AccountStartSessionWorldParticipationHistoricalReadRequest
        decoded;
    try {
      decoded = AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    try {
      var evidence = owner.read(decoded);
      observer.onNext(
          AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toResponse(
              decoded, evidence));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
    } catch (IllegalArgumentException | IllegalStateException mismatch) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (CannotCreateTransactionException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      observer.onError(
          (hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL)
              .asRuntimeException());
    } catch (RuntimeException failure) {
      observer.onError(Status.INTERNAL.asRuntimeException());
    }
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
          return true;
        }
      }
    }
    return false;
  }
}
