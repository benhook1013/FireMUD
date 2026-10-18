package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.GameDesignGameplayRuleSourceReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceResponse;

/** Standalone endpoint; authenticate before parsing any source selectors. */
public final class GameDesignGameplayRuleSourceReadGrpcService
    extends GameDesignGameplayRuleSourceReadServiceGrpc
        .GameDesignGameplayRuleSourceReadServiceImplBase {
  private final GameDesignGameplayRuleSourceReadService owner;
  private final String namespace;

  public GameDesignGameplayRuleSourceReadGrpcService(
      GameDesignGameplayRuleSourceReadService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readSelectedGameplayRuleSource(
      ReadSelectedGameplayRuleSourceRequest request,
      StreamObserver<ReadSelectedGameplayRuleSourceResponse> observer) {
    try {
      GameDesignGameplayRuleSourceReadService.requirePeer(namespace);
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    final net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence.Request decoded;
    try {
      decoded = GameplayRuleSourceReadGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    try {
      var result = owner.read(decoded);
      observer.onNext(GameplayRuleSourceReadGrpcCodec.toResponse(result));
      observer.onCompleted();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Complete synchronized rule source unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
