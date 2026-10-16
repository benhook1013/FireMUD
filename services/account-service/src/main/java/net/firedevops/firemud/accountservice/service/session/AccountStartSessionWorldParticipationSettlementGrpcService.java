package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.RetainedEvidenceException;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone unregistered receiver for exact historical World participation settlement. */
public final class AccountStartSessionWorldParticipationSettlementGrpcService
    extends AccountStartSessionWorldParticipationSettlementServiceGrpc
        .AccountStartSessionWorldParticipationSettlementServiceImplBase {
  private final AccountStartSessionWorldParticipationSettlementService owner;
  private final String namespace;

  public AccountStartSessionWorldParticipationSettlementGrpcService(
      AccountStartSessionWorldParticipationSettlementService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "World participation settlement owner required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void settleOriginalStartSessionWorldParticipation(
      SettleOriginalStartSessionWorldParticipationRequest request,
      StreamObserver<SettleOriginalStartSessionWorldParticipationResponse> observer) {
    Objects.requireNonNull(observer, "response observer required");
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    String expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/world-management-service";
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !expectedPeer.equals(peer.uri())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > AccountStartSessionWorldParticipationSettlementGrpcCodec.MAX_REQUEST_WIRE_BYTES) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    final AccountStartSessionWorldParticipationSettlementInput input;
    try {
      input =
          AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(request, namespace);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    try {
      var stored =
          owner.settle(input.accountWorldParticipationId(), input.accountWorldParticipationFence());
      WorldStartSessionExecutionTerminal terminal =
          WorldStartSessionExecutionTerminal.fromStored(stored.terminalBytes());
      if (!stored.participationId().equals(input.accountWorldParticipationId())
          || !terminal.accountWorldParticipationId().equals(input.accountWorldParticipationId())
          || terminal.accountWorldParticipationFence() != input.accountWorldParticipationFence()
          || !namespace.equals(terminal.targetNamespace())
          || !terminal.digest().equals(stored.terminalDigest())
          || terminal.outcome() != stored.outcome()
          || terminal.worldExecutionFence() != stored.worldExecutionFence()) {
        throw new IllegalArgumentException("Stored terminal settlement evidence is inconsistent");
      }
      var evidence =
          new AccountStartSessionWorldParticipationSettlementEvidence(
              input.settlementRequestId(), terminal, stored.settledAt().toInstant());
      observer.onNext(
          AccountStartSessionWorldParticipationSettlementGrpcCodec.toResponse(input, evidence));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      Status.Code code = Status.fromThrowable(failure).getCode();
      if (code != Status.Code.UNAUTHENTICATED
          && code != Status.Code.PERMISSION_DENIED
          && code != Status.Code.INVALID_ARGUMENT
          && code != Status.Code.FAILED_PRECONDITION) {
        code = Status.Code.UNAVAILABLE;
      }
      observer.onError(Status.fromCode(code).asRuntimeException());
    } catch (IllegalArgumentException invalidEvidence) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RetainedEvidenceException invalidRetainedEvidence) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
