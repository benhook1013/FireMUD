package net.firedevops.firemud.springcloudgateway.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayRouteReadinessHealthIndicatorTest {

  @Test
  void healthReturnsOutOfServiceWhenLocalGameplayRouteCannotUpgrade() {
    GameplayRouteReadinessHealthIndicator indicator = new GameplayRouteReadinessHealthIndicator(1);

    Health health = indicator.health();

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("DOWN", health.getDetails().get("gameplayRoute"));
  }
}
