package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Game Design-only transport; Account alone owns the durable settlement. */
public final class AccountSelectedOwnerIntakeSettlementGrpcService
    extends AccountSelectedOwnerIntakeSettlementServiceGrpc
        .AccountSelectedOwnerIntakeSettlementServiceImplBase {
  private final AccountSelectedOwnerIntakeSettlementService owner;
  private final String namespace;

  public AccountSelectedOwnerIntakeSettlementGrpcService(
      AccountSelectedOwnerIntakeSettlementService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "Account settlement owner service required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void settleSelectedOwnerIntake(
      SelectedOwnerIntakeSettlementRequest wire,
      StreamObserver<SelectedOwnerIntakeSettlementResponse> observer) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      deny(observer, Status.UNAUTHENTICATED);
      return;
    }
    String expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/game-design-service";
    if (SessionContext.hasAuthenticatedCallerContext() || !expectedPeer.equals(peer.uri())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      deny(observer, Status.FAILED_PRECONDITION);
      return;
    }

    final SelectedOwnerIntakeSettlementEvidence.Request request;
    try {
      request = SelectedOwnerIntakeSettlementGrpcCodec.fromRequest(wire);
    } catch (RuntimeException malformed) {
      deny(observer, Status.INVALID_ARGUMENT);
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }

    final SelectedOwnerIntakeSettlementResponse response;
    try {
      var receipt = owner.settle(request.authorizationBinding());
      response =
          SelectedOwnerIntakeSettlementGrpcCodec.toResponse(
              new SelectedOwnerIntakeSettlementEvidence.Result(request, receipt));
    } catch (StatusRuntimeException failure) {
      deny(observer, Status.fromCode(failure.getStatus().getCode()));
      return;
    } catch (IllegalArgumentException inconsistent) {
      deny(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (RuntimeException unavailable) {
      deny(observer, Status.UNAVAILABLE);
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static void deny(StreamObserver<?> observer, Status status) {
    observer.onError(
        status
            .withDescription("Account selected-owner settlement denied or unavailable")
            .asRuntimeException());
  }
}
