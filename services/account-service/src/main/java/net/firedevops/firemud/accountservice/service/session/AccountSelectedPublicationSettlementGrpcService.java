package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationSettlementGrpcCodec;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationSettlementGrpcCodec.Request;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone unregistered authenticated ingress for exact selected-publication settlement. */
public final class AccountSelectedPublicationSettlementGrpcService
    extends AccountSelectedPublicationSettlementServiceGrpc
        .AccountSelectedPublicationSettlementServiceImplBase {
  private final AccountSelectedDraftPublicationSettlementService owner;
  private final String namespace;

  public AccountSelectedPublicationSettlementGrpcService(
      AccountSelectedDraftPublicationSettlementService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "selected-publication settlement owner required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void settleSelectedPublication(
      SettleSelectedPublicationRequest wire,
      StreamObserver<SettleSelectedPublicationResponse> observer) {
    Objects.requireNonNull(observer, "response observer required");
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      deny(observer, Status.UNAUTHENTICATED);
      return;
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      deny(observer, Status.FAILED_PRECONDITION);
      return;
    }
    if (wire == null
        || wire.getSerializedSize()
            > AccountSelectedPublicationSettlementGrpcCodec.MAX_MESSAGE_BYTES) {
      deny(observer, Status.INVALID_ARGUMENT);
      return;
    }

    final Request request;
    try {
      request = AccountSelectedPublicationSettlementGrpcCodec.fromRequest(wire);
    } catch (RuntimeException malformed) {
      deny(observer, Status.INVALID_ARGUMENT);
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }

    try {
      byte[] receipt = owner.settle(request.originalOperation());
      observer.onNext(AccountSelectedPublicationSettlementGrpcCodec.toResponse(request, receipt));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      Status.Code code = Status.fromThrowable(failure).getCode();
      if (code != Status.Code.UNAUTHENTICATED
          && code != Status.Code.PERMISSION_DENIED
          && code != Status.Code.INVALID_ARGUMENT
          && code != Status.Code.FAILED_PRECONDITION) {
        code = Status.Code.UNAVAILABLE;
      }
      deny(observer, Status.fromCode(code));
    } catch (IllegalArgumentException invalidEvidence) {
      deny(observer, Status.FAILED_PRECONDITION);
    } catch (RuntimeException unavailable) {
      deny(observer, Status.UNAVAILABLE);
    }
  }

  private static void deny(StreamObserver<?> observer, Status status) {
    observer.onError(
        status
            .withDescription("Account selected-publication settlement denied or unavailable")
            .asRuntimeException());
  }
}
