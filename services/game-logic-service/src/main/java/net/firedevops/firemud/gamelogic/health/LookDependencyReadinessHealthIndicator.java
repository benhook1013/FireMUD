package net.firedevops.firemud.gamelogic.health;

import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.gamelogic.health.ResolveLookPathProbe.ProbeResult;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for the downstream services required by the LOOK path. */
@Component("lookDependencyReadiness")
public class LookDependencyReadinessHealthIndicator implements HealthIndicator {
  private static final String COMPONENT = "game-logic-service";
  private static final String CONTRACT = "ResolveLook";
  private static final String PROBE_TENANT_ID = "0";
  private static final String PROBE_GAME_INSTANCE_ID = "0";
  private static final String PROBE_ROOM_ID = "0";

  private final ResolveLookPathProbe resolveLookPathProbe;
  private final ReadinessTransitionTracker readinessTransitionTracker;

  public LookDependencyReadinessHealthIndicator(
      ResolveLookPathProbe resolveLookPathProbe,
      ReadinessTransitionTracker readinessTransitionTracker) {
    this.resolveLookPathProbe = resolveLookPathProbe;
    this.readinessTransitionTracker = readinessTransitionTracker;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    ProbeResult probeResult =
        resolveLookPathProbe.probe(PROBE_TENANT_ID, PROBE_GAME_INSTANCE_ID, PROBE_ROOM_ID);
    if (!probeResult.ready()) {
      return DependencyReadinessSupport.recordOutOfService(
          readinessTransitionTracker,
          COMPONENT,
          CONTRACT,
          probeResult.failingDependency(),
          probeResult.dependencies());
    }

    return DependencyReadinessSupport.recordUp(
        readinessTransitionTracker, COMPONENT, CONTRACT, probeResult.dependencies());
  }
}
