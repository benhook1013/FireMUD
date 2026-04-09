package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.TickService;
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
import org.springframework.grpc.server.service.GrpcService;

/** gRPC endpoints for the Game Session Service. */
@GrpcService(interceptors = AuthTokenInterceptor.class)
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected service collaborators are framework-managed and retained internally")
public final class GameSessionGrpcService
    extends GameSessionServiceGrpc.GameSessionServiceImplBase {
  private final PingService pingService;
  private final GameInstanceService gameInstanceService;
  private final FeatureFlagService featureFlagService;
  private final TextCommandInterpreter textCommandInterpreter;
  private final GameInstanceRepository gameInstanceRepository;

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
      GameInstanceRepository gameInstanceRepository,
      TickService tickService,
      MeterRegistry meterRegistry,
      IpConnectionLimiter ipConnectionLimiter) {
    this.pingService = pingService;
    this.gameInstanceService = gameInstanceService;
    this.featureFlagService = featureFlagService;
    this.textCommandInterpreter = textCommandInterpreter;
    this.gameInstanceRepository = gameInstanceRepository;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
    this.ipConnectionLimiter = ipConnectionLimiter;
  }

  @Override
  @Timed(value = "gamesessionGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response =
        PingResponse.newBuilder().setMessage(msg).setError(GrpcAppErrors.ok(msg)).build();
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
      long tenantId = Long.parseLong(request.getTenantId());
      long ownerAccountId = parseOwnerAccountId(request.getOwnerAccountId());
      requireTenantAndOwnerAccess(tenantId, ownerAccountId);
      GameInstance existingRunningSession =
          gameInstanceRepository
              .findFirstByTenantIdAndOwnerAccountIdAndStatus(tenantId, ownerAccountId, "RUNNING")
              .orElse(null);
      if (clientIp != null
          && !clientIp.isBlank()
          && !ipConnectionLimiter.canAccept(
              clientIp, existingRunningSession != null ? existingRunningSession.getId() : null)) {
        StartSessionResponse response =
            StartSessionResponse.newBuilder()
                .setError(
                    GrpcAppErrors.error(
                        meterRegistry, "CONNECTION_LIMIT", "Too many connections from IP"))
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
        return;
      }
      StartSessionRequest dto =
          new StartSessionRequest(
              tenantId,
              request.getRuntimeVersion(),
              request.getScriptPatchVersion(),
              ownerAccountId);
      GameInstanceDto instance = gameInstanceService.startSession(dto, false);
      boolean transferredRegistration = false;
      if (clientIp != null && !clientIp.isBlank()) {
        transferredRegistration =
            existingRunningSession != null
                && ipConnectionLimiter.transferRegistration(
                    clientIp, existingRunningSession.getId(), instance.id());
        if (!transferredRegistration && !ipConnectionLimiter.tryRegister(clientIp, instance.id())) {
          gameInstanceService.stopSession(instance.id());
          StartSessionResponse response =
              StartSessionResponse.newBuilder()
                  .setError(
                      GrpcAppErrors.error(
                          meterRegistry, "CONNECTION_LIMIT", "Too many connections from IP"))
                  .build();
          responseObserver.onNext(response);
          responseObserver.onCompleted();
          return;
        }
      }
      if (existingRunningSession != null) {
        if (!transferredRegistration) {
          ipConnectionLimiter.release(existingRunningSession.getId());
        }
        gameInstanceService.stopSession(existingRunningSession.getId());
      }
      StartSessionResponse response =
          StartSessionResponse.newBuilder().setSessionId(instance.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      StartSessionResponse response =
          StartSessionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      StartSessionResponse response =
          StartSessionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      StartSessionResponse response =
          StartSessionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private long parseOwnerAccountId(String ownerAccountIdText) {
    if (ownerAccountIdText == null || ownerAccountIdText.isBlank()) {
      throw new IllegalArgumentException("ownerAccountId is required");
    }
    long ownerAccountId = Long.parseLong(ownerAccountIdText);
    if (ownerAccountId <= 0) {
      throw new IllegalArgumentException("ownerAccountId must be positive");
    }
    return ownerAccountId;
  }

  private void requireTenantAndOwnerAccess(long tenantId, long ownerAccountId) {
    requireTenantAccess(tenantId);
    if (isCurrentAccount(ownerAccountId)) {
      return;
    }
    throw new AuthorizationException("Owner access required");
  }

  @Override
  @Timed(value = "gamesessionGrpc.stopSession")
  public void stopSession(
      StopSessionRequest request, StreamObserver<StopSessionResponse> responseObserver) {
    try {
      long sessionId = Long.parseLong(request.getSessionId());
      requireInstanceAccess(sessionId);
      gameInstanceService.stopSession(sessionId);
      ipConnectionLimiter.release(sessionId);
      StopSessionResponse response = StopSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      StopSessionResponse response =
          StopSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      StopSessionResponse response =
          StopSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      StopSessionResponse response =
          StopSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", ex.getMessage()))
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
      long sessionId = Long.parseLong(request.getSessionId());
      requireInstanceAccess(sessionId);
      gameInstanceService.restartSession(sessionId);
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.enqueueCommand")
  public void enqueueCommand(
      EnqueueCommandRequest request, StreamObserver<EnqueueCommandResponse> responseObserver) {
    try {
      long sessionId = Long.parseLong(request.getSessionId());
      requireInstanceAccess(sessionId);
      TextCommandInterpretationResult interpretation =
          textCommandInterpreter.interpret(
              request.getSessionId(), request.getCommand(), request.getRequiresSoloTick());
      var commandResult = interpretation.commandResult();
      EnqueueCommandResponse.Builder builder =
          EnqueueCommandResponse.newBuilder().setAccepted(commandResult.accepted());
      if (commandResult.hasError()) {
        builder.setError(
            GrpcAppErrors.error(
                meterRegistry, commandResult.errorCode(), commandResult.errorMessage()));
      }
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      EnqueueCommandResponse response =
          EnqueueCommandResponse.newBuilder()
              .setAccepted(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      EnqueueCommandResponse response =
          EnqueueCommandResponse.newBuilder()
              .setAccepted(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.queryState")
  public void queryState(
      QueryStateRequest request, StreamObserver<QueryStateResponse> responseObserver) {
    try {
      long sessionId = Long.parseLong(request.getSessionId());
      requireInstanceAccess(sessionId);
      String state = tickService.queryState(sessionId);
      QueryStateResponse response = QueryStateResponse.newBuilder().setStateJson(state).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      QueryStateResponse response =
          QueryStateResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      QueryStateResponse response =
          QueryStateResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
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
      requireTenantAccess(Long.parseLong(request.getTenantId()));
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.pauseTicks")
  public void pauseTicks(
      PauseTicksRequest request, StreamObserver<PauseTicksResponse> responseObserver) {
    try {
      requireGlobalPrivilegedRole();
      tickService.pauseTicks(request.getReason());
      PauseTicksResponse response = PauseTicksResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      PauseTicksResponse response =
          PauseTicksResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.resumeTicks")
  public void resumeTicks(
      ResumeTicksRequest request, StreamObserver<ResumeTicksResponse> responseObserver) {
    try {
      requireGlobalPrivilegedRole();
      tickService.resumeTicks(request.getReason());
      ResumeTicksResponse response = ResumeTicksResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      ResumeTicksResponse response =
          ResumeTicksResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
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

  private void requireTenantAccess(long tenantId) {
    if (SessionContext.hasTenantAccess(tenantId)) {
      return;
    }
    throw new AuthorizationException("Tenant access required");
  }

  private void requireInstanceAccess(long sessionId) {
    GameInstance instance =
        gameInstanceRepository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    if (SessionContext.hasTenantAccess(instance.getTenantId()) || isCurrentAccount(instance)) {
      return;
    }
    throw new AuthorizationException("Session ownership required");
  }

  private void requireGlobalPrivilegedRole() {
    List<String> roles = SessionContext.getGlobalRoles();
    if (roles.contains("platformAdmin") || roles.contains("moderator")) {
      return;
    }
    throw new AuthorizationException("Admin role required");
  }

  private boolean isCurrentAccount(GameInstance instance) {
    return isCurrentAccount(instance.getOwnerAccountId());
  }

  private boolean isCurrentAccount(long ownerAccountId) {
    String accountId = SessionContext.getAccountId();
    if (accountId == null || accountId.isBlank()) {
      return false;
    }
    try {
      return Long.parseLong(accountId) == ownerAccountId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private static final class AuthorizationException extends RuntimeException {
    private AuthorizationException(String message) {
      super(message);
    }
  }
}
