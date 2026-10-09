package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadGrpcCodec;

/** Standalone only; authenticate exact workload before parsing a caller-carried binding. */
public final class AccountGameLogicIntakeAuthorizationReadGrpcService
    extends AccountGameLogicIntakeAuthorizationReadServiceGrpc
        .AccountGameLogicIntakeAuthorizationReadServiceImplBase {
  private final AccountGameLogicIntakeAuthorizationReadService owner;
  private final String namespace;

  public AccountGameLogicIntakeAuthorizationReadGrpcService(
      AccountGameLogicIntakeAuthorizationReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readHeldGameLogicIntakeAuthorization(
      ReadHeldGameLogicIntakeAuthorizationRequest request,
      StreamObserver<ReadHeldGameLogicIntakeAuthorizationResponse> observer) {
    try {
      AccountGameLogicIntakeAuthorizationReadService.requirePeer(namespace);
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    final net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadEvidence.Request
        decoded;
    try {
      decoded = GameLogicIntakeAuthorizationReadGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    try {
      owner.requireHeld(decoded);
      observer.onNext(GameLogicIntakeAuthorizationReadGrpcCodec.toHeldResponse(decoded));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
