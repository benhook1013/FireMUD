package net.firedevops.firemud.springcloudgateway.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayRouteReadinessHealthIndicatorTest {

  @Test
  void healthReturnsOutOfServiceWhenLocalGameplayRouteCannotUpgrade() {
    GameplayRouteReadinessHealthIndicator indicator =
        new GameplayRouteReadinessHealthIndicator(1, tracker());

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    assertEquals("DOWN", dependencies.get("gameplayRoute").get("status"));
  }

  private static ReadinessTransitionTracker tracker() {
    return new ReadinessTransitionTracker(new SimpleMeterRegistry());
  }
}
