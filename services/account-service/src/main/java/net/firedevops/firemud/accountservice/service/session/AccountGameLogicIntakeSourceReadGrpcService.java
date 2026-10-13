package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeSourceReadServiceGrpc;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadProtoCodec;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Standalone GD-only confirmation; no runtime registration. */
public final class AccountGameLogicIntakeSourceReadGrpcService
    extends AccountGameLogicIntakeSourceReadServiceGrpc
        .AccountGameLogicIntakeSourceReadServiceImplBase {
  private final AccountGameLogicIntakeSourceReadService owner;
  private final String namespace;

  public AccountGameLogicIntakeSourceReadGrpcService(
      AccountGameLogicIntakeSourceReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readSourceScope(
      GameLogicIntakeSourcePermissionRequest request,
      StreamObserver<GameLogicIntakeSourcePermissionResponse> observer) {
    read(request, observer, true);
  }

  @Override
  public void readFinalizedIntake(
      GameLogicIntakeSourcePermissionRequest request,
      StreamObserver<GameLogicIntakeSourcePermissionResponse> observer) {
    read(request, observer, false);
  }

  private void read(
      GameLogicIntakeSourcePermissionRequest request,
      StreamObserver<GameLogicIntakeSourcePermissionResponse> observer,
      boolean preliminary) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    if (!("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    final net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadEvidence.Request decoded;
    try {
      decoded = GameLogicIntakeSourceReadProtoCodec.fromRequest(request);
      if (preliminary != (decoded.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary))
        throw new IllegalArgumentException("Wrong proof method");
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    if (!namespace.equals(decoded.targetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    try {
      if (decoded.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary proof) {
        var result =
            owner.readSourceScope(proof.scope(), decoded.intendedReader(), decoded.purpose());
        if (!proof.scope().equals(result))
          throw new IllegalStateException("Changed Account source scope");
      } else {
        var proof = (GameplayRuleSourceReadEvidence.Finalized) decoded.proof();
        var result =
            owner.readFinalizedIntake(
                proof.authorization(), decoded.intendedReader(), decoded.purpose());
        if (!java.util.Arrays.equals(
            proof.authorization().canonicalBytes(), result.canonicalBytes()))
          throw new IllegalStateException("Changed Account final authorization");
      }
      observer.onNext(GameLogicIntakeSourceReadProtoCodec.toResponse(decoded));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
