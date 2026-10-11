package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone unregistered settlement receiver: authenticate exact World before parsing. */
public final class AccountStartSessionActivationProtectionSettlementGrpcService
    extends AccountStartSessionActivationProtectionSettlementServiceGrpc
        .AccountStartSessionActivationProtectionSettlementServiceImplBase {
  private final AccountStartSessionActivationProtectionSettlementService owner;
  private final String namespace;

  public AccountStartSessionActivationProtectionSettlementGrpcService(
      AccountStartSessionActivationProtectionSettlementService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "activation settlement owner required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void settleOriginalStartSessionActivationProtection(
      SettleOriginalStartSessionActivationProtectionRequest request,
      StreamObserver<SettleOriginalStartSessionActivationProtectionResponse> observer) {
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
            > AccountStartSessionActivationProtectionSettlementGrpcCodec.MAX_REQUEST_WIRE_BYTES) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    final AccountStartSessionActivationProtectionSettlementInput input;
    try {
      input =
          AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(
              request, namespace);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    try {
      var stored = owner.settle(input.accountProtectionId(), input.accountProtectionFence());
      var evidence =
          new AccountStartSessionActivationProtectionSettlementEvidence(
              input.settlementRequestId(), stored.terminal(), stored.settledAt());
      observer.onNext(
          AccountStartSessionActivationProtectionSettlementGrpcCodec.toResponse(input, evidence));
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
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
