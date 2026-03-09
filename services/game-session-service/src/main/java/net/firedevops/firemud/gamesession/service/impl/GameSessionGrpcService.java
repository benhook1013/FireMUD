package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueCommandRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueCommandResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetTickStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetTickStatusResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksResponse;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import net.firedevops.firemud.gamesession.v1.QueryStateRequest;
import net.firedevops.firemud.gamesession.v1.QueryStateResponse;
import net.firedevops.firemud.gamesession.v1.RestartSessionRequest;
import net.firedevops.firemud.gamesession.v1.RestartSessionResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksResponse;
import net.firedevops.firemud.gamesession.v1.StartSessionResponse;
import net.firedevops.firemud.gamesession.v1.StopSessionRequest;
import net.firedevops.firemud.gamesession.v1.StopSessionResponse;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagRequest;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagResponse;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

/** gRPC endpoints for the Game Session Service. */
@GRpcService
public final class GameSessionGrpcService
    extends GameSessionServiceGrpc.GameSessionServiceImplBase {
  private final PingService pingService;
  private final GameInstanceService gameInstanceService;
  private final FeatureFlagService featureFlagService;
  private final TextCommandInterpreter textCommandInterpreter;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "TickService is injected and not exposed")
  private final TickService tickService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  private final IpConnectionLimiter ipConnectionLimiter;

  public GameSessionGrpcService(
      PingService pingService,
      GameInstanceService gameInstanceService,
      FeatureFlagService featureFlagService,
      TextCommandInterpreter textCommandInterpreter,
      TickService tickService,
      MeterRegistry meterRegistry,
      IpConnectionLimiter ipConnectionLimiter) {
    this.pingService = pingService;
    this.gameInstanceService = gameInstanceService;
    this.featureFlagService = featureFlagService;
    this.textCommandInterpreter = textCommandInterpreter;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
    this.ipConnectionLimiter = ipConnectionLimiter;
  }

  private ErrorDetail error(String code, String message) {
    meterRegistry.counter("grpc.app_error", "code", code).increment();
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  @Override
  @Timed(value = "gamesessionGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response =
        PingResponse.newBuilder()
            .setMessage(msg)
            .setError(ErrorDetail.newBuilder().setCode("OK").setMessage(msg))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamesessionGrpc.startSession")
  public void startSession(
      net.firedevops.firemud.gamesession.v1.StartSessionRequest request,
      StreamObserver<StartSessionResponse> responseObserver) {
    try {
      String clientIp = request.getClientIp();
      if (clientIp != null && !clientIp.isBlank() && !ipConnectionLimiter.canAccept(clientIp)) {
        StartSessionResponse response =
            StartSessionResponse.newBuilder()
                .setError(error("CONNECTION_LIMIT", "Too many connections from IP"))
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
        return;
      }
      StartSessionRequest dto =
          new StartSessionRequest(
              Long.valueOf(request.getTenantId()),
              request.getRuntimeVersion(),
              request.getScriptPatchVersion(),
              0L);
      GameInstanceDto instance = gameInstanceService.startSession(dto);
      if (clientIp != null && !clientIp.isBlank()) {
        ipConnectionLimiter.register(clientIp, instance.id());
      }
      StartSessionResponse response =
          StartSessionResponse.newBuilder().setSessionId(instance.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      StartSessionResponse response =
          StartSessionResponse.newBuilder()
              .setError(error("INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.stopSession")
  public void stopSession(
      StopSessionRequest request, StreamObserver<StopSessionResponse> responseObserver) {
    try {
      long sessionId = Long.parseLong(request.getSessionId());
      gameInstanceService.stopSession(sessionId);
      ipConnectionLimiter.release(sessionId);
      StopSessionResponse response = StopSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      StopSessionResponse response =
          StopSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(error("NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.restartSession")
  public void restartSession(
      RestartSessionRequest request, StreamObserver<RestartSessionResponse> responseObserver) {
    try {
      gameInstanceService.restartSession(Long.parseLong(request.getSessionId()));
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(error("NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.enqueueCommand")
  public void enqueueCommand(
      EnqueueCommandRequest request, StreamObserver<EnqueueCommandResponse> responseObserver) {
    TextCommandInterpretationResult interpretation =
        textCommandInterpreter.interpret(
            request.getSessionId(), request.getCommand(), request.getRequiresSoloTick());
    var commandResult = interpretation.commandResult();
    EnqueueCommandResponse.Builder builder =
        EnqueueCommandResponse.newBuilder().setAccepted(commandResult.accepted());
    if (commandResult.hasError()) {
      builder.setError(error(commandResult.errorCode(), commandResult.errorMessage()));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamesessionGrpc.queryState")
  public void queryState(
      QueryStateRequest request, StreamObserver<QueryStateResponse> responseObserver) {
    try {
      String state = tickService.queryState(Long.valueOf(request.getSessionId()));
      QueryStateResponse response = QueryStateResponse.newBuilder().setStateJson(state).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      QueryStateResponse response =
          QueryStateResponse.newBuilder().setError(error("NOT_FOUND", ex.getMessage())).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.toggleFeatureFlag")
  public void toggleFeatureFlag(
      ToggleFeatureFlagRequest request,
      StreamObserver<ToggleFeatureFlagResponse> responseObserver) {
    try {
      featureFlagService.toggleFlag(
          new net.firedevops.firemud.gamesession.dto.ToggleFeatureFlagRequest(
              Long.valueOf(request.getTenantId()), request.getName(), request.getEnabled()));
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(error("INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.pauseTicks")
  public void pauseTicks(
      PauseTicksRequest request, StreamObserver<PauseTicksResponse> responseObserver) {
    tickService.pauseTicks(request.getReason());
    PauseTicksResponse response = PauseTicksResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamesessionGrpc.resumeTicks")
  public void resumeTicks(
      ResumeTicksRequest request, StreamObserver<ResumeTicksResponse> responseObserver) {
    tickService.resumeTicks(request.getReason());
    ResumeTicksResponse response = ResumeTicksResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamesessionGrpc.getTickStatus")
  public void getTickStatus(
      GetTickStatusRequest request, StreamObserver<GetTickStatusResponse> responseObserver) {
    TickStatus status = tickService.getTickStatus();
    GetTickStatusResponse response = GetTickStatusResponse.newBuilder().setStatus(status).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
