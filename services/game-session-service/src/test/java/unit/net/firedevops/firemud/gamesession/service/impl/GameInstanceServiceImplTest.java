package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

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
                    42L,
                    "RUNNING"));

    assertEquals(
        "scriptPatchVersion requires scriptPinEpoch and script pin owner request id",
        exception.getMessage());
  }

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
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(2)).saveState(states.capture());
    assertEquals("STARTING", states.getAllValues().get(0).status());
    assertEquals("RUNNING", states.getAllValues().get(1).status());
    assertNull(store.get(10L).getScriptPinEpoch());
  }

  @Test
  void startSessionLeavesScriptPinTupleAbsentUntilOwnerPinTransition() {
    doReturn(
            ResolveLaunchDescriptorResponse.newBuilder()
                .setLaunchDescriptor(
                    net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
                        .setLaunchDescriptorId("ld-pinned")
                        .setTenantId("1")
                        .setGameTemplateId(3L)
                        .setControlPlaneRequestId("cp-pinned")
                        .setVersionId(11L)
                        .setScriptPatchVersion("patch-1")
                        .setRuntimeFlagsJson("{}")
                        .setGenerationConfigRevision("genrev-11")
                        .setVersionStateEpoch(77L)
                        .setReleaseBundleId(77L)
                        .setPublishedReleaseBundleRef("prb:1:11:77")
                        .build())
                .build())
        .when(gameDesignClient)
        .resolveLaunchDescriptor(any(Long.class), any(Long.class), any());

    GameInstanceDto dto = service.startSession(new StartSessionRequest(1L, 3L, "cp-pinned", 42L));

    assertNull(dto.scriptPatchVersion());
    assertNull(dto.scriptPinEpoch());
    assertNull(store.get(dto.id()).getScriptPinEpoch());
  }

  @Test
  void startSessionDoesNotCompensateWhenStartedMetricFails() {
    MeterRegistry failingMeterRegistry = mock(MeterRegistry.class);
    when(failingMeterRegistry.counter("game_sessions_started_total"))
        .thenThrow(new IllegalStateException("metrics unavailable"));
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
            failingMeterRegistry,
            immediateTransactionOperations());

    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-metric-failure", 42L);

    GameInstanceDto dto = service.startSession(request);

    assertEquals("RUNNING", dto.status());
    assertEquals("RUNNING", store.get(10L).getStatus());
    verify(stateService, never()).deleteState(1L, 10L);
  }

  @Test
  void startSessionQuarantinesOwnerWhenWorldActivationFails() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-activation-failure", 42L);
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                .newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_ACTIVATION_FAILED")
                        .setMessage("activation failed")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService).saveState(states.capture());
    assertEquals("STARTING", states.getValue().status());
    verify(stateService, never()).deleteState(1L, 10L);
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any());
    verify(repository, never()).deleteById(10L);
    assertEquals("STARTING", store.get(10L).getStatus());
  }

  @Test
  void startSessionQuarantinesOwnerAfterAmbiguousWorldActivationResponse() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-activation-timeout", 42L);
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenThrow(new IllegalStateException("activation response timed out"));

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    verify(stateService).saveState(any(GameInstanceDto.class));
    verify(stateService, never()).deleteState(1L, 10L);
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any());
    verify(repository, never()).deleteById(10L);
    assertEquals("STARTING", store.get(10L).getStatus());
  }

  @Test
  void startSessionQuarantinesOwnerWhenRuntimeStateSaveFailsAfterActivation() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-runtime-save-failure", 42L);
    AtomicInteger saveCount = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (saveCount.incrementAndGet() == 2) {
                throw new IllegalStateException("redis down");
              }
              return null;
            })
        .when(stateService)
        .saveState(any(GameInstanceDto.class));

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    verify(worldManagementClient).activatePreparedWorldInstance(anyLong(), anyLong(), anyLong());
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(3)).saveState(states.capture());
    assertEquals(
        List.of("STARTING", "RUNNING", "STARTING"),
        states.getAllValues().stream().map(GameInstanceDto::status).toList());
    verify(stateService, never()).deleteState(1L, 10L);
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any());
    verify(repository, never()).deleteById(10L);
    assertEquals("STARTING", store.get(10L).getStatus());
  }

  @Test
  void startSessionQuarantinesOwnerWhenLocalFinalizationFailsAfterActivation() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-finalization-failure", 42L);
    doThrow(new IllegalStateException("local finalization failed"))
        .when(mapper)
        .toDto(any(GameInstance.class));

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    verify(worldManagementClient).activatePreparedWorldInstance(anyLong(), anyLong(), anyLong());
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(3)).saveState(states.capture());
    assertEquals(
        List.of("STARTING", "RUNNING", "STARTING"),
        states.getAllValues().stream().map(GameInstanceDto::status).toList());
    verify(stateService, never()).deleteState(1L, 10L);
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any());
    verify(repository, never()).deleteById(10L);
    assertEquals("STARTING", store.get(10L).getStatus());
  }

  @Test
  void startSessionFailsWhenWorldPreparationFails() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-6", 42L);
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_UNAVAILABLE")
                        .setMessage("world unavailable")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    verify(stateService, never()).saveState(any());
    assertEquals(0, store.size());
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any());
  }

  @Test
  void startSessionUsesPreparationAuthorityErrorWhenPreparationTransportFails() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-preparation-timeout", 42L);
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenThrow(new IllegalStateException("preparation response timed out"));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals("world preparation authority unavailable", error.getMessage());
    assertEquals("preparation response timed out", error.getCause().getMessage());
    verify(stateService, never()).saveState(any());
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any());
  }

  @Test
  void failPreparedWorldInstanceUsesDedicatedAuthorityErrorWhenTransportFails()
      throws ReflectiveOperationException {
    when(worldManagementClient.failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any()))
        .thenThrow(new IllegalStateException("fail-prepared response timed out"));

    Class<?> preparedWorldInstanceType =
        Arrays.stream(GameInstanceServiceImpl.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("PreparedWorldInstance"))
            .findFirst()
            .orElseThrow();
    Method failPreparedMethod =
        GameInstanceServiceImpl.class.getDeclaredMethod(
            "failPreparedWorldInstance", preparedWorldInstanceType, String.class);
    Constructor<?> constructor =
        preparedWorldInstanceType.getDeclaredConstructor(long.class, long.class, long.class);
    constructor.setAccessible(true);
    Object preparedWorldInstance = constructor.newInstance(1L, 10L, 1L);
    failPreparedMethod.setAccessible(true);

    InvocationTargetException invocation =
        assertThrows(
            InvocationTargetException.class,
            () ->
                failPreparedMethod.invoke(
                    service, preparedWorldInstance, "session start failed before admission"));

    assertEquals("world fail-prepared authority unavailable", invocation.getCause().getMessage());
    assertEquals("fail-prepared response timed out", invocation.getCause().getCause().getMessage());
  }

  @Test
  void startSessionFailsClosedWhenWorldPreparationResponseIsNull() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-null-world", 42L);
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenReturn(null);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals("WORLD_AUTHORITY_MALFORMED: response was null", error.getMessage());
  }

  @Test
  void startSessionFailsClosedWhenWorldPreparationOmitsSnapshotAndError() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-empty-world", 42L);
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse
                .getDefaultInstance());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals(
        "WORLD_AUTHORITY_MALFORMED: response omitted lifecycle snapshot", error.getMessage());
  }

  @Test
  void startSessionFailsClosedWhenWorldPreparationScopeDoesNotMatch() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-mismatched-world", 42L);
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenReturn(worldPreparationSnapshot("2", "10", 1L));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals(
        "WORLD_AUTHORITY_SCOPE_MISMATCH: lifecycle response does not match the requested instance",
        error.getMessage());
  }

  @Test
  void startSessionFailsClosedWhenWorldPreparationEpochIsNotPositive() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-zero-world-epoch", 42L);
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenReturn(worldPreparationSnapshot("1", "10", 0L));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals("WORLD_AUTHORITY_MALFORMED: lifecycle epoch must be positive", error.getMessage());
  }

  @Test
  void startSessionFailsClosedWhenWorldPreparationStatusIsAlreadyActive() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-active-world", 42L);
    doReturn(
            net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setLifecycleEpoch(1L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                        .build())
                .build())
        .when(worldManagementClient)
        .prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals(
        "WORLD_AUTHORITY_MALFORMED: lifecycle response has unexpected status", error.getMessage());
    verify(worldManagementClient, never())
        .activatePreparedWorldInstance(anyLong(), anyLong(), anyLong());
    verify(stateService, never()).saveState(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = WorldInstanceLifecycleStatus.class,
      names = {
        "WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING",
        "WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION"
      })
  void startSessionWithReplacementRejectsKnownNonActiveWorldLifecycle(
      WorldInstanceLifecycleStatus status) {
    StartSessionRequest request =
        new StartSessionRequest(2L, 3L, "cp-known-non-active-" + status.name(), 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    when(worldManagementClient.getWorldInstanceLifecycle(2L, 7L))
        .thenReturn(worldLifecycleSnapshot("2", "7", 3L, status));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_NOT_ACTIVE: instance is not ACTIVE", error.getMessage());
    assertEquals("RUNNING", store.get(7L).getStatus());
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void startSessionFailsClosedWhenWorldActivationOmitsSnapshotAndError() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-empty-activation", 42L);
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                .getDefaultInstance());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals(
        "WORLD_AUTHORITY_MALFORMED: response omitted lifecycle snapshot", error.getMessage());
  }

  @Test
  void stopSessionFailsClosedWhenWorldTerminationOmitsSnapshotAndError() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
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
  void startSessionStopsExistingRunningSessionOnlyWithinTenantAndOwner() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-2", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));

    GameInstanceDto dto = service.startSession(request, true);

    verify(repository).findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING");
    verify(stateService, times(2)).saveState(any(GameInstanceDto.class));
    verify(stateService).deleteState(2L, 7L);
    verify(worldManagementClient).getWorldInstanceLifecycle(2L, 7L);
    verify(worldManagementClient)
        .terminateWorldInstance(
            anyLong(), anyLong(), anyLong(), anyString(), eq("session replacement requested"));
    ArgumentCaptor<GameInstance> savedInstances = ArgumentCaptor.forClass(GameInstance.class);
    verify(repository, times(4)).save(savedInstances.capture());
    assertEquals(
        1,
        savedInstances.getAllValues().stream()
            .filter(instance -> Long.valueOf(7L).equals(instance.getId()))
            .filter(instance -> "STOPPED".equals(instance.getStatus()))
            .count());
    assertEquals("STOPPED", store.get(7L).getStatus());
    assertEquals("RUNNING", store.get(dto.id()).getStatus());
  }

  @Test
  void startSessionWithoutReplacementLeavesExistingSessionRunning() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-3", 42L);

    service.startSession(request, false);

    verify(repository, never()).findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING");
    verify(stateService, times(2)).saveState(any(GameInstanceDto.class));
  }

  @Test
  void stopSessionDeletesState() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");

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
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
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
  void stopSessionKeepsSessionStoppedWhenFinalizationFailsAfterWorldTermination() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
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
    AtomicInteger saveCount = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (saveCount.incrementAndGet() == 1) {
                throw new IllegalStateException("redis down");
              }
              return null;
            })
        .when(stateService)
        .saveState(any());

    assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals(1, store.size());
    assertEquals("RUNNING", store.get(7L).getStatus());
    assertNull(store.get(7L).getScriptPatchVersion());
    assertNull(store.get(7L).getScriptPinEpoch());
    verify(stateService, never()).deleteState(2L, 7L);
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(2)).saveState(states.capture());
    assertEquals(10L, states.getAllValues().get(0).id());
    assertEquals(7L, states.getAllValues().get(1).id());
    assertEquals("RUNNING", states.getAllValues().get(1).status());
    verify(worldManagementClient, never()).getWorldInstanceLifecycle(anyLong(), anyLong());
  }

  @Test
  void startSessionWithReplacementLeavesExistingSessionStoppingWhenTerminationFails() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-5", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
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

    assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals(1, store.size());
    assertEquals("STOPPING", store.get(7L).getStatus());
    verify(stateService).deleteState(2L, 7L);
    verify(stateService).deleteState(2L, 10L);
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(1)).saveState(states.capture());
    assertEquals(10L, states.getValue().id());
  }

  @Test
  void startSessionWithReplacementFinalizesAlreadyTerminatedExistingSession() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-terminated-replacement", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    when(worldManagementClient.getWorldInstanceLifecycle(2L, 7L))
        .thenReturn(
            worldLifecycleSnapshot(
                "2",
                "7",
                3L,
                WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED));

    GameInstanceDto dto = service.startSession(request, true);

    assertEquals("STOPPED", store.get(7L).getStatus());
    assertEquals("RUNNING", dto.status());
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void startSessionWithReplacementDoesNotRestoreAlreadyTerminatedExistingSessionOnFailure() {
    StartSessionRequest request =
        new StartSessionRequest(2L, 3L, "cp-terminated-replacement-failure", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    when(worldManagementClient.getWorldInstanceLifecycle(2L, 7L))
        .thenReturn(
            worldLifecycleSnapshot(
                "2",
                "7",
                3L,
                WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED));
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                .newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_ACTIVATION_FAILED")
                        .setMessage("activation failed")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals("STOPPED", store.get(7L).getStatus());
    verify(stateService).deleteState(2L, 7L);
    verify(stateService, never()).saveState(argThat(state -> state.id() == 7L));
  }

  @Test
  void startSessionWithReplacementLeavesExistingSessionStoppingWhenTerminationIsInProgress() {
    StartSessionRequest request =
        new StartSessionRequest(2L, 3L, "cp-terminating-replacement", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    when(worldManagementClient.getWorldInstanceLifecycle(2L, 7L))
        .thenReturn(
            worldLifecycleSnapshot(
                "2",
                "7",
                3L,
                WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals(
        "WORLD_TERMINATION_IN_PROGRESS: replaced session is already terminating",
        error.getMessage());
    assertEquals("STOPPING", store.get(7L).getStatus());
    assertEquals(1, store.size());
    verify(stateService).deleteState(2L, 10L);
    verify(stateService, never()).saveState(argThat(state -> state.id() == 7L));
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void startSessionWithReplacementRestoresExistingStateWhenWorldLifecyclePreflightFails() {
    StartSessionRequest request =
        new StartSessionRequest(2L, 3L, "cp-world-preflight-failure", 42L);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, 42L, "RUNNING"))
        .thenReturn(Optional.of(existing));
    when(worldManagementClient.getWorldInstanceLifecycle(2L, 7L))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_LIFECYCLE_UNAVAILABLE")
                        .setMessage("lifecycle read failed")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals(1, store.size());
    assertEquals("RUNNING", store.get(7L).getStatus());
    verify(stateService).deleteState(2L, 7L);
    verify(stateService).deleteState(2L, 10L);
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(2)).saveState(states.capture());
    assertEquals(10L, states.getAllValues().get(0).id());
    assertEquals(7L, states.getAllValues().get(1).id());
    assertEquals("RUNNING", states.getAllValues().get(1).status());
    verify(worldManagementClient).getWorldInstanceLifecycle(2L, 7L);
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
    verify(worldManagementClient)
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), anyString());
    verify(repository).deleteById(10L);
  }

  @Test
  void startSessionDoesNotResurrectReplacedSessionWhenActivationFails() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-activation-failure", 42L);
    persistExisting(7L, 2L, "v1", null, 42L, "RUNNING");
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                .newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_ACTIVATION_FAILED")
                        .setMessage("activation failed")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.startSession(request, true));

    assertEquals("STOPPED", store.get(7L).getStatus());
    assertEquals("STARTING", store.get(10L).getStatus());
    verify(stateService).deleteState(2L, 7L);
    verify(stateService, times(1)).saveState(any(GameInstanceDto.class));
    verify(worldManagementClient, never())
        .failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any());
    verify(repository, never()).deleteById(10L);
  }

  @Test
  void stopSessionFailsFastWhenStateDeleteFails() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
    doThrow(new IllegalStateException("redis down")).when(stateService).deleteState(1L, 10L);

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    verify(stateService).deleteState(1L, 10L);
    verify(worldManagementClient, never()).getWorldInstanceLifecycle(anyLong(), anyLong());
    verify(stateService).saveState(any(GameInstanceDto.class));
    assertEquals("RUNNING", store.get(10L).getStatus());
  }

  @Test
  void stopSessionLeavesStoppingStateWhenWorldTerminationFailsAfterRequest() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
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
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
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
  void stopSessionLeavesStoppingStateWhenWorldTerminationIsInProgress() {
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenReturn(
            net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("10")
                        .setLifecycleEpoch(2L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
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
    persistExisting(10L, 1L, "v1", null, 42L, "RUNNING");
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenReturn(worldLifecycleSnapshot("1", "10", 3L, status));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    assertEquals("WORLD_INSTANCE_LIFECYCLE_NOT_ACTIVE: instance is not ACTIVE", error.getMessage());
    assertEquals("RUNNING", store.get(10L).getStatus());
    verify(worldManagementClient, never())
        .terminateWorldInstance(anyLong(), anyLong(), anyLong(), anyString(), anyString());
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
        .thenAnswer(invocation -> configureMappedDto(invocation.getArgument(0)));
  }

  private GameInstanceDto configureMappedDto(GameInstance entity) {
    return new GameInstanceDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getRuntimeVersion(),
        entity.getScriptPatchVersion(),
        entity.getScriptPinEpoch(),
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
                        .setAttestationSchemaVersion("v1")
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
        "RELEASE_ATTESTATION_MISMATCH: published asset artifact state does not match the release"
            + " bundle",
        error.getMessage());
  }

  @Test
  void startSessionRejectsNonPositiveLaunchDescriptorTenantIdBeforeWorldPreparation() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-launch-tenant", 42L);
    doReturn(
            ResolveLaunchDescriptorResponse.newBuilder()
                .setLaunchDescriptor(
                    net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
                        .setLaunchDescriptorId("ld-cp-launch-tenant")
                        .setTenantId("0")
                        .setGameTemplateId(3L)
                        .setControlPlaneRequestId("cp-launch-tenant")
                        .setVersionId(11L)
                        .setRuntimeFlagsJson("{}")
                        .setGenerationConfigRevision("genrev-11")
                        .setVersionStateEpoch(77L)
                        .setReleaseBundleId(77L)
                        .setPublishedReleaseBundleRef("prb:1:11:77")
                        .build())
                .build())
        .when(gameDesignClient)
        .resolveLaunchDescriptor(anyLong(), anyLong(), anyString());

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.startSession(request));

    assertEquals("tenantId must be positive", error.getMessage());
    verify(worldManagementClient, never())
        .prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any());
    verify(stateService, never()).saveState(any());
  }

  @Test
  void startSessionFailsWhenReleaseBundleSchemaIsUnsupported() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-schema", 42L);
    when(gameDesignClient.getPublishedReleaseBundle(any(Long.class), any(Long.class)))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setAttestationSchemaVersion("v999")
                        .setManifestHash("manifest-11")
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision("genrev-11")
                        .build())
                .build());

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.startSession(request));

    assertEquals(
        "SCHEMA_VERSION_UNSUPPORTED: unsupported published release bundle attestation schema v999",
        error.getMessage());
  }

  @Test
  void startSessionRejectsMalformedPreparedWorldInstanceGameInstanceIdBeforeActivation() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-prep-id", 42L);
    doReturn(
            net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                .setWorldInstance(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                        .newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("bad")
                        .setLifecycleEpoch(1L)
                        .setStatus(
                            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                                .WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING)
                        .build())
                .build())
        .when(worldManagementClient)
        .prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals("gameInstanceId must be numeric", error.getMessage());
    verify(worldManagementClient, never())
        .activatePreparedWorldInstance(anyLong(), anyLong(), anyLong());
    verify(stateService, never()).saveState(any());
  }

  @Test
  void persistExistingPreservesScriptPinOwnerRequestIdInCopiedTuple() {
    GameInstance existing =
        persistExisting(7L, 2L, "v1", "patch-1", 42L, "RUNNING", 3L, "pin-request-1");

    assertEquals(3L, existing.getScriptPinEpoch());
    assertEquals("pin-request-1", existing.getScriptPatchPinnedControlPlaneRequestId());
    assertEquals("pin-request-1", store.get(7L).getScriptPatchPinnedControlPlaneRequestId());
  }

  private static net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse
      worldPreparationSnapshot(String tenantId, String gameInstanceId, long lifecycleEpoch) {
    return net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
        .setWorldInstance(
            net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot.newBuilder()
                .setTenantId(tenantId)
                .setGameInstanceId(gameInstanceId)
                .setLifecycleEpoch(lifecycleEpoch)
                .setStatus(
                    net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus
                        .WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING)
                .build())
        .build();
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

  private void configureWorldActivation() {
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            any(),
            any(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1, Long.class)))
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
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING)
                            .build())
                    .build());
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                    .newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1, Long.class)))
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
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                            .build())
                    .build());
    when(worldManagementClient.failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse
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
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION)
                            .build())
                    .build());
    when(worldManagementClient.getWorldInstanceLifecycle(anyLong(), anyLong()))
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
            anyLong(), anyLong(), anyLong(), anyString(), anyString()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse
                    .newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1, Long.class)))
                            .setLifecycleEpoch(4L)
                            .setStatus(
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
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
    return persistExisting(
        id, tenantId, runtimeVersion, scriptPatchVersion, ownerAccountId, status, null, null);
  }

  private GameInstance persistExisting(
      Long id,
      Long tenantId,
      String runtimeVersion,
      String scriptPatchVersion,
      Long ownerAccountId,
      String status,
      Long scriptPinEpoch,
      String scriptPatchPinnedControlPlaneRequestId) {
    GameInstance instance = new GameInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setRuntimeVersion(runtimeVersion);
    instance.setScriptPatchVersion(scriptPatchVersion);
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
    copy.setScriptPinEpoch(instance.getScriptPinEpoch());
    copy.setScriptPatchPinnedControlPlaneRequestId(
        instance.getScriptPatchPinnedControlPlaneRequestId());
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
