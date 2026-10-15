package net.firedevops.firemud.gamelogic.sourceintake;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainProtoCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamelogic.v1.GameLogicGameplayRuleIntakeServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeRequest;
import net.firedevops.firemud.gamelogic.v1.RetainGameplayRuleIntakeResponse;

/** Standalone, unregistered transport for the existing owner-local intake service. */
public final class GameLogicGameplayRuleIntakeGrpcService
    extends GameLogicGameplayRuleIntakeServiceGrpc.GameLogicGameplayRuleIntakeServiceImplBase {
  private final GameLogicGameplayRuleIntakeService owner;
  private final String namespace;

  public GameLogicGameplayRuleIntakeGrpcService(
      GameLogicGameplayRuleIntakeService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void retainGameplayRuleIntake(
      RetainGameplayRuleIntakeRequest request,
      StreamObserver<RetainGameplayRuleIntakeResponse> observer) {
    try {
      requireGameDesignPeer(namespace);
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    final GameLogicIntakeRetainEvidence.Request decoded;
    try {
      decoded = GameLogicIntakeRetainProtoCodec.fromRequest(request);
      if (!namespace.equals(decoded.targetNamespace())) {
        observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
        return;
      }
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    try {
      var terminal = owner.retain(decoded.binding());
      observer.onNext(
          GameLogicIntakeRetainProtoCodec.toResponse(
              new GameLogicIntakeRetainEvidence(decoded, terminal)));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }

  private static void requireGameDesignPeer(String namespace) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!peer.isInNamespace(namespace)
        || !peer.isService("game-design-service")
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Design workload required")
          .asRuntimeException();
    }
  }
}
