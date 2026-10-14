package net.firedevops.firemud.gamesession.health;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.HttpEndpointAvailabilityChecker;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.health.GameplayLocalPathReadinessProbe.ProbeResult;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for the currently exposed gameplay login and first-command path. */
@Component("gameplayPathReadiness")
public class GameplayPathReadinessHealthIndicator implements HealthIndicator {
  private static final String COMPONENT = "game-session-service";
  private static final String CONTRACT = "LOGIN->LOOK";
  private static final String PROBE_EMAIL = "readiness-probe@example.invalid";
  private static final String PROBE_PASSWORD = "invalid";

  private final AccountClient accountClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Configuration properties are a framework-managed singleton")
  private final GameLogicProperties gameLogicProperties;

  private final HttpEndpointAvailabilityChecker httpEndpointAvailabilityChecker;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected gRPC clients are framework-managed singletons and only stored")
  private final GameLogicClient gameLogicClient;

  private final GameplayLocalPathReadinessProbe gameplayLocalPathReadinessProbe;
  private final ReadinessTransitionTracker readinessTransitionTracker;

  public GameplayPathReadinessHealthIndicator(
      AccountClient accountClient,
      GameLogicClient gameLogicClient,
      GameplayLocalPathReadinessProbe gameplayLocalPathReadinessProbe,
      ReadinessTransitionTracker readinessTransitionTracker,
      GameLogicProperties gameLogicProperties,
      HttpEndpointAvailabilityChecker httpEndpointAvailabilityChecker) {
    this.accountClient = accountClient;
    this.gameLogicProperties = gameLogicProperties;
    this.httpEndpointAvailabilityChecker = httpEndpointAvailabilityChecker;
    this.gameLogicClient = gameLogicClient;
    this.gameplayLocalPathReadinessProbe = gameplayLocalPathReadinessProbe;
    this.readinessTransitionTracker = readinessTransitionTracker;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    Map<String, Object> dependencies = new LinkedHashMap<>();

    try {
      AuthenticateResponse response =
          accountClient.authenticateForReadiness(PROBE_EMAIL, PROBE_PASSWORD);
      String outcome = response.hasError() ? response.getError().getCode() : "AUTHENTICATED";
      if (AuthenticationErrorCodes.UNAVAILABLE.equals(outcome)) {
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
      var ping = gameLogicClient.pingForReadiness();
      if (ping == null || ping.hasError() || !"pong".equals(ping.getMessage())) {
        throw new IllegalStateException("Game Logic authenticated Ping did not return pong");
      }
      URI endpoint = gameLogicProperties.readinessEndpoint();
      if (endpoint == null || !httpEndpointAvailabilityChecker.isHealthy(endpoint)) {
        throw new IllegalStateException(
            "Game Logic dependency readiness unavailable or unconfigured");
      }
      dependencies.put(
          "gameLogicService",
          DependencyReadinessSupport.upDependency(
              "pingAndReadiness", "grpc:GameLogicService#Ping+http:actuator/readiness", "OK"));
    } catch (RuntimeException ex) {
      dependencies.put(
          "gameLogicService",
          DependencyReadinessSupport.downDependency(
              "pingAndReadiness",
              "grpc:GameLogicService#Ping+http:actuator/readiness",
              DependencyReadinessSupport.message(ex)));
      return DependencyReadinessSupport.recordOutOfService(
          readinessTransitionTracker, COMPONENT, CONTRACT, "gameLogicService", dependencies);
    }

    return DependencyReadinessSupport.recordUp(
        readinessTransitionTracker, COMPONENT, CONTRACT, dependencies);
  }
}
