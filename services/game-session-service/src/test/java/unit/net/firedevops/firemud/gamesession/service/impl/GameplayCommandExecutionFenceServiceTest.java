package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.client.AutomationScriptingControlPlaneClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameplayCommandExecutionFenceServiceTest {
  private GameInstanceRepository gameInstanceRepository;
  private AutomationScriptingControlPlaneClient automationScriptingControlPlaneClient;
  private GameplayCommandExecutionFenceService service;

  @BeforeEach
  void setup() {
    gameInstanceRepository = mock(GameInstanceRepository.class);
    automationScriptingControlPlaneClient = mock(AutomationScriptingControlPlaneClient.class);
    service =
        new GameplayCommandExecutionFenceService(
            gameInstanceRepository, automationScriptingControlPlaneClient);
  }

  @Test
  void acceptsCurrentTimelineAndScriptPatchWithoutPluginFence() {
    TickBatch batch = batch();
    GameplayCommand command = command();
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance("patch-1")));

    assertTrue(service.validate(batch, command).isEmpty());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void acceptsLocalAutomationCommandWithExactCurrentScriptPinTuple() {
    GameplayCommand command = automationCommand();
    command.setSourceType(" aUtOmAtIoN ");
    when(gameInstanceRepository.findById(2L))
        .thenReturn(Optional.of(pinnedInstance("patch-1", 4L, "request-1")));

    assertTrue(service.validate(batch(), command).isEmpty());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsCommandFromOldRuntimeTimeline() {
    GameplayCommand command = command();
    command.setRegionEpoch(6L);

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("STALE_COMMAND_TIMELINE", failure.code());
    verifyNoInteractions(gameInstanceRepository, automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsCommandFromOldScriptPatch() {
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance("patch-2")));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command()).orElseThrow();

    assertEquals("STALE_SCRIPT_PATCH_VERSION", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsAutomationCommandWithoutScriptPatchFence() {
    GameplayCommand command = command();
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion(null);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance("patch-1")));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("INCOMPLETE_SCRIPT_PATCH_FENCE", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsLocalAutomationCommandWithoutCompleteScriptPinTuple() {
    GameplayCommand command = automationCommand();
    command.setScriptPinEpoch(null);
    command.setScriptPinControlPlaneRequestId(null);
    when(gameInstanceRepository.findById(2L))
        .thenReturn(Optional.of(pinnedInstance("patch-1", 4L, "request-1")));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("INCOMPLETE_SCRIPT_PIN_FENCE", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsLocalAutomationCommandWithoutScriptPinEpoch() {
    GameplayCommand command = automationCommand();
    command.setScriptPinEpoch(null);
    when(gameInstanceRepository.findById(2L))
        .thenReturn(Optional.of(pinnedInstance("patch-1", 4L, "request-1")));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("INCOMPLETE_SCRIPT_PIN_FENCE", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsLocalAutomationCommandWithoutScriptPinOwnerRequestId() {
    GameplayCommand command = automationCommand();
    command.setScriptPinControlPlaneRequestId(null);
    when(gameInstanceRepository.findById(2L))
        .thenReturn(Optional.of(pinnedInstance("patch-1", 4L, "request-1")));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("INCOMPLETE_SCRIPT_PIN_FENCE", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsLocalAutomationCommandAgainstCurrentSemanticUnpinnedInstance() {
    GameplayCommand command = automationCommand();
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance(null)));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("STALE_SCRIPT_PIN_EPOCH", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsLocalAutomationCommandFromDifferentScriptPinEpoch() {
    GameplayCommand command = automationCommand();
    command.setScriptPinEpoch(3L);
    when(gameInstanceRepository.findById(2L))
        .thenReturn(Optional.of(pinnedInstance("patch-1", 4L, "request-1")));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("STALE_SCRIPT_PIN_EPOCH", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void rejectsLocalAutomationCommandFromDifferentScriptPinOwnerRequest() {
    GameplayCommand command = automationCommand();
    command.setScriptPinControlPlaneRequestId("request-old");
    when(gameInstanceRepository.findById(2L))
        .thenReturn(Optional.of(pinnedInstance("patch-1", 4L, "request-1")));

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("STALE_SCRIPT_PIN_REQUEST_ID", failure.code());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void leavesRemoteFollowupCommandsOutsideLocalAutomationPinTupleFence() {
    GameplayCommand command = command();
    command.setSourceType("REMOTE_FOLLOWUP");
    command.setRemoteFollowupId("followup-1");
    command.setScriptPatchVersion(null);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance(null)));

    assertTrue(service.validate(batch(), command).isEmpty());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void leavesAutomationCommandsWithRemoteFollowupIdentityOutsideLocalPinTupleFence() {
    GameplayCommand command = command();
    command.setSourceType("AUTOMATION");
    command.setRemoteFollowupId("followup-1");
    command.setScriptPatchVersion(null);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance(null)));

    assertTrue(service.validate(batch(), command).isEmpty());
    verifyNoInteractions(automationScriptingControlPlaneClient);
  }

  @Test
  void acceptsEnabledPluginVersionForCurrentRuntimeScope() {
    GameplayCommand command = command();
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance("patch-1")));
    when(automationScriptingControlPlaneClient.getPluginStatus(1L, 2L, "plugin-1"))
        .thenReturn(
            GetPluginStatusResponse.newBuilder()
                .setPluginState(PluginState.PLUGIN_STATE_ENABLED)
                .setActivePluginVersionId("plugin-v1")
                .setRuntimeRegionId("region-alpha")
                .setRuntimeRegionEpoch(7L)
                .build());

    assertTrue(service.validate(batch(), command).isEmpty());
  }

  @Test
  void rejectsInactivePluginVersion() {
    GameplayCommand command = command();
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance("patch-1")));
    when(automationScriptingControlPlaneClient.getPluginStatus(1L, 2L, "plugin-1"))
        .thenReturn(
            GetPluginStatusResponse.newBuilder()
                .setPluginState(PluginState.PLUGIN_STATE_DISABLED)
                .setActivePluginVersionId("plugin-v1")
                .setRuntimeRegionId("region-alpha")
                .setRuntimeRegionEpoch(7L)
                .build());

    GameplayCommandExecutionFenceService.FenceFailure failure =
        service.validate(batch(), command).orElseThrow();

    assertEquals("STALE_PLUGIN_VERSION", failure.code());
  }

  @Test
  void retriesWhenPluginAuthorityIsUnavailable() {
    GameplayCommand command = command();
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance("patch-1")));
    when(automationScriptingControlPlaneClient.getPluginStatus(1L, 2L, "plugin-1"))
        .thenReturn(
            GetPluginStatusResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("AUTOMATION_SCRIPTING_UNAVAILABLE")
                        .setMessage("unavailable"))
                .build());

    assertThrows(
        TickQueueControlService.QueueUnavailableException.class,
        () -> service.validate(batch(), command));
  }

  private static TickBatch batch() {
    TickBatch batch = new TickBatch();
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("region-alpha");
    batch.setRegionEpoch(7L);
    return batch;
  }

  private static GameplayCommand command() {
    GameplayCommand command = new GameplayCommand();
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setRegionId("region-alpha");
    command.setRegionEpoch(7L);
    command.setScriptPatchVersion("patch-1");
    return command;
  }

  private static GameplayCommand automationCommand() {
    GameplayCommand command = command();
    command.setSourceType("AUTOMATION");
    command.setScriptPinEpoch(4L);
    command.setScriptPinControlPlaneRequestId("request-1");
    return command;
  }

  private static GameInstance instance(String scriptPatchVersion) {
    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(1L);
    instance.setScriptPatchVersion(scriptPatchVersion);
    return instance;
  }

  private static GameInstance pinnedInstance(
      String scriptPatchVersion, long scriptPinEpoch, String controlPlaneRequestId) {
    GameInstance instance = instance(scriptPatchVersion);
    instance.setScriptPinEpoch(scriptPinEpoch);
    instance.setScriptPatchPinnedControlPlaneRequestId(controlPlaneRequestId);
    return instance;
  }
}
