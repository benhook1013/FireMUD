package net.firedevops.firemud.gamesession.health;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
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
  private static final String CONTRACT = "LOGIN->LOOK";
  private static final String PROBE_TENANT_ID = "0";
  private static final String PROBE_USERNAME = "readiness-probe@example.invalid";
  private static final String PROBE_PASSWORD = "invalid";
  private static final String PROBE_SESSION_ID = "readiness-probe-session";
  private static final String PROBE_PLAYER_ID = "readiness-probe-player";
  private static final String PROBE_ROOM_ID = "0";

  private final AccountClient accountClient;
  private final GameLogicClient gameLogicClient;

  public GameplayPathReadinessHealthIndicator(
      AccountClient accountClient, GameLogicClient gameLogicClient) {
    this.accountClient = accountClient;
    this.gameLogicClient = gameLogicClient;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    Map<String, Object> dependencies = new LinkedHashMap<>();

    try {
      AuthenticateResponse response =
          accountClient.authenticate(PROBE_TENANT_ID, PROBE_USERNAME, PROBE_PASSWORD, "");
      String outcome = response.hasError() ? response.getError().getCode() : "AUTHENTICATED";
      if (AuthenticationErrorCodes.UPSTREAM_FAILURE.equals(outcome)) {
        dependencies.put(
            "accountService",
            DependencyReadinessSupport.downDependency(
                "authenticate",
                "grpc:AccountService#Authenticate",
                "Authentication service unavailable"));
        return DependencyReadinessSupport.outOfService(CONTRACT, "accountService", dependencies);
      }
      dependencies.put(
          "accountService",
          DependencyReadinessSupport.upDependency(
              "authenticate", "grpc:AccountService#Authenticate", normalizeOutcome(outcome)));
    } catch (RuntimeException ex) {
      dependencies.put(
          "accountService",
          DependencyReadinessSupport.downDependency(
              "authenticate", "grpc:AccountService#Authenticate", message(ex)));
      return DependencyReadinessSupport.outOfService(CONTRACT, "accountService", dependencies);
    }

    try {
      gameLogicClient.resolveLook(
          PROBE_TENANT_ID, PROBE_SESSION_ID, PROBE_PLAYER_ID, PROBE_ROOM_ID);
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
                normalizeOutcome(ex.getStatus().getCode().name())));
      } else {
        dependencies.put(
            "gameLogicService",
            DependencyReadinessSupport.downDependency(
                "resolveLook", "grpc:GameLogicService#ResolveLook", message(ex)));
        return DependencyReadinessSupport.outOfService(CONTRACT, "gameLogicService", dependencies);
      }
    } catch (RuntimeException ex) {
      dependencies.put(
          "gameLogicService",
          DependencyReadinessSupport.downDependency(
              "resolveLook", "grpc:GameLogicService#ResolveLook", message(ex)));
      return DependencyReadinessSupport.outOfService(CONTRACT, "gameLogicService", dependencies);
    }

    return DependencyReadinessSupport.up(CONTRACT, dependencies);
  }

  private static boolean isReachableAppStatus(Status.Code code) {
    return code == Status.Code.INVALID_ARGUMENT || code == Status.Code.NOT_FOUND;
  }

  private static String normalizeOutcome(String outcome) {
    return outcome == null || outcome.isBlank() ? "OK" : outcome;
  }

  private static String message(RuntimeException ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }
}
