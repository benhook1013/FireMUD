package net.firedevops.firemud.gamelogic.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.gamelogic.health.ResolveLookPathProbe.ProbeResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class LookDependencyReadinessHealthIndicatorTest {
  private static final String PROBE_TENANT_ID = "1";
  private static final String PROBE_GAME_INSTANCE_ID = "1";
  private static final String PROBE_ROOM_ID = "R-1021";

  @Test
  void healthReturnsUpWhenLookDependenciesRespondToOperationShapedChecks() {
    ResolveLookPathProbe resolveLookPathProbe = mock(ResolveLookPathProbe.class);
    when(resolveLookPathProbe.probe(PROBE_TENANT_ID, PROBE_GAME_INSTANCE_ID, PROBE_ROOM_ID))
        .thenReturn(
            ProbeResult.up(
                Map.of(
                    "worldManagementService",
                    DependencyReadinessSupport.upDependency(
                        "getRoomSnapshot",
                        "grpc:WorldManagementService#GetRoomSnapshot",
                        "NOT_FOUND"),
                    "entityManagementService",
                    DependencyReadinessSupport.upDependency(
                        "listRoomEntities",
                        "grpc:EntityManagementService#ListRoomEntities",
                        "OK"))));
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(resolveLookPathProbe, tracker());

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>)
            Objects.requireNonNull(health.getDetails().get("dependencies"));

    assertEquals(Status.UP, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies"), health.getDetails().keySet());
    assertEquals("NOT_FOUND", dependencies.get("worldManagementService").get("outcome"));
    assertEquals("OK", dependencies.get("entityManagementService").get("outcome"));
  }

  @Test
  void healthReturnsOutOfServiceWhenWorldDependencyFails() {
    ResolveLookPathProbe resolveLookPathProbe = mock(ResolveLookPathProbe.class);
    Map<String, Object> probeDependencies = new LinkedHashMap<>();
    probeDependencies.put(
        "worldManagementService",
        DependencyReadinessSupport.downDependency(
            "getRoomSnapshot", "grpc:WorldManagementService#GetRoomSnapshot", "world down"));
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(resolveLookPathProbe, tracker());
    when(resolveLookPathProbe.probe(PROBE_TENANT_ID, PROBE_GAME_INSTANCE_ID, PROBE_ROOM_ID))
        .thenReturn(ProbeResult.outOfService("worldManagementService", probeDependencies));

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>)
            Objects.requireNonNull(health.getDetails().get("dependencies"));

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    assertEquals("DOWN", dependencies.get("worldManagementService").get("status"));
  }

  @Test
  void healthReturnsOutOfServiceWhenEntityDependencyFails() {
    ResolveLookPathProbe resolveLookPathProbe = mock(ResolveLookPathProbe.class);
    Map<String, Object> probeDependencies = new LinkedHashMap<>();
    probeDependencies.put(
        "worldManagementService",
        DependencyReadinessSupport.upDependency(
            "getRoomSnapshot", "grpc:WorldManagementService#GetRoomSnapshot", "OK"));
    probeDependencies.put(
        "entityManagementService",
        DependencyReadinessSupport.downDependency(
            "listRoomEntities", "grpc:EntityManagementService#ListRoomEntities", "entity down"));
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(resolveLookPathProbe, tracker());
    when(resolveLookPathProbe.probe(PROBE_TENANT_ID, PROBE_GAME_INSTANCE_ID, PROBE_ROOM_ID))
        .thenReturn(ProbeResult.outOfService("entityManagementService", probeDependencies));

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>)
            Objects.requireNonNull(health.getDetails().get("dependencies"));

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    assertEquals("UP", dependencies.get("worldManagementService").get("status"));
    assertEquals("DOWN", dependencies.get("entityManagementService").get("status"));
  }

  private static ReadinessTransitionTracker tracker() {
    return new ReadinessTransitionTracker(new SimpleMeterRegistry());
  }
}
