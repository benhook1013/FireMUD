package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateRequest;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.junit.jupiter.api.Test;

class GameSessionRuntimeControlPlaneReadServiceTest {
  @Test
  void runtimeReadRejectsWorldLifecycleThatIsNotActive() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            worldLifecycle(
                1L,
                7L,
                3L,
                WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING));
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_INVALID", error.code());
    assertEquals("WORLD_INSTANCE_LIFECYCLE_INVALID: instance is not ACTIVE", error.getMessage());
  }

  @Test
  void runtimeReadReturnsPersistedScriptPinEpoch() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            worldLifecycle(
                1L, 7L, 3L, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));
    GameSessionRuntimeControlPlaneReadService service = service(world, "RUNNING", "patch-1", 9L);

    var state = service.getGameInstanceRuntimeState(1L, runtimeRequest());

    assertEquals("patch-1", state.getPinnedScriptPatchVersion());
    assertEquals(9L, state.getScriptPinEpoch());
  }

  @Test
  void runtimeReadRejectsLifecycleResponseWithBlankErrorCode() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            GetWorldInstanceLifecycleResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setMessage("diagnostic only").build())
                .setWorldInstance(
                    WorldInstanceLifecycleSnapshot.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setLifecycleEpoch(3L)
                        .setStatus(
                            WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                        .build())
                .build());
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_AUTHORITY_UNAVAILABLE", error.code());
    assertEquals("diagnostic only", error.detailMessage());
    assertEquals("WORLD_AUTHORITY_UNAVAILABLE: diagnostic only", error.getMessage());
  }

  @Test
  void runtimeReadRejectsWorldLifecycleErrorWithCode() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            GetWorldInstanceLifecycleResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("WORLD_AUTHORITY_UNAVAILABLE")
                        .setMessage("world unavailable")
                        .build())
                .build());
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_AUTHORITY_UNAVAILABLE", error.code());
    assertEquals("world unavailable", error.detailMessage());
    assertEquals("WORLD_AUTHORITY_UNAVAILABLE: world unavailable", error.getMessage());
  }

  @Test
  void runtimeReadRejectsMissingWorldLifecycleSnapshot() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(GetWorldInstanceLifecycleResponse.getDefaultInstance());
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_INVALID", error.code());
    assertEquals(
        "WORLD_INSTANCE_LIFECYCLE_INVALID: World response omitted lifecycle snapshot",
        error.getMessage());
  }

  @Test
  void runtimeReadRejectsNonpositiveWorldLifecycleEpoch() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            worldLifecycle(
                1L, 7L, 0L, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_INVALID", error.code());
    assertEquals(
        "WORLD_INSTANCE_LIFECYCLE_INVALID: lifecycle epoch must be positive", error.getMessage());
  }

  @Test
  void runtimeReadRejectsWorldLifecycleWithMixedIdentity() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            worldLifecycle(
                2L, 7L, 3L, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_INSTANCE_SCOPE_MISMATCH", error.code());
    assertEquals(
        "WORLD_INSTANCE_SCOPE_MISMATCH: lifecycle response does not match the requested instance",
        error.getMessage());
  }

  @Test
  void runtimeReadClassifiesWorldTransportFailureAsAuthorityFailure() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenThrow(new RuntimeException("transport unavailable"));
    GameSessionRuntimeControlPlaneReadService service = service(world);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("world lifecycle authority unavailable", error.getMessage());
  }

  @Test
  void runtimeReadFailsClosedWhenWorldLifecycleResponseIsNull() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L)).thenReturn(null);
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_AUTHORITY_MALFORMED", error.code());
    assertEquals("World response was null", error.detailMessage());
    assertEquals("WORLD_AUTHORITY_MALFORMED: World response was null", error.getMessage());
  }

  @Test
  void runtimeReadFailsClosedWhenWorldLifecycleAuthorityIsNotConfigured() {
    GameSessionRuntimeControlPlaneReadService service = service(null);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("world lifecycle authority unavailable", error.getMessage());
  }

  @Test
  void runtimeReadRejectsMalformedWorldLifecycleIdentity() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            worldLifecycle(
                "not-a-number",
                "7",
                3L,
                WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_INVALID", error.code());
    assertEquals(
        "WORLD_INSTANCE_LIFECYCLE_INVALID: lifecycle response has invalid identity",
        error.getMessage());
  }

  @Test
  void runtimeReadRejectsNonPositiveWorldLifecycleIdentity() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            worldLifecycle(
                "1", "0", 3L, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));
    GameSessionRuntimeControlPlaneReadService service = service(world);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_INVALID", error.code());
    assertEquals(
        "WORLD_INSTANCE_LIFECYCLE_INVALID: lifecycle response has invalid identity",
        error.getMessage());
  }

  @Test
  void runtimeReadRejectsLocallyNonRunningInstanceBeforeExposingState() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    when(world.getWorldInstanceLifecycle(1L, 7L))
        .thenReturn(
            worldLifecycle(
                1L, 7L, 3L, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));
    GameSessionRuntimeControlPlaneReadService service = service(world, "STARTING");

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals(
        "GAME_INSTANCE_STATUS_INVALID: runtime state requires a RUNNING game instance",
        error.getMessage());
  }

  @Test
  void runtimeReadRejectsMalformedLocalPinBeforeWorldAuthorityCall() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    GameSessionRuntimeControlPlaneReadService service = service(world, "RUNNING", "patch-1", null);

    GameSessionRuntimeControlPlaneReadService.RuntimeStateException error =
        assertThrows(
            GameSessionRuntimeControlPlaneReadService.RuntimeStateException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals(
        "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together",
        error.getMessage());
    verify(world, never()).getWorldInstanceLifecycle(1L, 7L);
  }

  @Test
  void runtimeReadRejectsCrossTenantInstanceBeforeWorldAuthorityCall() {
    WorldManagementClient world = mock(WorldManagementClient.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(2L);
    RuntimeRegionStatus runtimeStatus = new RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(7L);
    runtimeStatus.setRegionId("region-7");

    GameInstanceRepository instances = mock(GameInstanceRepository.class);
    when(instances.findById(7L)).thenReturn(Optional.of(instance));
    RuntimeRegionStatusRepository runtime = mock(RuntimeRegionStatusRepository.class);
    when(runtime.findByTenantIdAndRegionId(1L, "region-7")).thenReturn(Optional.of(runtimeStatus));
    GameSessionRuntimeControlPlaneReadService service =
        new GameSessionRuntimeControlPlaneReadService(
            instances,
            mock(GameplayCommandRepository.class),
            mock(RemoteFollowupRepository.class),
            runtime,
            mock(GameplayAdmissionPointerAuthorityService.class),
            null,
            world);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.getGameInstanceRuntimeState(1L, runtimeRequest()));

    assertEquals("tenant_id does not own game_instance_id", error.getMessage());
    verify(world, never()).getWorldInstanceLifecycle(1L, 7L);
  }

  private GameSessionRuntimeControlPlaneReadService service(WorldManagementClient world) {
    return service(world, "RUNNING");
  }

  private GameSessionRuntimeControlPlaneReadService service(
      WorldManagementClient world, String status) {
    return service(world, status, null, null);
  }

  private GameSessionRuntimeControlPlaneReadService service(
      WorldManagementClient world, String status, String scriptPatchVersion, Long scriptPinEpoch) {
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(1L);
    instance.setRuntimeVersion("runtime-v7");
    instance.setScriptPatchVersion(scriptPatchVersion);
    instance.setScriptPinEpoch(scriptPinEpoch);
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      instance.setScriptPatchPinnedControlPlaneRequestId("request-1");
    }
    instance.setStatus(status);
    RuntimeRegionStatus runtimeStatus = new RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(7L);
    runtimeStatus.setRegionId("region-7");
    runtimeStatus.setRegionEpoch(2L);

    GameInstanceRepository instances = mock(GameInstanceRepository.class);
    when(instances.findById(7L)).thenReturn(Optional.of(instance));
    RuntimeRegionStatusRepository runtime = mock(RuntimeRegionStatusRepository.class);
    when(runtime.findByTenantIdAndRegionId(1L, "region-7")).thenReturn(Optional.of(runtimeStatus));
    return new GameSessionRuntimeControlPlaneReadService(
        instances,
        mock(GameplayCommandRepository.class),
        mock(RemoteFollowupRepository.class),
        runtime,
        mock(GameplayAdmissionPointerAuthorityService.class),
        null,
        world);
  }

  private GetGameInstanceRuntimeStateRequest runtimeRequest() {
    return GetGameInstanceRuntimeStateRequest.newBuilder()
        .setGameInstanceId("7")
        .setRegionId("region-7")
        .build();
  }

  private GetWorldInstanceLifecycleResponse worldLifecycle(
      long tenantId, long gameInstanceId, long epoch, WorldInstanceLifecycleStatus status) {
    return worldLifecycle(Long.toString(tenantId), Long.toString(gameInstanceId), epoch, status);
  }

  private GetWorldInstanceLifecycleResponse worldLifecycle(
      String tenantId, String gameInstanceId, long epoch, WorldInstanceLifecycleStatus status) {
    return GetWorldInstanceLifecycleResponse.newBuilder()
        .setWorldInstance(
            WorldInstanceLifecycleSnapshot.newBuilder()
                .setTenantId(tenantId)
                .setGameInstanceId(gameInstanceId)
                .setLifecycleEpoch(epoch)
                .setStatus(status)
                .build())
        .build();
  }
}
