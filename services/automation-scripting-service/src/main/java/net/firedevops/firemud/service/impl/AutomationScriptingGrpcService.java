package net.firedevops.firemud.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusResponse;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateResponse;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptRequest;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptResponse;
import net.firedevops.firemud.dto.ScriptDefinitionDto;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.ScriptDefinitionService;
import net.firedevops.firemud.service.ScriptVersionService;
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
  public void getScriptStatus(
      GetScriptStatusRequest request, StreamObserver<GetScriptStatusResponse> responseObserver) {
    // Placeholder implementation; scripts execute asynchronously
    GetScriptStatusResponse resp =
        GetScriptStatusResponse.newBuilder().setQueued(false).setRunning(false).build();
    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }

  @Override
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
