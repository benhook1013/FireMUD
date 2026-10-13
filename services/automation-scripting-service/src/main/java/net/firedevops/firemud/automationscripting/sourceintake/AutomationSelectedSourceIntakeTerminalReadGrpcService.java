package net.firedevops.firemud.automationscripting.sourceintake;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.v1.AutomationSelectedSourceIntakeTerminalReadServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.automationscripting.v1.ReadSelectedSourceIntakeTerminalResponse;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadGrpcCodec;

/** No automatic registration; authenticate Account before parsing caller-carried evidence. */
public final class AutomationSelectedSourceIntakeTerminalReadGrpcService
    extends AutomationSelectedSourceIntakeTerminalReadServiceGrpc
        .AutomationSelectedSourceIntakeTerminalReadServiceImplBase {
  private final AutomationEmptySelectedSourceIntakeTerminalReadService owner;

  public AutomationSelectedSourceIntakeTerminalReadGrpcService(
      AutomationEmptySelectedSourceIntakeTerminalReadService owner) {
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

    final net.firedevops.firemud.common.automation.sourceintake
            .AutomationSelectedSourceIntakeTerminalReadEvidence.Request
        decoded;
    try {
      decoded = AutomationSelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }
    try {
      var evidence = owner.read(decoded);
      observer.onNext(AutomationSelectedSourceIntakeTerminalReadGrpcCodec.toResponse(evidence));
      observer.onCompleted();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
    } catch (RuntimeException invalid) {
      observer.onError(Status.DATA_LOSS.asRuntimeException());
    }
  }
}
