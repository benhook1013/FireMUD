package net.firedevops.firemud.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandRequest;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandResponse;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.logic.dto.CommandResult;
import net.firedevops.firemud.logic.service.CommandService;
import net.firedevops.firemud.service.PingService;
import org.lognet.springboot.grpc.GRpcService;

/** gRPC endpoints for the Game Logic Service. */
@GRpcService
public class GameLogicGrpcService extends GameLogicServiceGrpc.GameLogicServiceImplBase {
  private final PingService pingService;
  private final CommandService commandService;

  public GameLogicGrpcService(PingService pingService, CommandService commandService) {
    this.pingService = pingService;
    this.commandService = commandService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    PingResponse response = PingResponse.newBuilder().setMessage(pingService.ping()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void executeCommand(
      ExecuteCommandRequest request, StreamObserver<ExecuteCommandResponse> responseObserver) {
    CommandResult result = commandService.handleCommand(request.getCommand());
    if (result.error() != null) {
      StatusRuntimeException exception =
          Status.INVALID_ARGUMENT.withDescription(result.error().message()).asRuntimeException();
      responseObserver.onError(exception);
      return;
    }
    ExecuteCommandResponse response =
        ExecuteCommandResponse.newBuilder().setResult(result.result()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
