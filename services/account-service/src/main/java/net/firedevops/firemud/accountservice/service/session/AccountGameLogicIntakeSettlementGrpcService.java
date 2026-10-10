package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeRequest;
import net.firedevops.firemud.account.v1.SettleGameLogicIntakeResponse;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementProtoCodec;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered GD-only transport; Account alone reads GL terminal and owns settlement. */
public final class AccountGameLogicIntakeSettlementGrpcService
    extends AccountGameLogicIntakeSettlementServiceGrpc
        .AccountGameLogicIntakeSettlementServiceImplBase {
  private final AccountGameLogicIntakeSettlementService owner;
  private final String namespace;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected owner service is an intentionally shared transport collaborator.")
  public AccountGameLogicIntakeSettlementGrpcService(
      AccountGameLogicIntakeSettlementService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
  }

  @Override
  public void settleGameLogicIntake(
      SettleGameLogicIntakeRequest wire, StreamObserver<SettleGameLogicIntakeResponse> observer) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      deny(observer, Status.UNAUTHENTICATED);
      return;
    }
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      deny(observer, Status.FAILED_PRECONDITION);
      return;
    }
    final AccountGameLogicIntakeSettlementReadEvidence.Request request;
    try {
      request = AccountGameLogicIntakeSettlementProtoCodec.fromRequest(wire);
    } catch (RuntimeException malformed) {
      deny(observer, Status.INVALID_ARGUMENT);
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }
    final SettleGameLogicIntakeResponse response;
    try {
      var receipt = owner.settle(request.binding());
      response =
          AccountGameLogicIntakeSettlementProtoCodec.toResponse(
              new AccountGameLogicIntakeSettlementReadEvidence(
                  request, new AccountGameLogicIntakeSettlementEvidence(receipt.terminal())));
    } catch (StatusRuntimeException failure) {
      deny(observer, Status.fromCode(failure.getStatus().getCode()));
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
            .withDescription("Account intake settlement denied or unavailable")
            .asRuntimeException());
  }
}
