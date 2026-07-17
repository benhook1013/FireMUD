package net.firedevops.firemud.automationscripting.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateRequest;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasRequest;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GameSessionControlPlaneClient
    extends AbstractBlockingGrpcClient<
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub> {
  private static final Logger logger = LoggerFactory.getLogger(GameSessionControlPlaneClient.class);
  private static final long CALL_DEADLINE_SECONDS = 3L;

  public GameSessionControlPlaneClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, grpcClientProperties, channelFactory, stubCustomizer);
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameSessionControlPlaneServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public EnqueueAutomationCommandIfAbsentResponse enqueueAutomationCommandIfAbsent(
      EnqueueAutomationCommandIfAbsentRequest request) {
    if (stub() == null) {
      return unavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
          .enqueueAutomationCommandIfAbsent(request);
    } catch (RuntimeException ex) {
      logger.warn("Game Session enqueueAutomationCommandIfAbsent failed", ex);
      return unavailable();
    }
  }

  public GetGameInstanceRuntimeStateResponse getGameInstanceRuntimeState(
      String tenantId, String gameInstanceId) {
    return getGameInstanceRuntimeState(tenantId, gameInstanceId, "");
  }

  public GetGameInstanceRuntimeStateResponse getGameInstanceRuntimeState(
      String tenantId, String gameInstanceId, String regionId) {
    if (stub() == null) {
      return runtimeUnavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
          .getGameInstanceRuntimeState(
              GetGameInstanceRuntimeStateRequest.newBuilder()
                  .setTenantId(tenantId)
                  .setGameInstanceId(gameInstanceId)
                  .setRegionId(regionId == null ? "" : regionId)
                  .build());
    } catch (RuntimeException ex) {
      logger.warn("Game Session getGameInstanceRuntimeState failed", ex);
      return runtimeUnavailable();
    }
  }

  public ScheduleRemoteFollowupResponse scheduleRemoteFollowup(
      ScheduleRemoteFollowupRequest request) {
    if (stub() == null) {
      return remoteFollowupUnavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
          .scheduleRemoteFollowup(request);
    } catch (RuntimeException ex) {
      logger.warn("Game Session scheduleRemoteFollowup failed", ex);
      return remoteFollowupUnavailable();
    }
  }

  public GetGameplayCommandStatusResponse getGameplayCommandStatus(
      String tenantId, String gameInstanceId, String commandId) {
    if (stub() == null) {
      return gameplayCommandUnavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
          .getGameplayCommandStatus(
              GetGameplayCommandStatusRequest.newBuilder()
                  .setTenantId(tenantId)
                  .setGameInstanceId(gameInstanceId == null ? "" : gameInstanceId)
                  .setCommandId(commandId == null ? "" : commandId)
                  .build());
    } catch (RuntimeException ex) {
      logger.warn("Game Session getGameplayCommandStatus failed", ex);
      return gameplayCommandUnavailable();
    }
  }

  public ValidateBuiltInCommandAliasResponse validateBuiltInCommandAlias(String alias) {
    if (stub() == null) {
      return aliasUnavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
          .validateBuiltInCommandAlias(
              ValidateBuiltInCommandAliasRequest.newBuilder().setAlias(alias).build());
    } catch (RuntimeException ex) {
      logger.warn("Game Session validateBuiltInCommandAlias failed", ex);
      return aliasUnavailable();
    }
  }

  private static EnqueueAutomationCommandIfAbsentResponse unavailable() {
    return EnqueueAutomationCommandIfAbsentResponse.newBuilder()
        .setAccepted(false)
        .setAdmissionOutcome("GAME_SESSION_UNAVAILABLE")
        .setError(
            ErrorDetail.newBuilder()
                .setCode("GAME_SESSION_UNAVAILABLE")
                .setMessage("Game Session service unavailable"))
        .build();
  }

  private static GetGameInstanceRuntimeStateResponse runtimeUnavailable() {
    return GetGameInstanceRuntimeStateResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("GAME_SESSION_UNAVAILABLE")
                .setMessage("Game Session service unavailable"))
        .build();
  }

  private static ValidateBuiltInCommandAliasResponse aliasUnavailable() {
    return ValidateBuiltInCommandAliasResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("GAME_SESSION_UNAVAILABLE")
                .setMessage("Game Session service unavailable"))
        .build();
  }

  private static ScheduleRemoteFollowupResponse remoteFollowupUnavailable() {
    return ScheduleRemoteFollowupResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("GAME_SESSION_UNAVAILABLE")
                .setMessage("Game Session service unavailable"))
        .build();
  }

  private static GetGameplayCommandStatusResponse gameplayCommandUnavailable() {
    return GetGameplayCommandStatusResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("GAME_SESSION_UNAVAILABLE")
                .setMessage("Game Session service unavailable"))
        .build();
  }
}
