package net.firedevops.firemud.gamelogic.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;
import net.firedevops.firemud.gamelogic.logic.service.CommandService;
import net.firedevops.firemud.gamelogic.service.LookAggregationService;
import net.firedevops.firemud.gamelogic.service.MoveAggregationService;
import net.firedevops.firemud.gamelogic.service.PingService;
import net.firedevops.firemud.gamelogic.service.SayAggregationService;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayRequest;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayResponse;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandRequest;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandResponse;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC endpoints for the Game Logic Service. */
@GrpcService
public class GameLogicGrpcService extends GameLogicServiceGrpc.GameLogicServiceImplBase {
  private final PingService pingService;
  private final CommandService commandService;
  private final LookAggregationService lookAggregationService;
  private final SayAggregationService sayAggregationService;
  private final MoveAggregationService moveAggregationService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  public GameLogicGrpcService(
      PingService pingService,
      CommandService commandService,
      LookAggregationService lookAggregationService,
      SayAggregationService sayAggregationService,
      MoveAggregationService moveAggregationService,
      MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.commandService = commandService;
    this.lookAggregationService = lookAggregationService;
    this.sayAggregationService = sayAggregationService;
    this.moveAggregationService = moveAggregationService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "gamelogicGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    PingResponse response = PingResponse.newBuilder().setMessage(pingService.ping()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.executeCommand")
  public void executeCommand(
      ExecuteCommandRequest request, StreamObserver<ExecuteCommandResponse> responseObserver) {
    CommandResult result = commandService.handleCommand(request.getCommand());
    ExecuteCommandResponse.Builder builder =
        ExecuteCommandResponse.newBuilder().setResult(result.result());
    if (result.error() != null) {
      builder.setError(
          GrpcAppErrors.error(meterRegistry, result.error().code(), result.error().message()));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.resolveLook")
  public void resolveLook(LookRequest request, StreamObserver<LookResult> responseObserver) {
    try {
      LookResult result = lookAggregationService.resolve(request);
      responseObserver.onNext(result);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException ex) {
      responseObserver.onError(ex);
    } catch (Exception ex) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "gamelogicGrpc.broadcastSay")
  public void broadcastSay(
      BroadcastSayRequest request, StreamObserver<BroadcastSayResponse> responseObserver) {
    BroadcastSayResponse response = sayAggregationService.broadcast(request);
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.resolveMove")
  public void resolveMove(MoveRequest request, StreamObserver<MoveResult> responseObserver) {
    MoveResult response = moveAggregationService.resolve(request);
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
