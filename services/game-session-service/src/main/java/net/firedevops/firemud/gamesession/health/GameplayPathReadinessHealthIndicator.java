package net.firedevops.firemud.gamesession.health;

import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for the currently exposed gameplay login and first-command path. */
@Component("gameplayPathReadiness")
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public class GameplayPathReadinessHealthIndicator implements HealthIndicator {
  private final AccountClient accountClient;
  private final GameLogicClient gameLogicClient;

  public GameplayPathReadinessHealthIndicator(
      AccountClient accountClient, GameLogicClient gameLogicClient) {
    this.accountClient = accountClient;
    this.gameLogicClient = gameLogicClient;
  }

  @Override
  public Health health() {
    try {
      accountClient.ping();
    } catch (RuntimeException ex) {
      return Health.outOfService()
          .withDetail("accountService", "DOWN")
          .withDetail("reason", message(ex))
          .build();
    }

    try {
      gameLogicClient.ping();
    } catch (RuntimeException ex) {
      return Health.outOfService()
          .withDetail("accountService", "UP")
          .withDetail("gameLogicService", "DOWN")
          .withDetail("reason", message(ex))
          .build();
    }

    return Health.up()
        .withDetail("accountService", "UP")
        .withDetail("gameLogicService", "UP")
        .build();
  }

  private static String message(RuntimeException ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }
}
