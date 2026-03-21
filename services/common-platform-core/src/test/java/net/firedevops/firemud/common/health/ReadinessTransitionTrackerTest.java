package net.firedevops.firemud.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

class ReadinessTransitionTrackerTest {

  @Test
  void recordsTransitionMetricOnlyWhenStateChanges() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReadinessTransitionTracker tracker = new ReadinessTransitionTracker(meterRegistry);

    Health down =
        DependencyReadinessSupport.outOfService(
            "LOGIN->LOOK",
            "accountService",
            Map.of(
                "accountService",
                DependencyReadinessSupport.downDependency(
                    "authenticate", "grpc:AccountService#Authenticate", "down")));
    Health up =
        DependencyReadinessSupport.up(
            "LOGIN->LOOK",
            Map.of(
                "accountService",
                DependencyReadinessSupport.upDependency(
                    "authenticate", "grpc:AccountService#Authenticate", "OK")));

    tracker.record("game-session-service", down);
    tracker.record("game-session-service", down);
    tracker.record("game-session-service", up);

    assertEquals("safe for new traffic on LOGIN->LOOK", up.getDetails().get("admissionMeaning"));

    assertEquals(
        1.0,
        meterRegistry
            .get("firemud.readiness.transitions")
            .tag("component", "game-session-service")
            .tag("to_status", "OUT_OF_SERVICE")
            .tag("failing_dependency", "accountService")
            .counter()
            .count());
    assertEquals(
        1.0,
        meterRegistry
            .get("firemud.readiness.transitions")
            .tag("component", "game-session-service")
            .tag("to_status", "UP")
            .tag("failing_dependency", "none")
            .counter()
            .count());
    assertEquals(
        1.0,
        meterRegistry
            .get("firemud.readiness.current")
            .tag("component", "game-session-service")
            .gauge()
            .value());
  }
}
