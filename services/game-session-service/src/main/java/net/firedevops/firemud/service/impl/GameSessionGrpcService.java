package net.firedevops.firemud.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueCommandRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueCommandResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import net.firedevops.firemud.gamesession.v1.QueryStateRequest;
import net.firedevops.firemud.gamesession.v1.QueryStateResponse;
import net.firedevops.firemud.gamesession.v1.RestartSessionRequest;
import net.firedevops.firemud.gamesession.v1.RestartSessionResponse;
import net.firedevops.firemud.gamesession.v1.StartSessionResponse;
import net.firedevops.firemud.gamesession.v1.StopSessionRequest;
import net.firedevops.firemud.gamesession.v1.StopSessionResponse;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagRequest;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagResponse;
import net.firedevops.firemud.service.FeatureFlagService;
import net.firedevops.firemud.service.GameInstanceService;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.TickService;
import org.lognet.springboot.grpc.GRpcService;

/** gRPC endpoints for the Game Session Service. */
@GRpcService
public class GameSessionGrpcService extends GameSessionServiceGrpc.GameSessionServiceImplBase {
  private final PingService pingService;
  private final GameInstanceService gameInstanceService;
  private final FeatureFlagService featureFlagService;
  private final TickService tickService;

  public GameSessionGrpcService(
      PingService pingService,
      GameInstanceService gameInstanceService,
      FeatureFlagService featureFlagService,
      TickService tickService) {
    this.pingService = pingService;
    this.gameInstanceService = gameInstanceService;
    this.featureFlagService = featureFlagService;
    this.tickService = tickService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void startSession(
      net.firedevops.firemud.gamesession.v1.StartSessionRequest request,
      StreamObserver<StartSessionResponse> responseObserver) {
    StartSessionRequest dto =
        new StartSessionRequest(Long.valueOf(request.getTenantId()), request.getVersionId(), 0L);
    GameInstanceDto instance = gameInstanceService.startSession(dto);
    StartSessionResponse response =
        StartSessionResponse.newBuilder().setSessionId(instance.id().toString()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void stopSession(
      StopSessionRequest request, StreamObserver<StopSessionResponse> responseObserver) {
    try {
      GameInstanceDto dto = gameInstanceService.stopSession(Long.parseLong(request.getSessionId()));
      StopSessionResponse response = StopSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void restartSession(
      RestartSessionRequest request, StreamObserver<RestartSessionResponse> responseObserver) {
    try {
      GameInstanceDto dto =
          gameInstanceService.restartSession(Long.parseLong(request.getSessionId()));
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void enqueueCommand(
      EnqueueCommandRequest request, StreamObserver<EnqueueCommandResponse> responseObserver) {
    tickService.enqueueCommand(Long.valueOf(request.getSessionId()), request.getCommand());
    EnqueueCommandResponse response = EnqueueCommandResponse.newBuilder().setAccepted(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void queryState(
      QueryStateRequest request, StreamObserver<QueryStateResponse> responseObserver) {
    String state = tickService.queryState(Long.valueOf(request.getSessionId()));
    QueryStateResponse response = QueryStateResponse.newBuilder().setStateJson(state).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void toggleFeatureFlag(
      ToggleFeatureFlagRequest request,
      StreamObserver<ToggleFeatureFlagResponse> responseObserver) {
    featureFlagService.toggleFlag(
        new net.firedevops.firemud.dto.ToggleFeatureFlagRequest(
            Long.valueOf(request.getTenantId()), request.getName(), request.getEnabled()));
    ToggleFeatureFlagResponse response =
        ToggleFeatureFlagResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
