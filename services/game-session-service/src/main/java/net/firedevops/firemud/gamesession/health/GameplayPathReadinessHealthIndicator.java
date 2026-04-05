package net.firedevops.firemud.gamesession.health;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.health.GameplayLocalPathReadinessProbe.ProbeResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for the currently exposed gameplay login and first-command path. */
@Component("gameplayPathReadiness")
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public class GameplayPathReadinessHealthIndicator implements HealthIndicator {
  private static final String COMPONENT = "game-session-service";
  private static final String CONTRACT = "LOGIN->LOOK";
  private static final String PROBE_TENANT_ID = "1";
  private static final String PROBE_USERNAME = "readiness-probe@example.invalid";
  private static final String PROBE_PASSWORD = "invalid";
  private static final String PROBE_SESSION_ID = "1";
  private static final String PROBE_PLAYER_ID = "1";
  private static final String PROBE_GAME_INSTANCE_ID = "1";
  private static final String PROBE_ROOM_ID = "1";

  private final AccountClient accountClient;
  private final GameLogicClient gameLogicClient;
  private final GameplayLocalPathReadinessProbe gameplayLocalPathReadinessProbe;
  private final ReadinessTransitionTracker readinessTransitionTracker;

  public GameplayPathReadinessHealthIndicator(
      AccountClient accountClient,
      GameLogicClient gameLogicClient,
      GameplayLocalPathReadinessProbe gameplayLocalPathReadinessProbe,
      ReadinessTransitionTracker readinessTransitionTracker) {
    this.accountClient = accountClient;
    this.gameLogicClient = gameLogicClient;
    this.gameplayLocalPathReadinessProbe = gameplayLocalPathReadinessProbe;
    this.readinessTransitionTracker = readinessTransitionTracker;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    Map<String, Object> dependencies = new LinkedHashMap<>();

    try {
      AuthenticateResponse response =
          accountClient.authenticateForReadiness(
              PROBE_TENANT_ID, PROBE_USERNAME, PROBE_PASSWORD, "");
      String outcome = response.hasError() ? response.getError().getCode() : "AUTHENTICATED";
      if (AuthenticationErrorCodes.UPSTREAM_FAILURE.equals(outcome)) {
        dependencies.put(
            "accountService",
            DependencyReadinessSupport.downDependency(
                "authenticate",
                "grpc:AccountService#Authenticate",
                "Authentication service unavailable"));
        return DependencyReadinessSupport.recordOutOfService(
            readinessTransitionTracker, COMPONENT, CONTRACT, "accountService", dependencies);
      }
      dependencies.put(
          "accountService",
          DependencyReadinessSupport.upDependency(
              "authenticate",
              "grpc:AccountService#Authenticate",
              DependencyReadinessSupport.normalizeOutcome(outcome)));
    } catch (RuntimeException ex) {
      dependencies.put(
          "accountService",
          DependencyReadinessSupport.downDependency(
              "authenticate",
              "grpc:AccountService#Authenticate",
              DependencyReadinessSupport.message(ex)));
      return DependencyReadinessSupport.recordOutOfService(
          readinessTransitionTracker, COMPONENT, CONTRACT, "accountService", dependencies);
    }

    ProbeResult sessionContextProbe = gameplayLocalPathReadinessProbe.probeSessionContextStore();
    if (!sessionContextProbe.ready()) {
      dependencies.put(
          "sessionContextStore",
          DependencyReadinessSupport.downDependency(
              "roundTrip", "redis:session-context", sessionContextProbe.detail()));
      return DependencyReadinessSupport.recordOutOfService(
          readinessTransitionTracker, COMPONENT, CONTRACT, "sessionContextStore", dependencies);
    }
    dependencies.put(
        "sessionContextStore",
        DependencyReadinessSupport.upDependency(
            "roundTrip", "redis:session-context", sessionContextProbe.detail()));

    ProbeResult commandQueueProbe = gameplayLocalPathReadinessProbe.probeCommandQueueStore();
    if (!commandQueueProbe.ready()) {
      dependencies.put(
          "commandQueueStore",
          DependencyReadinessSupport.downDependency(
              "roundTrip", "redis:tick-queue", commandQueueProbe.detail()));
      return DependencyReadinessSupport.recordOutOfService(
          readinessTransitionTracker, COMPONENT, CONTRACT, "commandQueueStore", dependencies);
    }
    dependencies.put(
        "commandQueueStore",
        DependencyReadinessSupport.upDependency(
            "roundTrip", "redis:tick-queue", commandQueueProbe.detail()));

    try {
      gameLogicClient.resolveLookForReadiness(
          PROBE_TENANT_ID,
          PROBE_SESSION_ID,
          PROBE_PLAYER_ID,
          PROBE_GAME_INSTANCE_ID,
          PROBE_ROOM_ID);
      dependencies.put(
          "gameLogicService",
          DependencyReadinessSupport.upDependency(
              "resolveLook", "grpc:GameLogicService#ResolveLook", "OK"));
    } catch (StatusRuntimeException ex) {
      if (isReachableAppStatus(ex.getStatus().getCode())) {
        dependencies.put(
            "gameLogicService",
            DependencyReadinessSupport.upDependency(
                "resolveLook",
                "grpc:GameLogicService#ResolveLook",
                DependencyReadinessSupport.normalizeOutcome(ex.getStatus().getCode().name())));
      } else {
        dependencies.put(
            "gameLogicService",
            DependencyReadinessSupport.downDependency(
                "resolveLook",
                "grpc:GameLogicService#ResolveLook",
                DependencyReadinessSupport.message(ex)));
        return DependencyReadinessSupport.recordOutOfService(
            readinessTransitionTracker, COMPONENT, CONTRACT, "gameLogicService", dependencies);
      }
    } catch (RuntimeException ex) {
      dependencies.put(
          "gameLogicService",
          DependencyReadinessSupport.downDependency(
              "resolveLook",
              "grpc:GameLogicService#ResolveLook",
              DependencyReadinessSupport.message(ex)));
      return DependencyReadinessSupport.recordOutOfService(
          readinessTransitionTracker, COMPONENT, CONTRACT, "gameLogicService", dependencies);
    }

    return DependencyReadinessSupport.recordUp(
        readinessTransitionTracker, COMPONENT, CONTRACT, dependencies);
  }

  private static boolean isReachableAppStatus(Status.Code code) {
    return code == Status.Code.INVALID_ARGUMENT || code == Status.Code.NOT_FOUND;
  }
}
