package net.firedevops.firemud.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class AutomationScriptingGrpcService
    extends AutomationScriptingServiceGrpc.AutomationScriptingServiceImplBase {
  private final PingService pingService;

  public AutomationScriptingGrpcService(PingService pingService) {
    this.pingService = pingService;
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
}
