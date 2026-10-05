package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class GameInstanceServiceImplTest {
  private static final String OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174000";
  private static final String OTHER_OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174001";

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
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-1", OWNER_ACCOUNT_UUID);

    GameInstanceDto dto = service.startSession(request);

    assertEquals("RUNNING", dto.status());
    verify(repository, never())
        .findFirstByTenantIdAndOwnerAccountIdAndStatus(1L, OWNER_ACCOUNT_UUID, "RUNNING");
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(2)).saveState(states.capture());
    assertEquals("STARTING", states.getAllValues().get(0).status());
    assertEquals("RUNNING", states.getAllValues().get(1).status());
    assertNull(store.get(10L).getScriptPinEpoch());
  }

  @Test
  void startSessionRejectsNonCanonicalOwnerBeforeDependenciesOrMutation() {
    for (String invalidOwner :
        List.of(
            "42", "00000000-0000-0000-0000-000000000000", "123E4567-E89B-12D3-A456-426614174000")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.startSession(
                  new StartSessionRequest(1L, 3L, "cp-invalid-owner", invalidOwner), true));
    }

    verify(gameDesignClient, never()).resolveLaunchDescriptor(anyLong(), anyLong(), anyString());
    verify(repository, never()).save(any(GameInstance.class));
    assertEquals(0, store.size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void startSessionRejectsUnresolvedLegacyOwnerBeforeMutation(boolean replaceExistingFirst) {
    persistLegacyOwner(7L, 1L, "v1", "RUNNING", 9_007_199_254_740_993L);

    LifecycleOutcomeException error =
        assertThrows(
            LifecycleOutcomeException.class,
            () ->
                service.startSession(
                    new StartSessionRequest(1L, 3L, "cp-legacy-owner", OWNER_ACCOUNT_UUID),
                    replaceExistingFirst));

    assertEquals("OWNER_ACCOUNT_IDENTITY_UNAVAILABLE", error.code());
    assertEquals(
        "cannot start a session while tenant owner identity evidence is active or uncertain",
        error.detailMessage());
    assertEquals("RUNNING", store.get(7L).getStatus());
    assertEquals(9_007_199_254_740_993L, store.get(7L).getLegacyOwnerAccountId());
    assertEquals(1, store.size());
    verify(repository).findUnresolvedActiveOwnerRowsByTenantIdForUpdate(1L);
    verify(repository, never()).save(any(GameInstance.class));
    verify(repository, never())
        .findFirstByTenantIdAndOwnerAccountIdAndStatus(1L, OWNER_ACCOUNT_UUID, "RUNNING");
    verifyNoInteractions(stateService);
    verifyNoInteractions(worldManagementClient);
  }

  @Test
  void startSessionRejectsEveryUncertainLegacyOwnerStatus() {
    String[] uncertainStatuses = {"STARTING", "STOPPING", "UNRECOGNIZED", null};
    long id = 20L;
    for (String status : uncertainStatuses) {
      persistLegacyOwner(id, 1L, "v1", status, 100L + id);

      LifecycleOutcomeException error =
          assertThrows(
              LifecycleOutcomeException.class,
              () ->
                  service.startSession(
                      new StartSessionRequest(1L, 3L, "cp-uncertain-owner", OWNER_ACCOUNT_UUID)));

      assertEquals("OWNER_ACCOUNT_IDENTITY_UNAVAILABLE", error.code());
      assertEquals(status, store.get(id).getStatus());
      assertEquals(100L + id, store.get(id).getLegacyOwnerAccountId());
      store.remove(id);
      id++;
    }

    verify(repository, times(uncertainStatuses.length))
        .findUnresolvedActiveOwnerRowsByTenantIdForUpdate(1L);
    verify(repository, never()).save(any(GameInstance.class));
    verifyNoInteractions(stateService);
    verifyNoInteractions(worldManagementClient);
  }

  @Test
  void startSessionAllowsTenantWithOnlyStoppedLegacyOwnerEvidence() {
    persistLegacyOwner(7L, 1L, "v1", "STOPPED", 9_007_199_254_740_993L);

    GameInstanceDto dto =
        service.startSession(
            new StartSessionRequest(1L, 3L, "cp-stopped-legacy-owner", OWNER_ACCOUNT_UUID));

    assertEquals("RUNNING", dto.status());
    assertEquals("STOPPED", store.get(7L).getStatus());
    assertEquals(9_007_199_254_740_993L, store.get(7L).getLegacyOwnerAccountId());
    assertEquals("RUNNING", store.get(dto.id()).getStatus());
  }

  @Test
  void restartSessionRejectsMissingOrNonCanonicalOwnerBeforeMutation() {
    String[] unresolvedOwnerIds = {
      null, "42", "00000000-0000-0000-0000-000000000000", "123E4567-E89B-12D3-A456-426614174000"
    };
    long id = 20L;
    for (String ownerAccountId : unresolvedOwnerIds) {
      GameInstance instance = persistLegacyOwner(id, 1L, "v1", "STOPPED", 200L + id);
      instance.setOwnerAccountId(ownerAccountId);
      store.put(id, copyOf(instance));
      long sessionId = id;

      LifecycleOutcomeException error =
          assertThrows(LifecycleOutcomeException.class, () -> service.restartSession(sessionId));

      assertEquals("OWNER_ACCOUNT_IDENTITY_UNAVAILABLE", error.code());
      assertEquals("STOPPED", store.get(sessionId).getStatus());
      assertEquals(ownerAccountId, store.get(sessionId).getOwnerAccountId());
      assertEquals(200L + sessionId, store.get(sessionId).getLegacyOwnerAccountId());
      id++;
    }

    verify(repository, never()).save(any(GameInstance.class));
    verifyNoInteractions(stateService);
    verifyNoInteractions(worldManagementClient);
  }

  @Test
  void runOwnedInitialLaunchExactRetryReturnsSameActiveTargetWithoutReplayingActivation() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-1", OWNER_ACCOUNT_UUID);
    configureRunOwnedWorld(request);

    var first = service.startRunOwnedInitialLaunch(request);
    clearInvocations(stateService, worldManagementClient);
    var retry = service.startRunOwnedInitialLaunch(request);

    assertEquals(first.gameInstance().id(), retry.gameInstance().id());
    assertEquals(10L, retry.gameInstance().id());
    assertEquals(11L, retry.gameInstance().versionId());
    assertEquals(2L, retry.activeLifecycleEpoch());
    assertEquals("RUNNING", store.get(10L).getStatus());
    assertEquals(1L, store.get(10L).getRunOwnedStartPreparingEpoch());
    assertEquals(2L, store.get(10L).getRunOwnedStartActiveEpoch());
    assertEquals(
        "b906f1e8c611679a00a2fa30ae11438bc5ce540b18995a9efbd607e6891cc720",
        store.get(10L).getRunOwnedStartRequestDigest());
    ArgumentCaptor<GameInstanceDto> repairedState = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService).saveState(repairedState.capture());
    assertEquals("RUNNING", repairedState.getValue().status());
    verify(worldManagementClient).getWorldInstanceLifecycle(1L, 10L);
    verifyNoMoreInteractions(worldManagementClient);
  }

  @Test
  void runOwnedRunningRetryRejectsContradictoryPreparingWorldWithoutRuntimeMutation() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-preparing", OWNER_ACCOUNT_UUID);
    AtomicReference<WorldInstanceLifecycleSnapshot> worldState = configureRunOwnedWorld(request);
    service.startRunOwnedInitialLaunch(request);
    clearInvocations(stateService, worldManagementClient);
    worldState.set(
        runOwnedWorldSnapshot(
            request,
            10L,
            1L,
            WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> service.startRunOwnedInitialLaunch(request));

    assertEquals(
        "RUN_OWNED_INITIAL_LAUNCH_STATE_MISMATCH: running owner row is not ACTIVE in World",
        error.getMessage());
    assertEquals("RUNNING", store.get(10L).getStatus());
    assertEquals(2L, store.get(10L).getRunOwnedStartActiveEpoch());
    verifyNoInteractions(stateService);
    verify(worldManagementClient).getWorldInstanceLifecycle(1L, 10L);
    verifyNoMoreInteractions(worldManagementClient);
  }

  @Test
  void runOwnedInitialLaunchRetryReconcilesAmbiguousPrepareOnTheSameInstance() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-prepare-timeout", OWNER_ACCOUNT_UUID);
    AtomicReference<WorldInstanceLifecycleSnapshot> worldState = configureRunOwnedWorld(request);
    AtomicInteger prepareCalls = new AtomicInteger();
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
            invocation -> {
              if (prepareCalls.getAndIncrement() == 0) {
                worldState.set(
                    runOwnedWorldSnapshot(
                        request,
                        invocation.getArgument(1, Long.class),
                        1L,
                        WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING));
                throw new IllegalStateException("prepare response timed out");
              }
              return net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse
                  .newBuilder()
                  .setWorldInstance(worldState.get())
                  .build();
            });

    IllegalStateException pending =
        assertThrows(
            IllegalStateException.class, () -> service.startRunOwnedInitialLaunch(request));

    assertEquals("world preparation authority unavailable", pending.getMessage());
    assertEquals(1, store.size());
    assertEquals(10L, store.get(10L).getId());
    assertNull(store.get(10L).getRunOwnedStartPreparingEpoch());

    var resumed = service.startRunOwnedInitialLaunch(request);

    assertEquals(10L, resumed.gameInstance().id());
    assertEquals(2L, resumed.activeLifecycleEpoch());
    assertEquals(1, store.size());
    assertEquals("RUNNING", store.get(10L).getStatus());
    verify(worldManagementClient, times(2))
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
  }

  @Test
  void runOwnedInitialLaunchRetryReconcilesAmbiguousActivationOnTheSameInstance() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-timeout", OWNER_ACCOUNT_UUID);
    AtomicReference<WorldInstanceLifecycleSnapshot> worldState = configureRunOwnedWorld(request);
    doThrow(new IllegalStateException("activation response timed out"))
        .when(worldManagementClient)
        .activatePreparedWorldInstance(1L, 10L, 1L);

    IllegalStateException pending =
        assertThrows(
            IllegalStateException.class, () -> service.startRunOwnedInitialLaunch(request));

    assertEquals("world activation authority unavailable", pending.getMessage());
    assertEquals("STARTING", store.get(10L).getStatus());
    assertEquals(1L, store.get(10L).getRunOwnedStartPreparingEpoch());
    assertNull(store.get(10L).getRunOwnedStartActiveEpoch());
    worldState.set(
        runOwnedWorldSnapshot(
            request, 10L, 2L, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE));

    var resumed = service.startRunOwnedInitialLaunch(request);

    assertEquals(10L, resumed.gameInstance().id());
    assertEquals(2L, resumed.activeLifecycleEpoch());
    assertEquals(1, store.size());
    assertEquals("RUNNING", store.get(10L).getStatus());
    verify(worldManagementClient, times(1)).activatePreparedWorldInstance(1L, 10L, 1L);
    verify(repository, never()).deleteById(10L);
  }

  @Test
  void runOwnedInitialLaunchRequestIdConflictsOnChangedOwnerOrTemplate() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-conflict", OWNER_ACCOUNT_UUID);
    configureRunOwnedWorld(request);
    service.startRunOwnedInitialLaunch(request);

    assertThrows(
        IllegalStateException.class,
        () ->
            service.startRunOwnedInitialLaunch(
                new StartSessionRequest(1L, 3L, "run-owned-conflict", OTHER_OWNER_ACCOUNT_UUID)));
    assertThrows(
        IllegalStateException.class,
        () ->
            service.startRunOwnedInitialLaunch(
                new StartSessionRequest(1L, 4L, "run-owned-conflict", OWNER_ACCOUNT_UUID)));

    assertEquals(1, store.size());
    assertEquals(10L, store.get(10L).getId());
    verify(worldManagementClient, times(1))
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
  }

  @Test
  void runOwnedInitialLaunchRejectsWorldDescriptorMismatchAndRetainsIdentity() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-mismatch", OWNER_ACCOUNT_UUID);
    AtomicReference<WorldInstanceLifecycleSnapshot> worldState = configureRunOwnedWorld(request);
    doAnswer(
            invocation ->
                GetWorldInstanceLifecycleResponse.newBuilder()
                    .setWorldInstance(
                        worldState.get().toBuilder()
                            .setPublishedReleaseBundleRef("prb:1:11:78")
                            .build())
                    .build())
        .when(worldManagementClient)
        .getWorldInstanceLifecycle(anyLong(), anyLong());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> service.startRunOwnedInitialLaunch(request));

    assertEquals(
        "WORLD_AUTHORITY_DESCRIPTOR_MISMATCH: lifecycle readback differs from the resolved launch descriptor",
        error.getMessage());
    assertEquals(1, store.size());
    assertEquals("STARTING", store.get(10L).getStatus());
    assertEquals(1L, store.get(10L).getRunOwnedStartPreparingEpoch());
    verify(worldManagementClient, never())
        .activatePreparedWorldInstance(anyLong(), anyLong(), anyLong());
  }

  @Test
  void runOwnedInitialLaunchRetainsTerminalIdentityAfterPreActivationFailure() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-failed", OWNER_ACCOUNT_UUID);
    AtomicReference<WorldInstanceLifecycleSnapshot> worldState = configureRunOwnedWorld(request);
    worldState.set(
        runOwnedWorldSnapshot(
            request,
            10L,
            2L,
            WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION));

    assertThrows(IllegalStateException.class, () -> service.startRunOwnedInitialLaunch(request));
    assertThrows(IllegalStateException.class, () -> service.startRunOwnedInitialLaunch(request));

    assertEquals(1, store.size());
    assertEquals("run-owned-failed", store.get(10L).getRunOwnedStartRequestId());
    assertEquals("STARTING", store.get(10L).getStatus());
    verify(worldManagementClient, never())
        .activatePreparedWorldInstance(anyLong(), anyLong(), anyLong());
  }

  @Test
  void runOwnedInitialLaunchRejectsNonCanonicalOwnerBeforeDependenciesOrMutation() {
    for (String invalidOwner :
        List.of(
            "42", "00000000-0000-0000-0000-000000000000", "123E4567-E89B-12D3-A456-426614174000")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.startRunOwnedInitialLaunch(
                  new StartSessionRequest(1L, 3L, "run-owned-invalid-owner", invalidOwner)));
    }

    verify(gameDesignClient, never()).resolveLaunchDescriptor(anyLong(), anyLong(), anyString());
    verify(repository, never()).lockRunOwnedStartIdentity(anyLong(), anyString());
    verify(repository, never()).save(any(GameInstance.class));
    assertEquals(0, store.size());
    verifyNoInteractions(stateService);
    verifyNoInteractions(worldManagementClient);
  }

  @Test
  void runOwnedInitialLaunchRejectsUnresolvedLegacyOwnerBeforeCreatingCandidate() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "run-owned-legacy-owner", OWNER_ACCOUNT_UUID);
    configureRunOwnedWorld(request);
    persistLegacyOwner(7L, 1L, "v1", "RUNNING", 9_007_199_254_740_993L);

    LifecycleOutcomeException error =
        assertThrows(
            LifecycleOutcomeException.class, () -> service.startRunOwnedInitialLaunch(request));

    assertEquals("OWNER_ACCOUNT_IDENTITY_UNAVAILABLE", error.code());
    assertEquals(
        "cannot start a session while tenant owner identity evidence is active or uncertain",
        error.detailMessage());
    assertEquals(1, store.size());
    assertEquals(9_007_199_254_740_993L, store.get(7L).getLegacyOwnerAccountId());
    verify(repository).findUnresolvedActiveOwnerRowsByTenantIdForUpdate(1L);
    verify(repository, never()).save(any(GameInstance.class));
    verifyNoInteractions(stateService);
    verifyNoInteractions(worldManagementClient);
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

    GameInstanceDto dto =
        service.startSession(new StartSessionRequest(1L, 3L, "cp-pinned", OWNER_ACCOUNT_UUID));

    assertNull(dto.scriptPatchVersion());
    assertNull(dto.scriptPinEpoch());
    assertNull(store.get(dto.id()).getScriptPinEpoch());
    ArgumentCaptor<String> scriptPatchVersion = ArgumentCaptor.forClass(String.class);
    verify(worldManagementClient)
        .prepareWorldInstance(
            eq(1L),
            eq(dto.id()),
            eq(3L),
            eq("cp-pinned"),
            eq("ld-pinned"),
            eq(11L),
            scriptPatchVersion.capture(),
            eq("{}"),
            eq("genrev-11"),
            eq(77L),
            eq("prb:1:11:77"),
            eq(77L),
            org.mockito.ArgumentMatchers.isNull());
    assertNull(scriptPatchVersion.getValue());
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

    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-metric-failure", OWNER_ACCOUNT_UUID);

    GameInstanceDto dto = service.startSession(request);

    assertEquals("RUNNING", dto.status());
    assertEquals("RUNNING", store.get(10L).getStatus());
    verify(stateService, never()).deleteState(1L, 10L);
  }

  @Test
  void startSessionQuarantinesOwnerWhenWorldActivationFails() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-activation-failure", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-activation-timeout", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-runtime-save-failure", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-finalization-failure", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-6", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-preparation-timeout", OWNER_ACCOUNT_UUID);
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
  void startSessionPreservesStartFailureWhenPreparedWorldCleanupTransportFails() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-fail-prepared", OWNER_ACCOUNT_UUID);
    doThrow(new IllegalStateException("state save failed")).when(stateService).saveState(any());
    when(worldManagementClient.failPreparedWorldInstance(anyLong(), anyLong(), anyLong(), any()))
        .thenThrow(new IllegalStateException("fail-prepared response timed out"));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals("state save failed", error.getMessage());
    verify(worldManagementClient)
        .failPreparedWorldInstance(
            eq(1L), eq(10L), eq(1L), eq("session start failed before admission opened"));
  }

  @Test
  void startSessionFailsClosedWhenWorldPreparationResponseIsNull() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-null-world", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-empty-world", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-mismatched-world", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-zero-world-epoch", OWNER_ACCOUNT_UUID);
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

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "not-a-number,10,tenantId",
    "1,not-a-number,gameInstanceId"
  })
  void startSessionPrefixesMalformedWorldIdentifiersAndRetainsCause(
      String tenantId, String gameInstanceId, String fieldName) {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-malformed-world-id", OWNER_ACCOUNT_UUID);
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
        .thenReturn(worldPreparationSnapshot(tenantId, gameInstanceId, 1L));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.startSession(request));

    assertEquals(
        "WORLD_AUTHORITY_MALFORMED: " + fieldName + " must be numeric", error.getMessage());
    assertEquals(fieldName + " must be numeric", error.getCause().getMessage());
  }

  @Test
  void startSessionFailsClosedWhenWorldPreparationStatusIsAlreadyActive() {
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-active-world", OWNER_ACCOUNT_UUID);
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
        new StartSessionRequest(2L, 3L, "cp-known-non-active-" + status.name(), OWNER_ACCOUNT_UUID);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-empty-activation", OWNER_ACCOUNT_UUID);
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
  void startSessionStopsExistingRunningSessionOnlyWithinTenantAndOwner() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-2", OWNER_ACCOUNT_UUID);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
        .thenReturn(Optional.of(existing));

    GameInstanceDto dto = service.startSession(request, true);

    verify(repository)
        .findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, OWNER_ACCOUNT_UUID, "RUNNING");
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
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-3", OWNER_ACCOUNT_UUID);

    service.startSession(request, false);

    verify(repository, never())
        .findFirstByTenantIdAndOwnerAccountIdAndStatus(2L, OWNER_ACCOUNT_UUID, "RUNNING");
    verify(stateService, times(2)).saveState(any(GameInstanceDto.class));
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
  void startSessionFailsFastWhenStateSaveFails() {
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-4", OWNER_ACCOUNT_UUID);
    doThrow(new IllegalStateException("redis down")).when(stateService).saveState(any());

    assertThrows(IllegalStateException.class, () -> service.startSession(request));

    verify(stateService).saveState(any());
    verify(stateService, never()).deleteState(1L, 10L);
    assertEquals(0, store.size());
  }

  @Test
  void startSessionWithReplacementRestoresExistingRunningStateWhenNewStateSaveFails() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-5", OWNER_ACCOUNT_UUID);
    GameInstance existing =
        persistExisting(
            7L, 2L, "v1", "patch-1", OWNER_ACCOUNT_UUID, "RUNNING", 100L, 3L, "pin-request-1");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
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
    assertEquals("patch-1", store.get(7L).getScriptPatchVersion());
    assertEquals(100L, store.get(7L).getScriptPatchBaseVersionId());
    assertEquals(3L, store.get(7L).getScriptPinEpoch());
    assertEquals("pin-request-1", store.get(7L).getScriptPatchPinnedControlPlaneRequestId());
    verify(stateService, never()).deleteState(2L, 7L);
    ArgumentCaptor<GameInstanceDto> states = ArgumentCaptor.forClass(GameInstanceDto.class);
    verify(stateService, times(2)).saveState(states.capture());
    assertEquals(10L, states.getAllValues().get(0).id());
    assertEquals(7L, states.getAllValues().get(1).id());
    assertEquals("RUNNING", states.getAllValues().get(1).status());
    assertEquals(100L, states.getAllValues().get(1).scriptPatchBaseVersionId());
    verify(worldManagementClient, never()).getWorldInstanceLifecycle(anyLong(), anyLong());
  }

  @Test
  void startSessionWithReplacementLeavesExistingSessionStoppingWhenTerminationFails() {
    StartSessionRequest request = new StartSessionRequest(2L, 3L, "cp-5", OWNER_ACCOUNT_UUID);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
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
    StartSessionRequest request =
        new StartSessionRequest(2L, 3L, "cp-terminated-replacement", OWNER_ACCOUNT_UUID);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
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
        new StartSessionRequest(2L, 3L, "cp-terminated-replacement-failure", OWNER_ACCOUNT_UUID);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
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
        new StartSessionRequest(2L, 3L, "cp-terminating-replacement", OWNER_ACCOUNT_UUID);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
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
        new StartSessionRequest(2L, 3L, "cp-world-preflight-failure", OWNER_ACCOUNT_UUID);
    GameInstance existing = persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            2L, OWNER_ACCOUNT_UUID, "RUNNING"))
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
    StartSessionRequest request =
        new StartSessionRequest(2L, 3L, "cp-activation-failure", OWNER_ACCOUNT_UUID);
    persistExisting(7L, 2L, "v1", null, OWNER_ACCOUNT_UUID, "RUNNING");
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
  void stopSessionCompensationPreservesUnmappedNumericOwnerEvidence() {
    persistLegacyOwner(10L, 1L, "v1", "RUNNING", 9_007_199_254_740_993L);
    when(worldManagementClient.getWorldInstanceLifecycle(1L, 10L))
        .thenReturn(
            GetWorldInstanceLifecycleResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("WORLD_LIFECYCLE_UNAVAILABLE")
                        .setMessage("lifecycle read failed")
                        .build())
                .build());

    assertThrows(IllegalStateException.class, () -> service.stopSession(10L));

    assertEquals("RUNNING", store.get(10L).getStatus());
    assertNull(store.get(10L).getOwnerAccountId());
    assertEquals(9_007_199_254_740_993L, store.get(10L).getLegacyOwnerAccountId());
    verify(stateService).deleteState(1L, 10L);
    verify(stateService)
        .saveState(argThat(state -> state.id() == 10L && "RUNNING".equals(state.status())));
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
    when(repository.findByTenantIdAndRunOwnedStartRequestIdForUpdate(anyLong(), anyString()))
        .thenAnswer(
            invocation -> {
              Long tenantId = invocation.getArgument(0);
              String requestId = invocation.getArgument(1);
              return store.values().stream()
                  .filter(instance -> tenantId.equals(instance.getTenantId()))
                  .filter(instance -> requestId.equals(instance.getRunOwnedStartRequestId()))
                  .findFirst()
                  .map(GameInstanceServiceImplTest::copyOf);
            });
    when(repository.findByTenantIdAndGameInstanceIdForUpdate(anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              Long tenantId = invocation.getArgument(0);
              Long gameInstanceId = invocation.getArgument(1);
              GameInstance stored = store.get(gameInstanceId);
              return stored != null && tenantId.equals(stored.getTenantId())
                  ? Optional.of(copyOf(stored))
                  : Optional.empty();
            });
    when(repository.findUnresolvedActiveOwnerRowsByTenantIdForUpdate(anyLong()))
        .thenAnswer(
            invocation -> {
              Long tenantId = invocation.getArgument(0);
              return store.values().stream()
                  .filter(instance -> tenantId.equals(instance.getTenantId()))
                  .filter(instance -> instance.getOwnerAccountId() == null)
                  .filter(
                      instance ->
                          instance.getStatus() == null || !"STOPPED".equals(instance.getStatus()))
                  .map(GameInstanceServiceImplTest::copyOf)
                  .toList();
            });
    when(repository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
            any(Long.class), anyString(), any()))
        .thenAnswer(
            invocation -> {
              Long tenantId = invocation.getArgument(0);
              String ownerAccountId = invocation.getArgument(1);
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
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-proof", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request =
        new StartSessionRequest(1L, 3L, "cp-launch-tenant", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-schema", OWNER_ACCOUNT_UUID);
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
    StartSessionRequest request = new StartSessionRequest(1L, 3L, "cp-prep-id", OWNER_ACCOUNT_UUID);
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

    assertEquals("WORLD_AUTHORITY_MALFORMED: gameInstanceId must be numeric", error.getMessage());
    assertEquals("gameInstanceId must be numeric", error.getCause().getMessage());
    verify(worldManagementClient, never())
        .activatePreparedWorldInstance(anyLong(), anyLong(), anyLong());
    verify(stateService, never()).saveState(any());
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

  private AtomicReference<WorldInstanceLifecycleSnapshot> configureRunOwnedWorld(
      StartSessionRequest request) {
    AtomicReference<WorldInstanceLifecycleSnapshot> worldState = new AtomicReference<>();
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
            invocation -> {
              if (worldState.get() == null) {
                worldState.set(
                    runOwnedWorldSnapshot(
                        request,
                        invocation.getArgument(1, Long.class),
                        1L,
                        WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING));
              }
              return PrepareWorldInstanceResponse.newBuilder()
                  .setWorldInstance(worldState.get())
                  .build();
            });
    when(worldManagementClient.getWorldInstanceLifecycle(anyLong(), anyLong()))
        .thenAnswer(
            invocation ->
                GetWorldInstanceLifecycleResponse.newBuilder()
                    .setWorldInstance(worldState.get())
                    .build());
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              WorldInstanceLifecycleSnapshot active =
                  runOwnedWorldSnapshot(
                      request,
                      invocation.getArgument(1, Long.class),
                      invocation.getArgument(2, Long.class) + 1L,
                      WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE);
              worldState.set(active);
              return net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                  .newBuilder()
                  .setWorldInstance(active)
                  .build();
            });
    return worldState;
  }

  private static WorldInstanceLifecycleSnapshot runOwnedWorldSnapshot(
      StartSessionRequest request,
      long gameInstanceId,
      long lifecycleEpoch,
      WorldInstanceLifecycleStatus status) {
    return WorldInstanceLifecycleSnapshot.newBuilder()
        .setTenantId(Long.toString(request.tenantId()))
        .setGameInstanceId(Long.toString(gameInstanceId))
        .setGameTemplateId(Long.toString(request.gameTemplateId()))
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setLaunchDescriptorId("ld-" + request.controlPlaneRequestId())
        .setVersionId("11")
        .setReleaseBundleId("77")
        .setGenerationConfigRevision("genrev-11")
        .setPublishedReleaseBundleRef("prb:" + request.tenantId() + ":11:77")
        .setVersionStateEpoch(77L)
        .setLifecycleEpoch(lifecycleEpoch)
        .setStatus(status)
        .build();
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

  private GameInstance persistLegacyOwner(
      Long id, Long tenantId, String runtimeVersion, String status, Long legacyOwnerAccountId) {
    GameInstance instance = new GameInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setRuntimeVersion(runtimeVersion);
    instance.setStatus(status);
    instance.setLegacyOwnerAccountId(legacyOwnerAccountId);
    store.put(id, copyOf(instance));
    return copyOf(instance);
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
    copy.setScriptPatchBaseVersionId(instance.getScriptPatchBaseVersionId());
    copy.setGameTemplateId(instance.getGameTemplateId());
    copy.setLaunchDescriptorId(instance.getLaunchDescriptorId());
    copy.setVersionId(instance.getVersionId());
    copy.setReleaseBundleId(instance.getReleaseBundleId());
    copy.setVersionStateEpoch(instance.getVersionStateEpoch());
    copy.setGenerationConfigRevision(instance.getGenerationConfigRevision());
    copy.setRemapSetId(instance.getRemapSetId());
    copy.setOwnerAccountId(instance.getOwnerAccountId());
    copy.setLegacyOwnerAccountId(instance.getLegacyOwnerAccountId());
    copy.setStatus(instance.getStatus());
    copy.setRowVersion(instance.getRowVersion());
    copy.setRunOwnedStartRequestId(instance.getRunOwnedStartRequestId());
    copy.setRunOwnedStartRequestDigest(instance.getRunOwnedStartRequestDigest());
    copy.setRunOwnedStartPublishedReleaseBundleRef(
        instance.getRunOwnedStartPublishedReleaseBundleRef());
    copy.setRunOwnedStartPreparingEpoch(instance.getRunOwnedStartPreparingEpoch());
    copy.setRunOwnedStartActiveEpoch(instance.getRunOwnedStartActiveEpoch());
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
