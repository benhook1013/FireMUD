package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameInstanceServiceImplTest {
  private GameInstanceRepository repository;
  private GameInstanceMapper mapper;
  private SessionStateService stateService;
  private GameDesignClient gameDesignClient;
  private WorldManagementClient worldManagementClient;
  private SimpleMeterRegistry meterRegistry;
  private GameInstanceServiceImpl service;
  private Map<Long, GameInstance> store;
  private AtomicLong nextId;

  @BeforeEach
  void setup() {
    repository = mock(GameInstanceRepository.class);
    mapper = mock(GameInstanceMapper.class);
    stateService = mock(SessionStateService.class);
    gameDesignClient = mock(GameDesignClient.class);
    worldManagementClient = mock(WorldManagementClient.class);
    meterRegistry = new SimpleMeterRegistry();
    store = new HashMap<>();
    nextId = new AtomicLong(10L);
    service =
        new GameInstanceServiceImpl(
            repository,
            mapper,
            stateService,
            gameDesignClient,
            null,
            worldManagementClient,
            null,
            null,
            meterRegistry,
            immediateTransactionOperations());
    configureRepositoryPersistence();
    configureMapper();
    configureLaunchPreflight();
    configureWorldActivation();
  }

  @Test
  void startSessionSavesState() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-1", 42L);

    GameInstanceDto dto = service.startSession(request);

    assertEquals("RUNNING", dto.status());
    verify(repository, never()).findFirstByTenantIdAndOwnerAccountIdAndStatus(1L, 42L, "RUNNING");
    verify(stateService).saveState(any(GameInstanceDto.class));
  }

  @Test
  void startSessionFailsWhenWorldPreparationFails() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-6", 42L);
    when(worldManagementClient.prepareWorldInstance(
            any(Long.class),
            any(Long.class),
            any(Long.class),
            any(),
            any(),
            any(Long.class),
            any(),
            any(),
            any(),
            any(Long.class),
            any(),
            any(Long.class)))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_UNAVAILABLE")
                        .setMessage("world unavailable")
                        .build())
                .build());

    assertThrows(IllegalArgumentException.class, () -> service.startSession(request));

    verify(stateService, never()).saveState(any());
    assertEquals(0, store.size());
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(any(Long.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void startSessionStopsExistingRunningSessionOnlyWithinTenantAndOwner() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-2", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));

    GameInstanceDto dto = service.startSession(request, true);

    verify(repository).findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING");
    verify(stateService).saveState(any(GameInstanceDto.class));
    verify(stateService).deleteState(2L, 7L);
    verify(worldManagementClient).getWorldInstanceLifecycle(2L, 7L);
    verify(worldManagementClient)
        .terminateWorldInstance(any(Long.class), any(Long.class), any(Long.class), any(), any());
    assertEquals("STOPPED", store.get(7L).getStatus());
    assertEquals("RUNNING", store.get(dto.id()).getStatus());
  }

  @Test
  void startSessionWithoutReplacementLeavesExistingSessionRunning() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-3", 42L);

    service.startSession(request, false);

    verify(repository, never()).findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING");
    verify(stateService).saveState(any(GameInstanceDto.class));
  }

  @Test
  void stopSessionDeletesState() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");

    GameInstanceDto dto = service.stopSession(10L);

    verify(stateService).deleteState(1L, 10L);
    verify(worldManagementClient).getWorldInstanceLifecycle(1L, 10L);
    verify(worldManagementClient)
        .terminateWorldInstance(any(Long.class), any(Long.class), any(Long.class), any(), any());
    assertEquals("STOPPED", dto.status());
    assertEquals("STOPPED", store.get(10L).getStatus());
  }

  @Test
  void startSessionFailsFastWhenStateSaveFails() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-4", 42L);
    doThrow(new IllegalStateException("redis down")).when(stateService).saveState(any());

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    verify(stateService).saveState(any());
    verify(stateService, never()).deleteState(1L, 10L);
    assertEquals(0, store.size());
  }

  @Test
  void startSessionWithReplacementRestoresExistingRunningStateWhenNewStateSaveFails() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-5", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    doThrow(new IllegalStateException("redis down")).when(stateService).saveState(any());

    assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals(1, store.size());
    assertEquals("RUNNING", store.get(7L).getStatus());
    verify(stateService, never()).deleteState(2L, 7L);
    verify(worldManagementClient, never())
        .getWorldInstanceLifecycle(any(Long.class), any(Long.class));
  }

  @Test
  void startSessionWithReplacementRestoresExistingRunningStateWhenTerminationFails() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-5", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    when(worldManagementClient.terminateWorldInstance(
            any(Long.class), any(Long.class), any(Long.class), any(), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("ENTITY_CLEANUP_FAILED")
                        .setMessage("cleanup failed")
                        .build())
                .build());

    assertThrows(IllegalArgumentException.class, () -> service.startSession(request, true));

    assertEquals(1, store.size());
    assertEquals("RUNNING", store.get(7L).getStatus());
    verify(stateService).deleteState(2L, 7L);
    verify(stateService, times(2)).saveState(any(GameInstanceDto.class));
  }

  @Test
  void stopSessionFailsFastWhenStateDeleteFails() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
    doThrow(new IllegalStateException("redis down")).when(stateService).deleteState(1L, 10L);

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    verify(stateService).deleteState(1L, 10L);
    verify(worldManagementClient, never())
        .getWorldInstanceLifecycle(any(Long.class), any(Long.class));
    verify(stateService).saveState(any(GameInstanceDto.class));
    assertEquals("RUNNING", store.get(10L).getStatus());
  }

  @Test
  void stopSessionLeavesStoppingRowWhenWorldTerminationFails() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
    when(worldManagementClient.terminateWorldInstance(
            any(Long.class), any(Long.class), any(Long.class), any(), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("ENTITY_CLEANUP_FAILED")
                        .setMessage("cleanup failed")
                        .build())
                .build());

    assertThrows(IllegalArgumentException.class, () -> service.stopSession(10L));

    verify(stateService).deleteState(1L, 10L);
    verify(stateService, never()).saveState(any());
    assertEquals("STOPPING", store.get(10L).getStatus());
  }

  private void configureRepositoryPersistence() {
    when(repository.save(any(GameInstance.class)))
        .thenAnswer(
            invocation -> {
              GameInstance input = invocation.getArgument(0);
              if (input.getId() == null) {
                input.setId(nextId.getAndIncrement());
              }
              store.put(input.getId(), copyOf(input));
              return input;
            });
    when(repository.findById(any(Long.class)))
        .thenAnswer(
            invocation -> {
              Long id = invocation.getArgument(0);
              GameInstance stored = store.get(id);
              return stored == null ? Optional.empty() : Optional.of(copyOf(stored));
            });
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            any(Long.class), any(Long.class), any()))
        .thenAnswer(
            invocation -> {
              Long tenantId = invocation.getArgument(0);
              Long ownerAccountId = invocation.getArgument(1);
              String status = invocation.getArgument(2);
              return store.values().stream()
                  .filter(instance -> tenantId.equals(instance.getTenantId()))
                  .filter(instance -> ownerAccountId.equals(instance.getOwnerAccountId()))
                  .filter(instance -> status.equals(instance.getStatus()))
                  .findFirst()
                  .map(GameInstanceServiceImplTest::copyOf);
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Long id = invocation.getArgument(0);
              store.remove(id);
              return null;
            })
        .when(repository)
        .deleteById(any(Long.class));
  }

  private void configureMapper() {
    when(mapper.toDto(any(GameInstance.class)))
        .thenAnswer(
            invocation -> {
              GameInstance entity = invocation.getArgument(0);
              return new GameInstanceDto(
                  entity.getId(),
                  entity.getTenantId(),
                  entity.getRuntimeVersion(),
                  entity.getScriptPatchVersion(),
                  entity.getGameTemplateId(),
                  entity.getLaunchDescriptorId(),
                  entity.getVersionId(),
                  entity.getReleaseBundleId(),
                  entity.getVersionStateEpoch(),
                  entity.getGenerationConfigRevision(),
                  entity.getOwnerAccountId(),
                  entity.getStatus());
            });
  }

  private void configureLaunchPreflight() {
    when(gameDesignClient.resolveLaunchDescriptor(any(Long.class), any(Long.class), any()))
        .thenAnswer(
            invocation ->
                ResolveLaunchDescriptorResponse.newBuilder()
                    .setLaunchDescriptor(
                        net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
                            .setLaunchDescriptorId("ld-" + invocation.getArgument(2, String.class))
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameTemplateId(invocation.getArgument(1, Long.class))
                            .setControlPlaneRequestId(invocation.getArgument(2, String.class))
                            .setVersionId(11L)
                            .setRuntimeFlagsJson("{}")
                            .setGenerationConfigRevision("genrev-11")
                            .setVersionStateEpoch(77L)
                            .setReleaseBundleId(77L)
                            .setPublishedReleaseBundleRef(
                                "prb:" + invocation.getArgument(0, Long.class) + ":11:77")
                            .build())
                    .build());
    when(gameDesignClient.getPublishedReleaseBundle(any(Long.class), any(Long.class)))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setManifestHash("manifest-11")
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision("genrev-11")
                        .build())
                .build());
    when(gameDesignClient.getVersionAssetArtifactState(any(Long.class), any(Long.class)))
        .thenReturn(
            GetVersionAssetArtifactStateResponse.newBuilder()
                .setArtifactState(
                    net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState.newBuilder()
                        .setTenantId("1")
                        .setVersionId(11L)
                        .setArtifactState(
                            net.firedevops.firemud.gamedesign.v1.ArtifactState
                                .ARTIFACT_STATE_PUBLISHED)
                        .setStateEpoch(2L)
                        .setManifestHash("manifest-11")
                        .addExportedManifestAssetKeys("manifest.json")
                        .build())
                .build());
    when(gameDesignClient.getVersionState(any(Long.class), any(Long.class)))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse.newBuilder()
                .setVersionState(
                    net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot.newBuilder()
                        .setTenantId("1")
                        .setVersionId(11L)
                        .setVersionState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setVersionStateEpoch(77L)
                        .setUpdatedAt("2026-04-15T10:00:00")
                        .build())
                .build());
  }

  @Test
  void startSessionFailsWhenPublishedAssetProofDoesNotMatchReleaseBundle() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-proof", 42L);
    when(gameDesignClient.getVersionAssetArtifactState(any(Long.class), any(Long.class)))
        .thenReturn(
            GetVersionAssetArtifactStateResponse.newBuilder()
                .setArtifactState(
                    net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState.newBuilder()
                        .setTenantId("1")
                        .setVersionId(11L)
                        .setArtifactState(
                            net.firedevops.firemud.gamedesign.v1.ArtifactState
                                .ARTIFACT_STATE_PUBLISHED)
                        .setStateEpoch(2L)
                        .setManifestHash("different-manifest")
                        .addExportedManifestAssetKeys("manifest.json")
                        .build())
                .build());

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.startSession(request));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published asset artifact state does not match the release bundle",
        error.getMessage());
  }

  private void configureWorldActivation() {
    when(worldManagementClient.prepareWorldInstance(
            any(Long.class),
            any(Long.class),
            any(Long.class),
            any(),
            any(),
            any(Long.class),
            any(),
            any(),
            any(),
            any(Long.class),
            any(),
            any(Long.class)))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setGameTemplateId("3")
                        .setControlPlaneRequestId("cp")
                        .setLaunchDescriptorId("ld-cp")
                        .setVersionId("11")
                        .setReleaseBundleId("77")
                        .setGenerationConfigRevision("genrev-11")
                        .setPublishedReleaseBundleRef("prb:1:11:77")
                        .setVersionStateEpoch(77L)
                        .setLifecycleEpoch(1L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING)
                        .build())
                .build());
    when(worldManagementClient.activatePreparedWorldInstance(
            any(Long.class), any(Long.class), any(Long.class)))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                .newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setGameTemplateId("3")
                        .setControlPlaneRequestId("cp")
                        .setLaunchDescriptorId("ld-cp")
                        .setVersionId("11")
                        .setReleaseBundleId("77")
                        .setGenerationConfigRevision("genrev-11")
                        .setPublishedReleaseBundleRef("prb:1:11:77")
                        .setVersionStateEpoch(77L)
                        .setLifecycleEpoch(2L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                        .build())
                .build());
    when(worldManagementClient.failPreparedWorldInstance(
            any(Long.class), any(Long.class), any(Long.class), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setLifecycleEpoch(2L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION)
                        .build())
                .build());
    when(worldManagementClient.getWorldInstanceLifecycle(any(Long.class), any(Long.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse
                    .newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1, Long.class)))
                            .setLifecycleEpoch(2L)
                            .setStatus(
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                            .build())
                    .build());
    when(worldManagementClient.terminateWorldInstance(
            any(Long.class), any(Long.class), any(Long.class), any(), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setLifecycleEpoch(4L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED)
                        .build())
                .build());
  }

  private GameInstance persistExisting(
      Long id,
      Long tenantId,
      String runtimeVersion,
      String scriptPatchVersion,
      Long ownerAccountId,
      String status) {
    GameInstance instance = new GameInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setRuntimeVersion(runtimeVersion);
    instance.setScriptPatchVersion(scriptPatchVersion);
    instance.setOwnerAccountId(ownerAccountId);
    instance.setStatus(status);
    store.put(id, copyOf(instance));
    return copyOf(instance);
  }

  private static GameInstance copyOf(GameInstance instance) {
    GameInstance copy = new GameInstance();
    copy.setId(instance.getId());
    copy.setTenantId(instance.getTenantId());
    copy.setRuntimeVersion(instance.getRuntimeVersion());
    copy.setScriptPatchVersion(instance.getScriptPatchVersion());
    copy.setOwnerAccountId(instance.getOwnerAccountId());
    copy.setStatus(instance.getStatus());
    return copy;
  }

  private static org.springframework.transaction.support.TransactionOperations
      immediateTransactionOperations() {
    return new org.springframework.transaction.support.TransactionOperations() {
      @Override
      public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
        return action.doInTransaction(
            new org.springframework.transaction.support.SimpleTransactionStatus());
      }
    };
  }
}
