package net.firedevops.firemud.gamelogic.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.gamelogic.health.ResolveLookPathProbe.ProbeResult;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.junit.jupiter.api.Test;

class ResolveLookPathProbeTest {

  @Test
  void probeReturnsUpWhenResolveLookDependenciesAreReachable() {
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

    ResolveLookPathProbe probe = new ResolveLookPathProbe(worldStub, entityStub);

    ProbeResult result = probe.probe("0", "0");

    assertTrue(result.ready());
    @SuppressWarnings("unchecked")
    var world = (java.util.Map<String, Object>) result.dependencies().get("worldManagementService");
    @SuppressWarnings("unchecked")
    var entity =
        (java.util.Map<String, Object>) result.dependencies().get("entityManagementService");
    assertEquals("NOT_FOUND", world.get("outcome"));
    assertEquals("OK", entity.get("outcome"));
  }

  @Test
  void probeReturnsOutOfServiceWhenWorldDependencyFails() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    doThrow(new IllegalStateException("world down"))
        .when(worldStub)
        .getRoomSnapshot(org.mockito.ArgumentMatchers.any());

    ResolveLookPathProbe probe =
        new ResolveLookPathProbe(worldStub, mock(EntityManagementServiceBlockingStub.class));

    ProbeResult result = probe.probe("0", "0");

    assertEquals(false, result.ready());
    assertEquals("worldManagementService", result.failingDependency());
  }
}
