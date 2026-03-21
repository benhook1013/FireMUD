package net.firedevops.firemud.gamelogic.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class LookDependencyReadinessHealthIndicatorTest {

  @Test
  void healthReturnsUpWhenLookDependenciesRespondToOperationShapedChecks() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    when(worldStub.getRoomSnapshot(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            GetRoomSnapshotResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("NOT_FOUND")
                        .setMessage("room missing"))
                .build());
    EntityManagementServiceBlockingStub entityStub =
        mock(EntityManagementServiceBlockingStub.class);
    when(entityStub.listRoomEntities(org.mockito.ArgumentMatchers.any()))
        .thenReturn(ListRoomEntitiesResponse.newBuilder().build());
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(worldStub, entityStub);

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.UP, health.getStatus());
    assertEquals("NOT_FOUND", dependencies.get("worldManagementService").get("outcome"));
    assertEquals("OK", dependencies.get("entityManagementService").get("outcome"));
  }

  @Test
  void healthReturnsOutOfServiceWhenWorldDependencyFails() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    doThrow(new IllegalStateException("world down"))
        .when(worldStub)
        .getRoomSnapshot(org.mockito.ArgumentMatchers.any());
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(
            worldStub, mock(EntityManagementServiceBlockingStub.class));

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("DOWN", dependencies.get("worldManagementService").get("status"));
  }

  @Test
  void healthReturnsOutOfServiceWhenEntityDependencyFails() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    when(worldStub.getRoomSnapshot(org.mockito.ArgumentMatchers.any()))
        .thenReturn(GetRoomSnapshotResponse.newBuilder().build());
    EntityManagementServiceBlockingStub entityStub =
        mock(EntityManagementServiceBlockingStub.class);
    doThrow(new IllegalStateException("entity down"))
        .when(entityStub)
        .listRoomEntities(org.mockito.ArgumentMatchers.any());
    LookDependencyReadinessHealthIndicator indicator =
        new LookDependencyReadinessHealthIndicator(worldStub, entityStub);

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("UP", dependencies.get("worldManagementService").get("status"));
    assertEquals("DOWN", dependencies.get("entityManagementService").get("status"));
  }
}
