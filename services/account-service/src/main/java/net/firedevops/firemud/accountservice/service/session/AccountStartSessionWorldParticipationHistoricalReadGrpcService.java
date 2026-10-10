package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationHistoricalReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.ReadHistoricalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Standalone unregistered same-namespace Account historical participation receiver. */
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
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
