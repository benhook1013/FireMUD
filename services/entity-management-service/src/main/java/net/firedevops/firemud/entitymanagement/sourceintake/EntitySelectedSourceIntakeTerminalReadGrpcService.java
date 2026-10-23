package net.firedevops.firemud.entitymanagement.sourceintake;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadGrpcCodec;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeTerminalReadServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalResponse;

/** No automatic registration; authenticate Account before parsing its claimed original binding. */
public final class EntitySelectedSourceIntakeTerminalReadGrpcService
    extends EntitySelectedSourceIntakeTerminalReadServiceGrpc
        .EntitySelectedSourceIntakeTerminalReadServiceImplBase {
  private final EntityEmptySelectedSourceIntakeTerminalReadService owner;

  public EntitySelectedSourceIntakeTerminalReadGrpcService(
      EntityEmptySelectedSourceIntakeTerminalReadService owner) {
    this.owner = Objects.requireNonNull(owner, "terminal-read owner is required");
  }

  @Override
  public void readSelectedSourceIntakeTerminal(
      ReadSelectedSourceIntakeTerminalRequest request,
      StreamObserver<ReadSelectedSourceIntakeTerminalResponse> observer) {
    try {
      owner.requireAuthenticatedAccountCaller();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    final net.firedevops.firemud.common.entity.sourceintake
            .EntitySelectedSourceIntakeTerminalReadEvidence.Request
        decoded;
    try {
      decoded = EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    try {
      var evidence = owner.read(decoded);
      observer.onNext(EntitySelectedSourceIntakeTerminalReadGrpcCodec.toResponse(evidence));
      observer.onCompleted();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
    } catch (RuntimeException corrupt) {
      observer.onError(Status.DATA_LOSS.asRuntimeException());
    }
  }
}
