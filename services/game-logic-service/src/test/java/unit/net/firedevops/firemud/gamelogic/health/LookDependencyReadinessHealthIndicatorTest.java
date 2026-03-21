package net.firedevops.firemud.gamelogic.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class LookDependencyReadinessHealthIndicatorTest {

  @Test
  void healthReturnsUpWhenWorldAndEntityServicesAreReachable() {
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(
            mock(WorldManagementServiceBlockingStub.class),
            mock(EntityManagementServiceBlockingStub.class));

    Health health = indicator.health();

    assertEquals(Status.UP, health.getStatus());
    assertEquals("UP", health.getDetails().get("worldManagementService"));
    assertEquals("UP", health.getDetails().get("entityManagementService"));
  }

  @Test
  void healthReturnsOutOfServiceWhenWorldDependencyFails() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    doThrow(new IllegalStateException("world down"))
        .when(worldStub)
        .ping(net.firedevops.firemud.worldmanagement.v1.PingRequest.getDefaultInstance());
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(
            worldStub, mock(EntityManagementServiceBlockingStub.class));

    Health health = indicator.health();

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("DOWN", health.getDetails().get("worldManagementService"));
  }

  @Test
  void healthReturnsOutOfServiceWhenEntityDependencyFails() {
    EntityManagementServiceBlockingStub entityStub =
        mock(EntityManagementServiceBlockingStub.class);
    doThrow(new IllegalStateException("entity down"))
        .when(entityStub)
        .ping(net.firedevops.firemud.entitymanagement.v1.PingRequest.getDefaultInstance());
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(
            mock(WorldManagementServiceBlockingStub.class), entityStub);

    Health health = indicator.health();

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("UP", health.getDetails().get("worldManagementService"));
    assertEquals("DOWN", health.getDetails().get("entityManagementService"));
  }
}
