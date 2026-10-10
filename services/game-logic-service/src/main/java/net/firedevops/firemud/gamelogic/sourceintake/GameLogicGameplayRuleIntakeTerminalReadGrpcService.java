package net.firedevops.firemud.gamelogic.sourceintake;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadGrpcCodec;
import net.firedevops.firemud.gamelogic.v1.GameLogicGameplayRuleIntakeTerminalReadServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.ReadGameplayRuleIntakeTerminalRequest;
import net.firedevops.firemud.gamelogic.v1.ReadGameplayRuleIntakeTerminalResponse;

/** No automatic registration; authenticate Account before parsing caller-carried evidence. */
public final class GameLogicGameplayRuleIntakeTerminalReadGrpcService
    extends GameLogicGameplayRuleIntakeTerminalReadServiceGrpc
        .GameLogicGameplayRuleIntakeTerminalReadServiceImplBase {
  private final GameLogicGameplayRuleIntakeTerminalReadService owner;
  private final String namespace;

  public GameLogicGameplayRuleIntakeTerminalReadGrpcService(
      GameLogicGameplayRuleIntakeTerminalReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readGameplayRuleIntakeTerminal(
      ReadGameplayRuleIntakeTerminalRequest request,
      StreamObserver<ReadGameplayRuleIntakeTerminalResponse> observer) {
    try {
      GameLogicGameplayRuleIntakeTerminalReadService.requirePeer(namespace);
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    final net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadEvidence.Request
        decoded;
    try {
      decoded = GameLogicIntakeTerminalReadGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    try {
      observer.onNext(GameLogicIntakeTerminalReadGrpcCodec.toResponse(owner.read(decoded)));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(failure);
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
