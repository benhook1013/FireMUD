package net.firedevops.firemud.springcloudgateway.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayRouteReadinessHealthIndicatorTest {

  @Test
  void firstPartyReadinessRemainsDownUntilCanonicalAdmissionPrerequisitesAreQualified() {
    GameplayRouteReadinessHealthIndicator indicator =
        new GameplayRouteReadinessHealthIndicator(tracker());

    Health health = indicator.health();
    Object rawDependencies = Objects.requireNonNull(health.getDetails().get("dependencies"));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) rawDependencies;

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    assertEquals("gameplayRoute", health.getDetails().get("failingDependency"));
    Map<String, Object> gameplayRoute = Objects.requireNonNull(dependencies.get("gameplayRoute"));
    assertEquals("DOWN", gameplayRoute.get("status"));
    assertEquals("websocketUpgrade", gameplayRoute.get("check"));
    assertEquals("first-party admission", gameplayRoute.get("target"));
    assertEquals(
        "Canonical Account connect-context production, shared replay fencing, and dedicated "
            + "Gateway signing keys are not qualified",
        gameplayRoute.get("reason"));
  }

  private static ReadinessTransitionTracker tracker() {
    return new ReadinessTransitionTracker(new SimpleMeterRegistry());
  }
}
