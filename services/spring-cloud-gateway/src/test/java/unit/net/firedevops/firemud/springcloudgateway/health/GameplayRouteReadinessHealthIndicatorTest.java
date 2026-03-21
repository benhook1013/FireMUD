package net.firedevops.firemud.springcloudgateway.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayRouteReadinessHealthIndicatorTest {

  @Test
  void healthReturnsOutOfServiceWhenLocalGameplayRouteCannotUpgrade() {
    GameplayRouteReadinessHealthIndicator indicator = new GameplayRouteReadinessHealthIndicator(1);

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("DOWN", dependencies.get("gameplayRoute").get("status"));
  }
}
