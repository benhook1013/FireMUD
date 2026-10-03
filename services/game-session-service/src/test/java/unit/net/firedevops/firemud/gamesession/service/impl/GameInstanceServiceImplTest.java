package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class GameInstanceServiceImplTest {
  private static final String OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174000";
  private static final String LEGACY_LAUNCH_DENIAL =
      "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world launch binding is required";

  private GameInstanceRepository repository;
  private GameInstanceMapper mapper;
  private SessionStateService stateService;
  private GameDesignClient gameDesignClient;
  private WorldManagementClient worldManagementClient;
  private GameInstanceServiceImpl service;
  private Map<Long, GameInstance> store;
  private AtomicLong nextId;

  @Test
  void legacyDtoConstructorRejectsNonblankPatchWithoutCompleteTuple() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new GameInstanceDto(
                    7L,
                    1L,
                    "runtime-1",
                    "patch-1",
                    3L,
                    "launch-1",
                    11L,
                    12L,
                    13L,
                    "generation-1",
                    OWNER_ACCOUNT_UUID,
                    "RUNNING"));

    assertEquals(
        "scriptPatchVersion requires scriptPatchBaseVersionId, scriptPinEpoch, and script pin"
            + " owner request id",
        exception.getMessage());
  }

  @BeforeEach
  void setup() {
    repository = mock(GameInstanceRepository.class);
    mapper = mock(GameInstanceMapper.class);
    stateService = mock(SessionStateService.class);
    gameDesignClient = mock(GameDesignClient.class);
    worldManagementClient = mock(WorldManagementClient.class);
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
            new SimpleMeterRegistry(),
            immediateTransactionOperations());
    configureRepositoryPersistence();
    configureMapper();
    configureWorldLifecycle();
  }

  @Test
  void legacyNumericStartIsDeniedBeforeDescriptorLookupOrMutation() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.startSession(
                    new StartSessionRequest(1L, 3L, "cp-legacy", OWNER_ACCOUNT_UUID)));

    assertEquals(LEGACY_LAUNCH_DENIAL, error.getMessage());
    assertEquals(0, store.size());
    verifyNoInteractions(repository, gameDesignClient, worldManagementClient, stateService);
  }

  @Test
  void startSessionStillRejectsNonCanonicalOwnerBeforeLaunchPreflight() {
    IllegalArgumentException error = null;
    for (String invalidOwner :
        List.of(
            "42", "00000000-0000-0000-0000-000000000000", "123E4567-E89B-12D3-A456-426614174000")) {
      error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  service.startSession(
                      new StartSessionRequest(1L, 3L, "cp-invalid-owner", invalidOwner), true));
    }

    assertEquals("ownerAccountId must be a canonical non-nil UUID", error.getMessage());
    assertEquals(0, store.size());
    verifyNoInteractions(repository, gameDesignClient, worldManagementClient, stateService);
  }

  @Test
  void legacyNumericReplacementIsDeniedWithoutChangingRetainedSession() {
    persistExisting(7L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.startSession(
                    new StartSessionRequest(1L, 3L, "cp-replace", OWNER_ACCOUNT_UUID), true));

    assertEquals(LEGACY_LAUNCH_DENIAL, error.getMessage());
    assertEquals("RUNNING", store.get(7L).getStatus());
    assertEquals(1, store.size());
    verifyNoInteractions(repository, gameDesignClient, worldManagementClient, stateService);
  }

  @Test
  void stopSessionFailsClosedWhenWorldTerminationOmitsSnapshotAndError() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.terminateWorldInstance(
            anyLong(), anyLong(), anyLong(), any(), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse
                .getDefaultInstance());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    assertEquals(
        "WORLD_AUTHORITY_MALFORMED: response omitted lifecycle snapshot", error.getMessage());
  }

  @Test
  void stopSessionDeletesState() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");

    GameInstanceDto dto = service.stopSession(10L);

    verify(stateService).deleteState(1L, 10L);
    verify(worldManagementClient).getWorldInstanceLifecycle(1L, 10L);
    verify(worldManagementClient)
        .terminateWorldInstance(
            anyLong(), anyLong(), anyLong(), anyString(), eq("session stop requested"));
    assertEquals("STOPPED", dto.status());
    assertEquals("STOPPED", store.get(10L).getStatus());
  }

  @Test
  void stopSessionFinalizesLocallyWhenWorldIsAlreadyTerminated() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenReturn(
            worldLifecycleSnapshot(
                "1",
                "10",
                3L,
                WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED));

    GameInstanceDto dto = service.stopSession(10L);

    assertEquals("STOPPED", dto.status());
    assertEquals("STOPPED", store.get(10L).getStatus());
    verify(stateService).deleteState(1L, 10L);
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void stopSessionMapsTerminationInProgressResponseToLifecycleOutcome() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.terminateWorldInstance(
            anyLong(), anyLong(), anyLong(), any(), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    WorldInstanceLifecycleSnapshot.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setLifecycleEpoch(3L)
                        .setStatus(
                            WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING)
                        .build())
                .build());

    LifecycleOutcomeException error =
        assertThrows(LifecycleOutcomeException.class, () -> service.stopSession(10L));

    assertEquals("WORLD_TERMINATION_IN_PROGRESS", error.code());
    assertEquals("session termination is already in progress", error.detailMessage());
    assertEquals("STOPPING", store.get(10L).getStatus());
  }

  @Test
  void stopSessionKeepsSessionStoppedWhenFinalizationFailsAfterWorldTermination() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    AtomicInteger mapperCalls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (mapperCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("local finalization failed");
              }
              return configureMappedDto(invocation.getArgument(0));
            })
        .when(mapper)
        .toDto(any(GameInstance.class));

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    verify(worldManagementClient)
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), any(), any());
    verify(mapper, times(2)).toDto(any(GameInstance.class));
    assertEquals("STOPPED", store.get(10L).getStatus());
  }

  @Test
  void stopSessionFailsFastWhenStateDeleteFails() {
    GameInstance retained = persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    retained.setOwnerAccountId(null);
    retained.setLegacyOwnerAccountId(42L);
    store.put(10L, copyOf(retained));
    doThrow(new IllegalStateException("redis down")).when(stateService).deleteState(1L, 10L);

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    verify(stateService).deleteState(1L, 10L);
    verify(worldManagementClient, never()).getWorldInstanceLifecycle(anyLong(), anyLong());
    ArgumentCaptor<GameInstanceDto> restoredState = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService).saveState(restoredState.capture());
    assertNull(restoredState.getValue().ownerAccountId());
    assertEquals("RUNNING", store.get(10L).getStatus());
    assertNull(store.get(10L).getOwnerAccountId());
    assertEquals(42L, store.get(10L).getLegacyOwnerAccountId());
  }

  @Test
  void stopSessionLeavesStoppingStateWhenWorldTerminationFailsAfterRequest() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.terminateWorldInstance(
            anyLong(), anyLong(), anyLong(), any(), any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("ENTITY_CLEANUP_FAILED")
                        .setMessage("cleanup failed")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    verify(stateService).deleteState(1L, 10L);
    verify(stateService, never()).saveState(any(GameInstanceDto.class));
    assertEquals("STOPPING", store.get(10L).getStatus());
  }

  @Test
  void stopSessionRestoresExistingStateWhenWorldLifecyclePreflightFails() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_LIFECYCLE_UNAVAILABLE")
                        .setMessage("lifecycle read failed")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    assertEquals("RUNNING", store.get(10L).getStatus());
    verify(stateService).deleteState(1L, 10L);
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService).saveState(states.capture());
    assertEquals(10L, states.getValue().id());
    assertEquals("RUNNING", states.getValue().status());
    verify(worldManagementClient).getWorldInstanceLifecycle(1L, 10L);
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void stopSessionUsesLifecycleAuthorityConstantWhenLifecycleReadTransportFails() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenThrow(new IllegalStateException("lifecycle read timed out"));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    assertEquals("world lifecycle authority unavailable", error.getMessage());
    assertEquals("lifecycle read timed out", error.getCause().getMessage());
    assertEquals("RUNNING", store.get(10L).getStatus());
  }

  @Test
  void stopSessionLeavesStoppingStateWhenWorldTerminationIsInProgress() {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse.newBuilder()
                .setWorldInstance(
                    WorldInstanceLifecycleSnapshot.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setLifecycleEpoch(2L)
                        .setStatus(
                            WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING)
                        .build())
                .build());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    assertEquals(
        "WORLD_TERMINATION_IN_PROGRESS: session termination is already in progress",
        error.getMessage());
    assertEquals("STOPPING", store.get(10L).getStatus());
    verify(stateService).deleteState(1L, 10L);
    verify(stateService, never()).saveState(any(GameInstanceDto.class));
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @ParameterizedTest
  @EnumSource(
      value = WorldInstanceLifecycleStatus.class,
      names = {
        "WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING",
        "WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION"
      })
  void stopSessionRejectsKnownNonActiveWorldLifecycle(WorldInstanceLifecycleStatus status) {
    persistExisting(10L, 1L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenReturn(worldLifecycleSnapshot("1", "10", 3L, status));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_NOT_ACTIVE: instance is not ACTIVE", error.getMessage());
    assertEquals("RUNNING", store.get(10L).getStatus());
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void persistExistingPreservesScriptPinOwnerRequestIdInCopiedTuple() {
    GameInstance existing =
        persistExisting(
            7L, 2L, "v1", "patch-1", OWNER_ACCOUNT_UUID, "RUNNING", 100L, 3L, "pin-request-1");

    assertEquals(100L, existing.getScriptPatchBaseVersionId());
    assertEquals(3L, existing.getScriptPinEpoch());
    assertEquals("pin-request-1", existing.getScriptPatchPinnedControlPlaneRequestId());
    assertEquals(100L, store.get(7L).getScriptPatchBaseVersionId());
    assertEquals("pin-request-1", store.get(7L).getScriptPatchPinnedControlPlaneRequestId());
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
        .thenAnswer(invocation -> configureMappedDto(invocation.getArgument(0)));
  }

  private GameInstanceDto configureMappedDto(GameInstance entity) {
    return new GameInstanceDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getRuntimeVersion(),
        entity.getScriptPatchVersion(),
        entity.getScriptPatchBaseVersionId(),
        entity.getScriptPinEpoch(),
        entity.getScriptPatchPinnedControlPlaneRequestId(),
        entity.getGameTemplateId(),
        entity.getLaunchDescriptorId(),
        entity.getVersionId(),
        entity.getReleaseBundleId(),
        entity.getVersionStateEpoch(),
        entity.getGenerationConfigRevision(),
        entity.getRemapSetId(),
        entity.getOwnerAccountId(),
        entity.getStatus());
  }

  private static GetWorldInstanceLifecycleResponse worldLifecycleSnapshot(
      String tenantId,
      String gameInstanceId,
      long lifecycleEpoch,
      WorldInstanceLifecycleStatus status) {
    return GetWorldInstanceLifecycleResponse.newBuilder()
        .setWorldInstance(
            WorldInstanceLifecycleSnapshot.newBuilder()
                .setTenantId(tenantId)
                .setGameInstanceId(gameInstanceId)
                .setLifecycleEpoch(lifecycleEpoch)
                .setStatus(status)
                .build())
        .build();
  }

  private void configureWorldLifecycle() {
    when(worldManagementClient.getWorldInstanceLifecycle(anyLong(), anyLong()))
        .thenAnswer(
            invocation ->
                worldLifecycleSnapshot(
                    Long.toString(invocation.getArgument(0, Long.class)),
                    Long.toString(invocation.getArgument(1, Long.class)),
                    2L,
                    WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));
    when(worldManagementClient.terminateWorldInstance(
            anyLong(), anyLong(), anyLong(), anyString(), anyString()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse
                    .newBuilder()
                    .setWorldInstance(
                        WorldInstanceLifecycleSnapshot.newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1, Long.class)))
                            .setLifecycleEpoch(3L)
                            .setStatus(
                                WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED)
                            .build())
                    .build());
  }

  private GameInstance persistExisting(
      Long id,
      Long tenantId,
      String runtimeVersion,
      String scriptPatchVersion,
      String ownerAccountId,
      String status) {
    return persistExisting(
        id, tenantId, runtimeVersion, scriptPatchVersion, ownerAccountId, status, null, null);
  }

  private GameInstance persistExisting(
      Long id,
      Long tenantId,
      String runtimeVersion,
      String scriptPatchVersion,
      String ownerAccountId,
      String status,
      Long scriptPinEpoch,
      String scriptPatchPinnedControlPlaneRequestId) {
    return persistExisting(
        id,
        tenantId,
        runtimeVersion,
        scriptPatchVersion,
        ownerAccountId,
        status,
        null,
        scriptPinEpoch,
        scriptPatchPinnedControlPlaneRequestId);
  }

  private GameInstance persistExisting(
      Long id,
      Long tenantId,
      String runtimeVersion,
      String scriptPatchVersion,
      String ownerAccountId,
      String status,
      Long scriptPatchBaseVersionId,
      Long scriptPinEpoch,
      String scriptPatchPinnedControlPlaneRequestId) {
    // Test-only retained-row fixture; this bypasses StartSession and proves retained-state APIs
    // only.
    GameInstance instance = new GameInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setRuntimeVersion(runtimeVersion);
    instance.setScriptPatchVersion(scriptPatchVersion);
    instance.setScriptPatchBaseVersionId(scriptPatchBaseVersionId);
    instance.setScriptPinEpoch(scriptPinEpoch);
    instance.setScriptPatchPinnedControlPlaneRequestId(scriptPatchPinnedControlPlaneRequestId);
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
    copy.setScriptPatchBaseVersionId(instance.getScriptPatchBaseVersionId());
    copy.setScriptPinEpoch(instance.getScriptPinEpoch());
    copy.setScriptPatchPinnedControlPlaneRequestId(
        instance.getScriptPatchPinnedControlPlaneRequestId());
    copy.setOwnerAccountId(instance.getOwnerAccountId());
    copy.setLegacyOwnerAccountId(instance.getLegacyOwnerAccountId());
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
