package net.firedevops.firemud.gamelogic.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.gamelogic.health.ResolveLookPathProbe.ProbeResult;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.junit.jupiter.api.Test;

class ResolveLookPathProbeTest {
  private GameplaySessionAttestationService probeAttestationService() {
    GameplaySessionAttestationService service = mock(GameplaySessionAttestationService.class);
    when(service.issueInternalProbeAttestation("1", "1", "R-1021")).thenReturn("probe-token");
    return service;
  }

  @Test
  void probeReturnsUpWhenResolveLookDependenciesAreReachable() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    when(worldStub.withDeadlineAfter(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(worldStub);
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
    when(entityStub.withDeadlineAfter(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(entityStub);
    when(entityStub.listRoomEntities(org.mockito.ArgumentMatchers.any()))
        .thenReturn(ListRoomEntitiesResponse.newBuilder().build());

    ResolveLookPathProbe probe =
        new ResolveLookPathProbe(worldStub, entityStub, probeAttestationService());

    ProbeResult result = probe.probe("1", "1", "R-1021");

    assertTrue(result.ready());
    @SuppressWarnings("unchecked")
    var world = (java.util.Map<String, Object>) result.dependencies().get("worldManagementService");
    @SuppressWarnings("unchecked")
    var entity =
        (java.util.Map<String, Object>) result.dependencies().get("entityManagementService");
    assertEquals("NOT_FOUND", world.get("outcome"));
    assertEquals("OK", entity.get("outcome"));
    verify(worldStub).withDeadlineAfter(anyLong(), org.mockito.ArgumentMatchers.any());
    verify(entityStub).withDeadlineAfter(anyLong(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void probeReturnsUpWhenDependenciesRequireSessionAttestation() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    when(worldStub.withDeadlineAfter(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(worldStub);
    when(worldStub.getRoomSnapshot(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            GetRoomSnapshotResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("SESSION_ATTESTATION_REQUIRED")
                        .setMessage("probe attestation rejected"))
                .build());
    EntityManagementServiceBlockingStub entityStub =
        mock(EntityManagementServiceBlockingStub.class);
    when(entityStub.withDeadlineAfter(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(entityStub);
    when(entityStub.listRoomEntities(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            ListRoomEntitiesResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("SESSION_ATTESTATION_REQUIRED")
                        .setMessage("probe attestation rejected"))
                .build());

    ResolveLookPathProbe probe =
        new ResolveLookPathProbe(worldStub, entityStub, probeAttestationService());

    ProbeResult result = probe.probe("1", "1", "R-1021");

    assertTrue(result.ready());
    @SuppressWarnings("unchecked")
    var world = (java.util.Map<String, Object>) result.dependencies().get("worldManagementService");
    @SuppressWarnings("unchecked")
    var entity =
        (java.util.Map<String, Object>) result.dependencies().get("entityManagementService");
    assertEquals("SESSION_ATTESTATION_REQUIRED", world.get("outcome"));
    assertEquals("SESSION_ATTESTATION_REQUIRED", entity.get("outcome"));
  }

  @Test
  void probeReturnsOutOfServiceWhenWorldDependencyFails() {
    WorldManagementServiceBlockingStub worldStub = mock(WorldManagementServiceBlockingStub.class);
    when(worldStub.withDeadlineAfter(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(worldStub);
    doThrow(new IllegalStateException("world down"))
        .when(worldStub)
        .getRoomSnapshot(org.mockito.ArgumentMatchers.any());

    ResolveLookPathProbe probe =
        new ResolveLookPathProbe(
            worldStub, mock(EntityManagementServiceBlockingStub.class), probeAttestationService());

    ProbeResult result = probe.probe("1", "1", "R-1021");

    assertEquals(false, result.ready());
    assertEquals("worldManagementService", result.failingDependency());
  }
}
