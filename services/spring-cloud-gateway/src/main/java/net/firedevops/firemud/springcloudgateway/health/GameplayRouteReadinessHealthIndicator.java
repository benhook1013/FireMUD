package net.firedevops.firemud.springcloudgateway.health;

import java.util.Map;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness contribution for first-party gameplay admission through the Gateway. */
@Component("gameplayRouteReadiness")
public class GameplayRouteReadinessHealthIndicator implements HealthIndicator {
  private static final String COMPONENT = "spring-cloud-gateway";
  private static final String CONTRACT = "Gateway /ws/game upgrade";
  private static final String UNAVAILABLE_REASON =
      "Canonical Account connect-context production, shared replay fencing, and dedicated Gateway "
          + "signing keys are not qualified";

  private final ReadinessTransitionTracker readinessTransitionTracker;

  public GameplayRouteReadinessHealthIndicator(
      ReadinessTransitionTracker readinessTransitionTracker) {
    this.readinessTransitionTracker = readinessTransitionTracker;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    return DependencyReadinessSupport.recordOutOfService(
        readinessTransitionTracker,
        COMPONENT,
        CONTRACT,
        "gameplayRoute",
        Map.of(
            "gameplayRoute",
            DependencyReadinessSupport.downDependency(
                "websocketUpgrade", "first-party admission", UNAVAILABLE_REASON)));
  }
}
