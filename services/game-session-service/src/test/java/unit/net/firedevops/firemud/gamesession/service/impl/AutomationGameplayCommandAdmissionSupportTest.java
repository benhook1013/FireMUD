package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.Test;

class AutomationGameplayCommandAdmissionSupportTest {
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
    when(gameplayCommandRepository.save(any()))
        .thenAnswer(
            invocation -> {
              GameplayCommand inserted = invocation.getArgument(0);
              GameplayCommand reloaded = new GameplayCommand();
              reloaded.setId(1L);
              reloaded.setCommandId(inserted.getCommandId());
              reloaded.setTenantId(inserted.getTenantId());
              reloaded.setGameInstanceId(inserted.getGameInstanceId());
              reloaded.setCommandText(inserted.getCommandText());
              reloaded.setRequiresSoloTick(inserted.isRequiresSoloTick());
              reloaded.setTargetEntityId(inserted.getTargetEntityId());
              reloaded.setCharacterId(inserted.getCharacterId());
              return reloaded;
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
    verify(gameplayCommandRepository).save(commandCaptor.capture());
    GameplayCommand accepted = commandCaptor.getValue();
    assertEquals("npc-alpha", accepted.getTargetEntityId());
    assertNull(accepted.getCharacterId());
    verify(tickService).enqueueCommand(1L, 2L, result.commandId(), "say hello", false);
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
}
