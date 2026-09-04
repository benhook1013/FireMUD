package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.service.impl.AutomationGameplayCommandAdmissionSupport.AdmissionRequest;
import net.firedevops.firemud.gamesession.service.impl.AutomationGameplayCommandAdmissionSupport.AdmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AutomationGameplayCommandAdmissionSupportTest {
  private RemoteFollowupRepository remoteFollowupRepository;
  private RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private GameInstanceRepository gameInstanceRepository;
  private GameplayCommandRepository gameplayCommandRepository;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private TickService tickService;
  private RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private AutomationScriptingClient automationScriptingClient;
  private GameplayAdmissionPointerAuthorityService pointerAuthority;
  private DurableRemoteFollowupExecutionService service;

  @BeforeEach
  void setupRemoteFollowupFixture() {
    remoteFollowupRepository = mock(RemoteFollowupRepository.class);
    remoteCommandCoordinatorRepository = mock(RemoteCommandCoordinatorRepository.class);
    gameInstanceRepository = mock(GameInstanceRepository.class);
    gameplayCommandRepository = mock(GameplayCommandRepository.class);
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    tickService = mock(TickService.class);
    remoteFollowupRuntimeService = mock(RemoteFollowupRuntimeService.class);
    automationScriptingClient = mock(AutomationScriptingClient.class);
    pointerAuthority = mock(GameplayAdmissionPointerAuthorityService.class);
    when(remoteFollowupRuntimeService.recordResult(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new RemoteFollowupRuntimeService.ResultOutcome(
                RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_ABANDONED,
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED,
                false,
                false));
    service =
        new DefaultDurableRemoteFollowupExecutionService(
            remoteFollowupRepository,
            remoteCommandCoordinatorRepository,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService,
            remoteFollowupRuntimeService,
            automationScriptingClient);
  }

  @Test
  void terminalizesUnexpectedQueueFailureAndReturnsSameRejectionOnRetry() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));

    GameplayCommand failed = new GameplayCommand();
    populateAdmissionFields(failed, automationRequest());
    failed.setCommandId("auto-failed");
    failed.setExecutionOutcome("FAILED");
    failed.setFailureCode("QUEUE_UNAVAILABLE");
    failed.setFailureMessage("Gameplay command queue unavailable");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.empty(), Optional.of(failed));
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenAnswer(
            invocation -> {
              GameplayCommand command = invocation.getArgument(0);
              command.setCommandId("auto-failed");
              return new GameplayCommandRepository.IdempotentInsertResult(command, true);
            });
    when(gameplayCommandRepository.markAcceptedCommandFailed(any(), any(), any(), any()))
        .thenReturn(true);

    RuntimeRegionStatus ownership = new RuntimeRegionStatus();
    ownership.setTenantId(1L);
    ownership.setGameInstanceId(2L);
    ownership.setRegionId("region-alpha");
    ownership.setRegionEpoch(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(ownership));
    doThrow(new IllegalStateException("redis connection failed"))
        .when(tickService)
        .enqueueCommand(1L, 2L, "auto-failed", "say hello", false);

    AdmissionRequest request = automationRequest();
    AdmissionResult first =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            request,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);
    AdmissionResult retry =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            request,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertFalse(first.accepted());
    assertEquals("REJECTED", first.admissionOutcome());
    assertEquals("UNAVAILABLE", first.errorCode());
    assertEquals(first, retry);
    verify(gameplayCommandRepository)
        .markAcceptedCommandFailed(
            eq("auto-failed"),
            eq("QUEUE_UNAVAILABLE"),
            eq("Gameplay command queue unavailable"),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void acceptsAutomationCommandWithMalformedTargetEntityAndLeavesCharacterIdUnset() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenAnswer(
            invocation -> {
              GameplayCommand inserted = invocation.getArgument(0);
              inserted.setId(1L);
              return new GameplayCommandRepository.IdempotentInsertResult(inserted, true);
            });

    RuntimeRegionStatus ownership = new RuntimeRegionStatus();
    ownership.setTenantId(1L);
    ownership.setGameInstanceId(2L);
    ownership.setRegionId("region-alpha");
    ownership.setRegionEpoch(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(ownership));

    AutomationGameplayCommandAdmissionSupport.AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                1L,
                2L,
                "region-alpha",
                7L,
                "AUTOMATION",
                "dispatch-1",
                "work-item-1",
                "script-1",
                "patch-1",
                "plugin-1",
                "plugin-v1",
                "SHARED",
                "demo",
                "production",
                17L,
                null,
                null,
                null,
                null,
                null,
                "npc-alpha",
                null,
                null,
                "say hello",
                false,
                null),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertEquals(true, result.accepted());
    org.mockito.ArgumentCaptor<GameplayCommand> commandCaptor =
        org.mockito.ArgumentCaptor.forClass(GameplayCommand.class);
    verify(gameplayCommandRepository).insertIfAbsentByIdempotencyIdentity(commandCaptor.capture());
    GameplayCommand accepted = commandCaptor.getValue();
    assertEquals("npc-alpha", accepted.getTargetEntityId());
    assertNull(accepted.getCharacterId());
    verify(tickService).enqueueCommand(1L, 2L, result.commandId(), "say hello", false);
  }

  @Test
  void rejectsDuplicateWhileAdmissionIsStillAcceptedAndDoesNotQueue() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));

    GameplayCommand inFlight = new GameplayCommand();
    populateAdmissionFields(inFlight, automationRequest());
    inFlight.setCommandId("auto-in-flight");
    inFlight.setExecutionOutcome("ACCEPTED");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.of(inFlight));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            automationRequest(),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertFalse(result.accepted());
    assertEquals("REJECTED", result.admissionOutcome());
    assertEquals("auto-in-flight", result.commandId());
    assertEquals("UNAVAILABLE", result.errorCode());
    assertEquals("Gameplay command admission is still in flight", result.errorMessage());
    verify(gameplayCommandRepository, org.mockito.Mockito.never())
        .insertIfAbsentByIdempotencyIdentity(any());
    verify(tickService, org.mockito.Mockito.never())
        .enqueueCommand(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void reusesStagedCommandReturnedByAtomicInsertConflict() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.empty());

    RuntimeRegionStatus ownership = new RuntimeRegionStatus();
    ownership.setTenantId(1L);
    ownership.setGameInstanceId(2L);
    ownership.setRegionId("region-alpha");
    ownership.setRegionEpoch(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(ownership));

    GameplayCommand staged = new GameplayCommand();
    populateAdmissionFields(staged, automationRequest());
    staged.setCommandId("auto-winner");
    staged.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenReturn(new GameplayCommandRepository.IdempotentInsertResult(staged, false));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            automationRequest(),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertEquals(true, result.accepted());
    assertEquals("DUPLICATE_NOOP", result.admissionOutcome());
    assertEquals("auto-winner", result.commandId());
    verify(tickService, org.mockito.Mockito.never())
        .enqueueCommand(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void reusesDuplicateWhenRoutingSlugsDifferOnlyByCase() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));

    GameplayCommand existing = new GameplayCommand();
    populateAdmissionFields(existing, automationRequest());
    existing.setWorldSlug("DEMO");
    existing.setRealmSlug("PRODUCTION");
    existing.setCommandId("auto-existing");
    existing.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.of(existing));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            automationRequest(),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertEquals(true, result.accepted());
    assertEquals("DUPLICATE_NOOP", result.admissionOutcome());
    assertEquals("auto-existing", result.commandId());
    verify(tickService, org.mockito.Mockito.never())
        .enqueueCommand(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void admitsRoutedCommandOnlyWithTheCurrentCompletePointer() {
    AdmissionResult result =
        admitWithCurrentPointers(List.of(currentPointer("demo", "production", 17L)));

    assertEquals(true, result.accepted());
    assertEquals("ENQUEUED", result.admissionOutcome());
  }

  @Test
  void admitsRoutedCommandWhenCurrentPointerTargetHasWideGameInstanceId() {
    AdmissionRequest request = automationRequestForGameInstance(128L);
    AdmissionResult result =
        admitWithCurrentPointers(
            List.of(currentPointerForGameInstance("demo", "production", 17L, 128L)), request);

    assertEquals(true, result.accepted());
    assertEquals("ENQUEUED", result.admissionOutcome());
  }

  @Test
  void acceptsCurrentPointerRouteWhenSlugsDifferOnlyByCase() {
    AdmissionRequest request =
        new AdmissionRequest(
            1L,
            2L,
            "region-alpha",
            7L,
            "AUTOMATION",
            "dispatch-1",
            "work-item-1",
            "script-1",
            "patch-1",
            "plugin-1",
            "plugin-v1",
            "SHARED",
            "DEMO",
            "PRODUCTION",
            17L,
            null,
            null,
            null,
            null,
            null,
            "npc-alpha",
            null,
            null,
            "say hello",
            false,
            null);

    AdmissionResult result =
        admitWithCurrentPointers(List.of(currentPointer("demo", "production", 17L)), request);

    assertEquals(true, result.accepted());
    assertEquals("ENQUEUED", result.admissionOutcome());
  }

  @Test
  void failsClosedWhenCurrentPointerAuthorityIsAbsent() {
    AdmissionResult result = admitWithCurrentPointers(List.of());

    assertEquals(false, result.accepted());
    assertEquals("RETRY_QUEUED", result.admissionOutcome());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.errorCode());
  }

  @Test
  void failsClosedWhenCurrentPointerAuthorityCannotBeRead() {
    AdmissionRequest request = automationRequest();
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    GameplayAdmissionPointerAuthorityService pointerAuthority =
        mock(GameplayAdmissionPointerAuthorityService.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(request.gameInstanceId());
    instance.setTenantId(request.tenantId());
    when(gameInstanceRepository.findById(request.gameInstanceId()))
        .thenReturn(Optional.of(instance));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                request.tenantId(),
                request.gameInstanceId(),
                request.regionId(),
                request.regionEpoch(),
                request.automationDispatchId()))
        .thenReturn(Optional.empty());
    doThrow(new IllegalStateException("authority store unavailable"))
        .when(pointerAuthority)
        .listByRuntimeTarget(request.tenantId(), request.gameInstanceId());

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            request,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            pointerAuthority,
            tickService);

    assertEquals(false, result.accepted());
    assertEquals("RETRY_QUEUED", result.admissionOutcome());
    assertEquals("AUTH_UNAVAILABLE", result.errorCode());
  }

  @Test
  void failsClosedWhenCurrentPointerAuthorityIsMultiple() {
    AdmissionResult result =
        admitWithCurrentPointers(
            List.of(
                currentPointer("demo", "production", 17L),
                currentPointer("demo", "production", 18L)));

    assertEquals(false, result.accepted());
    assertEquals("RETRY_QUEUED", result.admissionOutcome());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.errorCode());
  }

  @Test
  void failsClosedWhenCurrentPointerAuthorityIsIncomplete() {
    AdmissionResult result =
        admitWithCurrentPointers(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    1L,
                    2L,
                    17L,
                    true,
                    true,
                    false,
                    "",
                    "NONE")));

    assertEquals(false, result.accepted());
    assertEquals("RETRY_QUEUED", result.admissionOutcome());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.errorCode());
  }

  @Test
  void rejectsExplicitRoutingMismatchAsPointerUnavailable() {
    AdmissionRequest request =
        new AdmissionRequest(
            1L,
            2L,
            "region-alpha",
            7L,
            "AUTOMATION",
            "dispatch-1",
            "work-item-1",
            "script-1",
            "patch-1",
            "plugin-1",
            "plugin-v1",
            "SHARED",
            "demo",
            "production",
            16L,
            null,
            null,
            null,
            null,
            null,
            "npc-alpha",
            null,
            null,
            "say hello",
            false,
            null);

    AdmissionResult result =
        admitWithCurrentPointers(List.of(currentPointer("demo", "production", 17L)), request);

    assertEquals(false, result.accepted());
    assertEquals("REJECTED", result.admissionOutcome());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.errorCode());
  }

  @Test
  void reusesExactDuplicateAfterPointerCutoverWithoutFreshAdmission() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthority =
        mock(GameplayAdmissionPointerAuthorityService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    GameplayCommand existing = new GameplayCommand();
    populateAdmissionFields(existing, automationRequest());
    existing.setCommandId("auto-existing");
    existing.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.of(existing));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            automationRequest(),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            pointerAuthority,
            tickService);

    assertEquals(true, result.accepted());
    assertEquals("DUPLICATE_NOOP", result.admissionOutcome());
    assertEquals("auto-existing", result.commandId());
    verify(pointerAuthority, org.mockito.Mockito.never()).listByRuntimeTarget(anyLong(), anyLong());
  }

  @Test
  void reusesDuplicateWhenPlayableStateScopeOnlyDiffersByCaseAndWhitespace() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    GameplayCommand existing = new GameplayCommand();
    populateAdmissionFields(existing, automationRequest());
    existing.setPlayableStateScope(" shared ");
    existing.setCommandId("auto-existing");
    existing.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.of(existing));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            automationRequest(),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertEquals(true, result.accepted());
    assertEquals("DUPLICATE_NOOP", result.admissionOutcome());
    assertEquals("auto-existing", result.commandId());
  }

  @Test
  void rejectsPreReadReuseWhenAnyImmutableAdmissionFieldChanges() {
    for (AdmissionRequest changed : changedAdmissionRequests()) {
      assertPreReadReuseConflict(changed);
    }
  }

  @Test
  void rejectsPreReadReuseWhenScriptPatchVersionChanges() {
    assertPreReadReuseConflict(changedPatchVersionRequest());
  }

  @Test
  void rejectsPreReadReuseWhenDueTickChanges() {
    assertPreReadReuseConflict(changedDueTickRequest());
  }

  private void assertPreReadReuseConflict(AdmissionRequest changed) {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    GameplayCommand existing = new GameplayCommand();
    populateAdmissionFields(existing, automationRequest());
    existing.setCommandId("auto-existing");
    existing.setExecutionOutcome("STAGED");

    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.of(existing));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            changed,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertFalse(result.accepted());
    assertEquals("REJECTED", result.admissionOutcome());
    assertEquals("auto-existing", result.commandId());
    assertEquals("IDEMPOTENCY_CONFLICT", result.errorCode());

    verify(gameplayCommandRepository, org.mockito.Mockito.never())
        .insertIfAbsentByIdempotencyIdentity(any());
    verify(tickService, org.mockito.Mockito.never())
        .enqueueCommand(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void rejectsChangedAdmissionWhenAtomicInsertLosesRace() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.empty());

    RuntimeRegionStatus ownership = new RuntimeRegionStatus();
    ownership.setTenantId(1L);
    ownership.setGameInstanceId(2L);
    ownership.setRegionId("region-alpha");
    ownership.setRegionEpoch(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(ownership));

    GameplayCommand winner = new GameplayCommand();
    populateAdmissionFields(winner, automationRequest());
    winner.setCommandId("auto-winner");
    winner.setCommandText("say a different message");
    winner.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenReturn(new GameplayCommandRepository.IdempotentInsertResult(winner, false));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            automationRequest(),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertFalse(result.accepted());
    assertEquals("REJECTED", result.admissionOutcome());
    assertEquals("IDEMPOTENCY_CONFLICT", result.errorCode());
    assertEquals("auto-winner", result.commandId());
    verify(tickService, org.mockito.Mockito.never())
        .enqueueCommand(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void concurrentExactAndChangedAdmissionsKeepOneRowAndOnlyExactRetryIsReusable() throws Exception {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 2L, "region-alpha", 7L, "dispatch-1"))
        .thenReturn(Optional.empty());
    RuntimeRegionStatus ownership = new RuntimeRegionStatus();
    ownership.setTenantId(1L);
    ownership.setGameInstanceId(2L);
    ownership.setRegionId("region-alpha");
    ownership.setRegionEpoch(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(ownership));

    CountDownLatch exactInserted = new CountDownLatch(1);
    GameplayCommand winner = new GameplayCommand();
    populateAdmissionFields(winner, automationRequest());
    winner.setCommandId("auto-winner");
    winner.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenAnswer(
            invocation -> {
              GameplayCommand candidate = invocation.getArgument(0);
              if ("say hello".equals(candidate.getCommandText())) {
                exactInserted.countDown();
                return new GameplayCommandRepository.IdempotentInsertResult(winner, true);
              }
              if (!exactInserted.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("exact admission did not win the race");
              }
              return new GameplayCommandRepository.IdempotentInsertResult(winner, false);
            });

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AdmissionResult> exact =
          executor.submit(
              () ->
                  AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
                      automationRequest(),
                      gameInstanceRepository,
                      gameplayCommandRepository,
                      runtimeRegionStatusRepository,
                      tickService));
      Future<AdmissionResult> changed =
          executor.submit(
              () ->
                  AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
                      changedAdmissionRequests()[0],
                      gameInstanceRepository,
                      gameplayCommandRepository,
                      runtimeRegionStatusRepository,
                      tickService));

      AdmissionResult exactResult = exact.get(5, TimeUnit.SECONDS);
      AdmissionResult changedResult = changed.get(5, TimeUnit.SECONDS);
      assertEquals("ENQUEUED", exactResult.admissionOutcome());
      assertEquals("REJECTED", changedResult.admissionOutcome());
      assertEquals("IDEMPOTENCY_CONFLICT", changedResult.errorCode());
      assertEquals("auto-winner", changedResult.commandId());
      verify(tickService).enqueueCommand(1L, 2L, "auto-winner", "say hello", false);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsAutomationCommandWhenRoutingBundleIsIncomplete() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
                    new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                        1L,
                        2L,
                        "region-alpha",
                        7L,
                        "AUTOMATION",
                        "dispatch-1",
                        "work-item-1",
                        "script-1",
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "SHARED",
                        "demo",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "character-1",
                        null,
                        null,
                        "say hello",
                        false,
                        null),
                    gameInstanceRepository,
                    gameplayCommandRepository,
                    runtimeRegionStatusRepository,
                    tickService));
    assertEquals(
        "world_slug, realm_slug, pointer_version, and playable_state_scope must be provided together",
        ex.getMessage());
  }

  @Test
  void rejectsDelimiterBearingRemoteFollowupBeforeDurableAcceptance() {
    AdmissionRequest request = remoteFollowupRequest("followup|with-delimiter");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
                    request,
                    gameInstanceRepository,
                    gameplayCommandRepository,
                    runtimeRegionStatusRepository,
                    tickService));

    assertEquals("remote_followup_id cannot contain '|'", ex.getMessage());
    verify(gameplayCommandRepository, org.mockito.Mockito.never())
        .insertIfAbsentByIdempotencyIdentity(any());
    verify(tickService, org.mockito.Mockito.never())
        .enqueueCommand(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void rejectsAutomationCommandWhenRegionEpochIsZero() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
                    new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                        1L,
                        2L,
                        "region-alpha",
                        0L,
                        "AUTOMATION",
                        "dispatch-1",
                        "work-item-1",
                        "script-1",
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "character-1",
                        null,
                        null,
                        "say hello",
                        false,
                        null),
                    gameInstanceRepository,
                    gameplayCommandRepository,
                    runtimeRegionStatusRepository,
                    tickService));

    assertEquals("region_epoch must be positive", ex.getMessage());
  }

  @Test
  void rejectsAutomationCommandWhenRegionOwnershipBelongsToDifferentGameInstance() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));

    RuntimeRegionStatus mismatchedOwnership = new RuntimeRegionStatus();
    mismatchedOwnership.setTenantId(1L);
    mismatchedOwnership.setGameInstanceId(99L);
    mismatchedOwnership.setRegionId("region-alpha");
    mismatchedOwnership.setRegionEpoch(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(mismatchedOwnership));

    AutomationGameplayCommandAdmissionSupport.AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                1L,
                2L,
                "region-alpha",
                7L,
                "AUTOMATION",
                "dispatch-1",
                "work-item-1",
                "script-1",
                "patch-1",
                "plugin-1",
                "plugin-v1",
                "SHARED",
                "demo",
                "production",
                17L,
                null,
                null,
                null,
                null,
                null,
                "character-1",
                null,
                null,
                "say hello",
                false,
                null),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);

    assertFalse(result.accepted());
    assertEquals("OWNERSHIP_UNAVAILABLE", result.admissionOutcome());
    assertEquals("runtime_ownership_not_found", result.errorCode());
    verify(runtimeRegionStatusRepository).findByTenantIdAndRegionId(1L, "region-alpha");
  }

  private static AdmissionRequest automationRequest() {
    return new AdmissionRequest(
        1L,
        2L,
        "region-alpha",
        7L,
        "AUTOMATION",
        "dispatch-1",
        "work-item-1",
        "script-1",
        "patch-1",
        "plugin-1",
        "plugin-v1",
        "SHARED",
        "demo",
        "production",
        17L,
        null,
        null,
        null,
        null,
        null,
        "npc-alpha",
        null,
        null,
        "say hello",
        false,
        null);
  }

  private static AdmissionRequest automationRequestForGameInstance(long gameInstanceId) {
    AdmissionRequest base = automationRequest();
    return new AdmissionRequest(
        base.tenantId(),
        gameInstanceId,
        base.regionId(),
        base.regionEpoch(),
        base.sourceType(),
        base.automationDispatchId(),
        base.automationWorkItemId(),
        base.scriptId(),
        base.scriptPatchVersion(),
        base.pluginId(),
        base.pluginVersionId(),
        base.playableStateScope(),
        base.worldSlug(),
        base.realmSlug(),
        base.pointerVersion(),
        base.originSourceKind(),
        base.originSourceState(),
        base.originSourceOrdinal(),
        base.originSourceDueTickId(),
        base.originSourceDueAtMs(),
        base.targetEntityId(),
        base.remoteCoordinatorId(),
        base.remoteFollowupId(),
        base.command(),
        base.requiresSoloTick(),
        base.dueTickId());
  }

  private static AdmissionRequest remoteFollowupRequest(String remoteFollowupId) {
    return new AdmissionRequest(
        1L,
        2L,
        "region-alpha",
        7L,
        "REMOTE_FOLLOWUP",
        null,
        null,
        null,
        null,
        null,
        null,
        "SHARED",
        "demo",
        "production",
        17L,
        "REMOTE_FOLLOWUP",
        "TARGET_REGION_EXECUTED",
        1L,
        1L,
        null,
        "npc-alpha",
        "coordinator-1",
        remoteFollowupId,
        "say hello",
        false,
        null);
  }

  private static AdmissionRequest[] changedAdmissionRequests() {
    AdmissionRequest base = automationRequest();
    return new AdmissionRequest[] {
      new AdmissionRequest(
          base.tenantId(),
          base.gameInstanceId(),
          base.regionId(),
          base.regionEpoch(),
          base.sourceType(),
          base.automationDispatchId(),
          base.automationWorkItemId(),
          base.scriptId(),
          base.scriptPatchVersion(),
          base.pluginId(),
          base.pluginVersionId(),
          base.playableStateScope(),
          base.worldSlug(),
          base.realmSlug(),
          base.pointerVersion(),
          base.originSourceKind(),
          base.originSourceState(),
          base.originSourceOrdinal(),
          base.originSourceDueTickId(),
          base.originSourceDueAtMs(),
          base.targetEntityId(),
          base.remoteCoordinatorId(),
          base.remoteFollowupId(),
          "say goodbye",
          base.requiresSoloTick(),
          base.dueTickId()),
      new AdmissionRequest(
          base.tenantId(),
          base.gameInstanceId(),
          base.regionId(),
          base.regionEpoch(),
          base.sourceType(),
          base.automationDispatchId(),
          base.automationWorkItemId(),
          base.scriptId(),
          base.scriptPatchVersion(),
          base.pluginId(),
          base.pluginVersionId(),
          base.playableStateScope(),
          base.worldSlug(),
          base.realmSlug(),
          base.pointerVersion(),
          base.originSourceKind(),
          base.originSourceState(),
          base.originSourceOrdinal(),
          base.originSourceDueTickId(),
          base.originSourceDueAtMs(),
          "npc-beta",
          base.remoteCoordinatorId(),
          base.remoteFollowupId(),
          base.command(),
          base.requiresSoloTick(),
          base.dueTickId()),
      new AdmissionRequest(
          base.tenantId(),
          base.gameInstanceId(),
          base.regionId(),
          base.regionEpoch(),
          base.sourceType(),
          base.automationDispatchId(),
          base.automationWorkItemId(),
          base.scriptId(),
          base.scriptPatchVersion(),
          base.pluginId(),
          base.pluginVersionId(),
          base.playableStateScope(),
          base.worldSlug(),
          base.realmSlug(),
          base.pointerVersion(),
          base.originSourceKind(),
          base.originSourceState(),
          base.originSourceOrdinal(),
          base.originSourceDueTickId(),
          base.originSourceDueAtMs(),
          base.targetEntityId(),
          base.remoteCoordinatorId(),
          base.remoteFollowupId(),
          base.command(),
          true,
          base.dueTickId()),
      new AdmissionRequest(
          base.tenantId(),
          base.gameInstanceId(),
          base.regionId(),
          base.regionEpoch(),
          base.sourceType(),
          base.automationDispatchId(),
          base.automationWorkItemId(),
          base.scriptId(),
          base.scriptPatchVersion(),
          base.pluginId(),
          base.pluginVersionId(),
          base.playableStateScope(),
          "other-world",
          base.realmSlug(),
          base.pointerVersion(),
          base.originSourceKind(),
          base.originSourceState(),
          base.originSourceOrdinal(),
          base.originSourceDueTickId(),
          base.originSourceDueAtMs(),
          base.targetEntityId(),
          base.remoteCoordinatorId(),
          base.remoteFollowupId(),
          base.command(),
          base.requiresSoloTick(),
          base.dueTickId())
    };
  }

  private static AdmissionRequest changedPatchVersionRequest() {
    AdmissionRequest base = automationRequest();
    return admissionRequestWithImmutableFields(base, "patch-2", base.dueTickId());
  }

  private static AdmissionRequest changedDueTickRequest() {
    AdmissionRequest base = automationRequest();
    return admissionRequestWithImmutableFields(base, base.scriptPatchVersion(), 99L);
  }

  private static AdmissionRequest admissionRequestWithImmutableFields(
      AdmissionRequest base, String scriptPatchVersion, Long dueTickId) {
    return new AdmissionRequest(
        base.tenantId(),
        base.gameInstanceId(),
        base.regionId(),
        base.regionEpoch(),
        base.sourceType(),
        base.automationDispatchId(),
        base.automationWorkItemId(),
        base.scriptId(),
        scriptPatchVersion,
        base.pluginId(),
        base.pluginVersionId(),
        base.playableStateScope(),
        base.worldSlug(),
        base.realmSlug(),
        base.pointerVersion(),
        base.originSourceKind(),
        base.originSourceState(),
        base.originSourceOrdinal(),
        base.originSourceDueTickId(),
        base.originSourceDueAtMs(),
        base.targetEntityId(),
        base.remoteCoordinatorId(),
        base.remoteFollowupId(),
        base.command(),
        base.requiresSoloTick(),
        dueTickId);
  }

  private static void populateAdmissionFields(GameplayCommand command, AdmissionRequest request) {
    command.setTenantId(request.tenantId());
    command.setGameInstanceId(request.gameInstanceId());
    command.setSessionId(0L);
    command.setCommandName("SAY");
    command.setCommandText(request.command());
    command.setSanitizedCommandText(request.command());
    command.setRequiresSoloTick(request.requiresSoloTick());
    command.setSourceType(request.sourceType());
    command.setAutomationDispatchId(request.automationDispatchId());
    command.setAutomationWorkItemId(request.automationWorkItemId());
    command.setScriptId(request.scriptId());
    command.setScriptPatchVersion(request.scriptPatchVersion());
    command.setPluginId(request.pluginId());
    command.setPluginVersionId(request.pluginVersionId());
    command.setPlayableStateScope(request.playableStateScope());
    command.setWorldSlug(request.worldSlug());
    command.setRealmSlug(request.realmSlug());
    command.setPointerVersion(request.pointerVersion());
    command.setOriginSourceKind(request.originSourceKind());
    command.setOriginSourceState(request.originSourceState());
    command.setOriginSourceOrdinal(request.originSourceOrdinal());
    command.setOriginSourceDueTickId(request.originSourceDueTickId());
    command.setOriginSourceDueAtMs(request.originSourceDueAtMs());
    command.setTargetEntityId(request.targetEntityId());
    command.setRemoteCoordinatorId(request.remoteCoordinatorId());
    command.setRemoteFollowupId(request.remoteFollowupId());
    command.setRegionId(request.regionId());
    command.setRegionEpoch(request.regionEpoch());
    command.setDueTickId(request.dueTickId());
  }

  private static AdmissionResult admitWithCurrentPointers(
      List<GameplayAdmissionPointerSnapshot> currentPointers) {
    return admitWithCurrentPointers(currentPointers, automationRequest());
  }

  private static AdmissionResult admitWithCurrentPointers(
      List<GameplayAdmissionPointerSnapshot> currentPointers, AdmissionRequest request) {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    GameplayCommandRepository gameplayCommandRepository = mock(GameplayCommandRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickService tickService = mock(TickService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthority =
        mock(GameplayAdmissionPointerAuthorityService.class);

    GameInstance instance = new GameInstance();
    instance.setId(request.gameInstanceId());
    instance.setTenantId(request.tenantId());
    when(gameInstanceRepository.findById(request.gameInstanceId()))
        .thenReturn(Optional.of(instance));
    when(pointerAuthority.listByRuntimeTarget(request.tenantId(), request.gameInstanceId()))
        .thenReturn(currentPointers);
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                request.tenantId(),
                request.gameInstanceId(),
                request.regionId(),
                request.regionEpoch(),
                request.automationDispatchId()))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));
    RuntimeRegionStatus ownership = new RuntimeRegionStatus();
    ownership.setTenantId(request.tenantId());
    ownership.setGameInstanceId(request.gameInstanceId());
    ownership.setRegionId(request.regionId());
    ownership.setRegionEpoch(request.regionEpoch());
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(
            request.tenantId(), request.regionId()))
        .thenReturn(Optional.of(ownership));

    return AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
        request,
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        pointerAuthority,
        tickService);
  }

  @Test
  void routedAdmissionRetriesWhenCurrentPointerCasRejectsInsert() {
    AdmissionRequest request = automationRequest();
    GameInstance instance = new GameInstance();
    instance.setId(request.gameInstanceId());
    instance.setTenantId(request.tenantId());
    when(gameInstanceRepository.findById(request.gameInstanceId()))
        .thenReturn(Optional.of(instance));
    when(pointerAuthority.listByRuntimeTarget(request.tenantId(), request.gameInstanceId()))
        .thenReturn(List.of(currentPointer("demo", "production", 17L)));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                request.tenantId(),
                request.gameInstanceId(),
                request.regionId(),
                request.regionEpoch(),
                request.automationDispatchId()))
        .thenReturn(Optional.empty());
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(
            request.tenantId(), request.regionId()))
        .thenReturn(Optional.of(runtimeOwnership(request)));
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenThrow(
            new GameplayCommandRepository.AdmissionPointerUnavailableException(
                "pointer changed before command insert"));

    AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            request,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            pointerAuthority,
            tickService);

    assertFalse(result.accepted());
    assertEquals("RETRY_QUEUED", result.admissionOutcome());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.errorCode());
  }

  private static RuntimeRegionStatus runtimeOwnership(AdmissionRequest request) {
    RuntimeRegionStatus ownership = new RuntimeRegionStatus();
    ownership.setTenantId(request.tenantId());
    ownership.setGameInstanceId(request.gameInstanceId());
    ownership.setRegionId(request.regionId());
    ownership.setRegionEpoch(request.regionEpoch());
    return ownership;
  }

  private static GameplayAdmissionPointerSnapshot currentPointer(
      String worldSlug, String realmSlug, long pointerVersion) {
    return currentPointerForGameInstance(worldSlug, realmSlug, pointerVersion, 2L);
  }

  private static GameplayAdmissionPointerSnapshot currentPointerForGameInstance(
      String worldSlug, String realmSlug, long pointerVersion, long gameInstanceId) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        "Demo",
        realmSlug,
        "Production",
        1L,
        gameInstanceId,
        pointerVersion,
        true,
        true,
        false,
        "SHARED",
        "NONE");
  }

  private void usePointerAuthority() {
    service =
        new DefaultDurableRemoteFollowupExecutionService(
            remoteFollowupRepository,
            remoteCommandCoordinatorRepository,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService,
            remoteFollowupRuntimeService,
            automationScriptingClient,
            pointerAuthority);
  }

  @Test
  void remoteAutomationRejectsStaleTargetRoutingInsteadOfReplacingIt() {
    usePointerAuthority();
    TickEffect effect = commandEffect("automation-followup");
    RemoteFollowup followup = commandFollowup("automation-followup", "enqueue_automation_command");
    RemoteCommandCoordinator coordinator = commandCoordinator("automation-followup");
    configureTargetAdmission(followup, coordinator, "automation");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 9L, "region-target", 8L, "dispatch-1"))
        .thenReturn(Optional.empty());
    when(pointerAuthority.listByRuntimeTarget(1L, 9L))
        .thenReturn(List.of(targetPointer("target-world", "target-realm", 23L)));
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    verify(gameplayCommandRepository, never()).insertIfAbsentByIdempotencyIdentity(any());
    assertEquals("demo", followup.getWorldSlug());
    assertEquals("production", followup.getRealmSlug());
    assertEquals(Long.valueOf(17L), followup.getPointerVersion());
    verify(pointerAuthority).listByRuntimeTarget(1L, 9L);
  }

  @Test
  void remoteGameplayRejectsStaleTargetRoutingInsteadOfReplacingIt() {
    usePointerAuthority();
    TickEffect effect = commandEffect("gameplay-followup");
    RemoteFollowup followup = commandFollowup("gameplay-followup", "enqueue_gameplay_command");
    RemoteCommandCoordinator coordinator = commandCoordinator("gameplay-followup");
    configureTargetAdmission(followup, coordinator, "gameplay");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
                1L, 9L, "region-target", 8L, "gameplay-followup"))
        .thenReturn(Optional.empty());
    when(pointerAuthority.listByRuntimeTarget(1L, 9L))
        .thenReturn(List.of(targetPointer("target-world", "target-realm", 23L)));
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    verify(gameplayCommandRepository, never()).insertIfAbsentByIdempotencyIdentity(any());
    assertEquals("demo", followup.getWorldSlug());
    assertEquals("production", followup.getRealmSlug());
    assertEquals(Long.valueOf(17L), followup.getPointerVersion());
    verify(pointerAuthority).listByRuntimeTarget(1L, 9L);
  }

  @Test
  void remoteAutomationRetriesWithoutTerminalizingWhenTargetPointerAuthorityIsUnavailable() {
    usePointerAuthority();
    TickEffect effect = commandEffect("retry-followup");
    RemoteFollowup followup = commandFollowup("retry-followup", "enqueue_automation_command");
    RemoteCommandCoordinator coordinator = commandCoordinator("retry-followup");
    configureTargetAdmission(followup, coordinator, "automation");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 9L, "region-target", 8L, "dispatch-1"))
        .thenReturn(Optional.empty());
    doThrow(new IllegalStateException("pointer store unavailable"))
        .when(pointerAuthority)
        .listByRuntimeTarget(1L, 9L);

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("RETRY_QUEUED", result.effectStatus());
    assertEquals("AUTH_UNAVAILABLE", result.failureCode());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, followup.getStatus());
    assertEquals("tb-1", followup.getClaimedTickBatchId());
    verify(remoteFollowupRuntimeService, org.mockito.Mockito.never())
        .recordResult(org.mockito.ArgumentMatchers.any());
    verify(remoteFollowupRepository).save(followup);
  }

  @Test
  void exactRemoteGameplayDuplicateSurvivesTargetPointerCutover() {
    usePointerAuthority();
    TickEffect effect = commandEffect("cutover-followup");
    RemoteFollowup followup = commandFollowup("cutover-followup", "enqueue_gameplay_command");
    RemoteCommandCoordinator coordinator = commandCoordinator("cutover-followup");
    configureTargetAdmission(followup, coordinator, "gameplay");
    followup.setPlayableStateScope("ISOLATED");
    GameplayCommand existing = new GameplayCommand();
    existing.setTenantId(1L);
    existing.setGameInstanceId(9L);
    existing.setSourceType("REMOTE_FOLLOWUP");
    existing.setRemoteCoordinatorId(coordinator.getCoordinatorId());
    existing.setRemoteFollowupId(followup.getFollowupId());
    existing.setScriptId(coordinator.getScriptId());
    existing.setScriptPatchVersion(coordinator.getScriptPatchVersion());
    existing.setPluginId(coordinator.getPluginId());
    existing.setPluginVersionId(coordinator.getPluginVersionId());
    existing.setPlayableStateScope("SHARED");
    existing.setWorldSlug("demo");
    existing.setRealmSlug("production");
    existing.setPointerVersion(17L);
    existing.setOriginSourceKind("REMOTE_FOLLOWUP");
    existing.setOriginSourceState("TARGET_REGION_EXECUTED");
    existing.setOriginSourceOrdinal(55L);
    existing.setOriginSourceDueTickId(55L);
    existing.setTargetEntityId("321");
    existing.setCharacterId(321L);
    existing.setCommandName("LOOK");
    existing.setCommandText("LOOK");
    existing.setSanitizedCommandText("LOOK");
    existing.setRequiresSoloTick(false);
    existing.setRegionId("region-target");
    existing.setRegionEpoch(8L);
    existing.setDueTickId(55L);
    existing.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
                1L, 9L, "region-target", 8L, "cutover-followup"))
        .thenReturn(Optional.of(existing));
    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("APPLIED", result.effectStatus());
    verify(pointerAuthority, org.mockito.Mockito.never()).listByRuntimeTarget(1L, 9L);
    verify(gameplayCommandRepository, org.mockito.Mockito.never())
        .insertIfAbsentByIdempotencyIdentity(org.mockito.ArgumentMatchers.any());
  }

  @ParameterizedTest
  @MethodSource("temporarilyUnavailableTargetPointers")
  void remoteGameplayRetriesForInvalidTargetPointerAuthority(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    usePointerAuthority();
    TickEffect effect = commandEffect("invalid-pointer-followup");
    RemoteFollowup followup =
        commandFollowup("invalid-pointer-followup", "enqueue_gameplay_command");
    RemoteCommandCoordinator coordinator = commandCoordinator("invalid-pointer-followup");
    configureTargetAdmission(followup, coordinator, "gameplay");
    when(pointerAuthority.listByRuntimeTarget(1L, 9L)).thenReturn(pointers);

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("RETRY_QUEUED", result.effectStatus());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.failureCode());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, followup.getStatus());
    assertEquals("tb-1", followup.getClaimedTickBatchId());
    verify(remoteFollowupRuntimeService, org.mockito.Mockito.never())
        .recordResult(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void remoteGameplayAbandonsWhenTargetPointerBelongsToDifferentRuntimeTarget() {
    usePointerAuthority();
    TickEffect effect = commandEffect("mismatched-pointer-followup");
    RemoteFollowup followup =
        commandFollowup("mismatched-pointer-followup", "enqueue_gameplay_command");
    RemoteCommandCoordinator coordinator = commandCoordinator("mismatched-pointer-followup");
    configureTargetAdmission(followup, coordinator, "gameplay");
    when(pointerAuthority.listByRuntimeTarget(1L, 9L))
        .thenReturn(
            List.of(
                targetPointerForGameInstance("target-world", "target-realm", 23L, 128L, "SHARED")));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.failureCode());
    verify(remoteFollowupRuntimeService).recordResult(org.mockito.ArgumentMatchers.any());
    verify(gameplayCommandRepository, org.mockito.Mockito.never())
        .insertIfAbsentByIdempotencyIdentity(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void remoteTriggerScriptEventAbandonsWithoutDurablePinTupleAndExactReplaySkipsPointerReread() {
    usePointerAuthority();
    TickEffect effect = commandEffect("trigger-followup");
    RemoteFollowup followup = commandFollowup("trigger-followup", "trigger_script_event");
    followup.setPayloadJson("{\"kind\":\"trigger_script_event\",\"isDryRun\":true}");
    followup.setEventType("onEnterRegion");
    followup.setEventSchemaVersion("v1");
    followup.setScriptEventId("remote-enter-1");
    followup.setTriggerMode("TRIGGER_MODE_NORMAL");
    followup.setReadSnapshotToken("game-session:onEnterRegion:9:8:remote-enter-1");
    followup.setEventPayloadJson("{\"fromRegionId\":\"R-101\",\"toRegionId\":\"R-102\"}");
    RemoteCommandCoordinator coordinator = commandCoordinator("trigger-followup");
    configureTargetAdmission(followup, coordinator, "trigger");
    when(pointerAuthority.listByRuntimeTarget(1L, 9L))
        .thenReturn(List.of(targetPointer("target-world", "target-realm", 23L, "ISOLATED")));
    when(remoteFollowupRuntimeService.recordResult(any()))
        .thenAnswer(
            invocation -> {
              followup.setStatus(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED);
              followup.setFailureCode("REMOTE_SCRIPT_EVENT_PIN_TUPLE_UNAVAILABLE");
              followup.setFailureMessage(
                  "Legacy remote trigger delivery is disabled until durable source and target script pin tuples are available");
              return new RemoteFollowupRuntimeService.ResultOutcome(
                  RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_ABANDONED,
                  RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED,
                  false,
                  false);
            });

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult first =
        service.execute(effect);
    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult replay =
        service.execute(effect);

    assertEquals("ABANDONED", first.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PIN_TUPLE_UNAVAILABLE", first.failureCode());
    assertEquals("ABANDONED", replay.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PIN_TUPLE_UNAVAILABLE", replay.failureCode());
    verify(automationScriptingClient, never()).triggerScriptEvent(any());
    verify(pointerAuthority).listByRuntimeTarget(1L, 9L);
  }

  static Stream<List<GameplayAdmissionPointerSnapshot>> temporarilyUnavailableTargetPointers() {
    return Stream.of(
        List.of(),
        List.of(
            targetPointer("target-world", "target-realm", 23L),
            targetPointer("other", "realm", 24L)),
        List.of(
            new GameplayAdmissionPointerSnapshot(
                "target-world",
                "Target world",
                "target-realm",
                "Target realm",
                1L,
                9L,
                23L,
                true,
                true,
                false,
                "",
                "NONE")));
  }

  private static TickEffect commandEffect(String followupId) {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey(followupId);
    return effect;
  }

  private static RemoteFollowup commandFollowup(String followupId, String payloadKind) {
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId(followupId);
    followup.setTenantId(1L);
    followup.setOriginGameInstanceId(7L);
    followup.setOriginRegionId("region-origin");
    followup.setOriginRegionEpoch(4L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-target");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadKind(payloadKind);
    followup.setPayloadJson("{\"kind\":\"" + payloadKind + "\",\"command\":\"LOOK\"}");
    followup.setRequestedCommand("LOOK");
    return followup;
  }

  private static RemoteCommandCoordinator commandCoordinator(String followupId) {
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setCoordinatorId("coord-" + followupId);
    coordinator.setTenantId(1L);
    coordinator.setFollowupId(followupId);
    coordinator.setOriginGameInstanceId(7L);
    coordinator.setOriginRegionId("region-origin");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setTargetGameInstanceId(9L);
    coordinator.setTargetRegionId("region-target");
    coordinator.setTargetRegionEpoch(8L);
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    return coordinator;
  }

  private void configureTargetAdmission(
      RemoteFollowup followup, RemoteCommandCoordinator coordinator, String payloadKind) {
    when(remoteFollowupRepository.findByFollowupId(followup.getFollowupId()))
        .thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(
            1L, followup.getFollowupId()))
        .thenReturn(Optional.of(coordinator));
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    RuntimeRegionStatus runtimeStatus = new RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(9L);
    runtimeStatus.setRegionId("region-target");
    runtimeStatus.setRegionEpoch(8L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-target"))
        .thenReturn(Optional.of(runtimeStatus));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 9L))
        .thenReturn(Optional.of(runtimeStatus));
    if ("gameplay".equals(payloadKind)) {
      followup.setPayloadJson("{\"kind\":\"enqueue_gameplay_command\",\"command\":\"LOOK\"}");
    }
  }

  private static GameplayAdmissionPointerSnapshot targetPointer(
      String worldSlug, String realmSlug, long pointerVersion) {
    return targetPointer(worldSlug, realmSlug, pointerVersion, "SHARED");
  }

  private static GameplayAdmissionPointerSnapshot targetPointer(
      String worldSlug, String realmSlug, long pointerVersion, String stateScope) {
    return targetPointerForGameInstance(worldSlug, realmSlug, pointerVersion, 9L, stateScope);
  }

  private static GameplayAdmissionPointerSnapshot targetPointerForGameInstance(
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      long gameInstanceId,
      String stateScope) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldSlug,
        realmSlug,
        realmSlug,
        1L,
        gameInstanceId,
        pointerVersion,
        true,
        true,
        false,
        stateScope,
        "NONE");
  }
}
