package net.firedevops.firemud.automationscripting.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.service.PingService;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusResponse;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateResponse;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptRequest;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class AutomationScriptingGrpcService
    extends AutomationScriptingServiceGrpc.AutomationScriptingServiceImplBase {
  private final PingService pingService;
  private final ScriptDefinitionService scriptService;
  private final ScriptVersionService scriptVersionService;

  public AutomationScriptingGrpcService(
      PingService pingService,
      ScriptDefinitionService scriptService,
      ScriptVersionService scriptVersionService) {
    this.pingService = pingService;
    this.scriptService = scriptService;
    this.scriptVersionService = scriptVersionService;
  }

  @Override
  @Timed(value = "automationGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    try {
      String msg = pingService.ping();
      PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "automationGrpc.triggerScriptEvent")
  public void triggerScriptEvent(
      TriggerScriptEventRequest request,
      StreamObserver<TriggerScriptEventResponse> responseObserver) {
    TriggerScriptEventResponse response =
        TriggerScriptEventResponse.newBuilder()
            .setAdmitted(false)
            .setAdmissionOutcome(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_UNSPECIFIED)
            .setAdmissionReason("not_implemented")
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("NOT_IMPLEMENTED")
                    .setMessage("TriggerScriptEvent is not implemented yet")
                    .build())
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.updateScript")
  public void updateScript(
      UpdateScriptRequest request, StreamObserver<UpdateScriptResponse> responseObserver) {
    try {
      ScriptDefinitionDto dto =
          new ScriptDefinitionDto(
              null,
              Long.parseLong(request.getTenantId()),
              request.getName(),
              request.getVersion(),
              request.getDefinition());
      scriptService.updateScript(dto);
      UpdateScriptResponse resp = UpdateScriptResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      UpdateScriptResponse resp =
          UpdateScriptResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "automationGrpc.getScriptStatus")
  public void getScriptStatus(
      GetScriptStatusRequest request, StreamObserver<GetScriptStatusResponse> responseObserver) {
    // Placeholder implementation; scripts execute asynchronously
    GetScriptStatusResponse resp =
        GetScriptStatusResponse.newBuilder().setQueued(false).setRunning(false).build();
    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.notifyScriptVersionUpdate")
  public void notifyScriptVersionUpdate(
      NotifyScriptVersionUpdateRequest request,
      StreamObserver<NotifyScriptVersionUpdateResponse> responseObserver) {
    scriptVersionService.notifyUpdate(
        request.getTenantId(), request.getScriptPatchVersion(), request.getAffectedScriptsList());
    NotifyScriptVersionUpdateResponse resp =
        NotifyScriptVersionUpdateResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }
}
