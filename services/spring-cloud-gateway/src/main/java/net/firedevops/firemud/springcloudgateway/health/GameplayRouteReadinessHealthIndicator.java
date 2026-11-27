package net.firedevops.firemud.springcloudgateway.health;

import java.util.Map;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
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
  private final PreV1GateWaivers waivers;

  public GameplayRouteReadinessHealthIndicator(
      ReadinessTransitionTracker readinessTransitionTracker, PreV1GateWaivers waivers) {
    this.readinessTransitionTracker = readinessTransitionTracker;
    this.waivers = waivers;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    if (waivers.enabled()) {
      // Pre-v1 waiver (ADR 0185): the qualification above is owed before v1.
      return DependencyReadinessSupport.recordUp(
          readinessTransitionTracker,
          COMPONENT,
          CONTRACT,
          Map.of(
              "gameplayRoute",
              DependencyReadinessSupport.upDependency(
                  "websocketUpgrade", "first-party admission", "PRE_V1_WAIVED")));
    }
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
