package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.service.impl.AutomationGameplayCommandAdmissionSupport.AdmissionRequest;
import net.firedevops.firemud.gamesession.service.impl.AutomationGameplayCommandAdmissionSupport.AdmissionResult;
import org.junit.jupiter.api.Test;

class AutomationGameplayCommandAdmissionSupportTest {
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
        "world_slug, realm_slug, and pointer_version must be provided together", ex.getMessage());
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
}
