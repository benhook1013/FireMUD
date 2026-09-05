package net.firedevops.firemud.gamesession.service.impl;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.client.AutomationScriptingControlPlaneClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import org.springframework.stereotype.Service;

@Service
final class GameplayCommandExecutionFenceService {
  private final GameInstanceRepository gameInstanceRepository;
  private final AutomationScriptingControlPlaneClient automationScriptingControlPlaneClient;

  GameplayCommandExecutionFenceService(
      GameInstanceRepository gameInstanceRepository,
      AutomationScriptingControlPlaneClient automationScriptingControlPlaneClient) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.automationScriptingControlPlaneClient = automationScriptingControlPlaneClient;
  }

  Optional<FenceFailure> validate(TickBatch batch, GameplayCommand command) {
    if (!Objects.equals(batch.getTenantId(), command.getTenantId())
        || !Objects.equals(batch.getGameInstanceId(), command.getGameInstanceId())
        || !Objects.equals(batch.getRegionId(), command.getRegionId())
        || batch.getRegionEpoch() != command.getRegionEpoch()) {
      return failure(
          "STALE_COMMAND_TIMELINE",
          "Gameplay command runtime scope does not match the current tick timeline");
    }

    GameInstance instance = gameInstanceRepository.findById(batch.getGameInstanceId()).orElse(null);
    if (instance == null || !Objects.equals(instance.getTenantId(), batch.getTenantId())) {
      return failure(
          "GAME_INSTANCE_NOT_FOUND",
          "Gameplay command game instance is unavailable for execution fencing");
    }

    String sourceType = normalize(command.getSourceType()).toUpperCase(Locale.ROOT);
    boolean localAutomationCommand =
        "AUTOMATION".equals(sourceType) && normalize(command.getRemoteFollowupId()).isEmpty();
    String commandPatch = normalize(command.getScriptPatchVersion());
    if (localAutomationCommand && commandPatch.isEmpty()) {
      return failure(
          "INCOMPLETE_SCRIPT_PATCH_FENCE",
          "Automation gameplay command must include its admitted script patch");
    }
    if (localAutomationCommand
        && (command.getScriptPinEpoch() == null
            || command.getScriptPinEpoch() <= 0
            || normalize(command.getScriptPinControlPlaneRequestId()).isEmpty())) {
      return failure(
          "INCOMPLETE_SCRIPT_PIN_FENCE",
          "Automation gameplay command must include its complete script pin tuple");
    }

    if (localAutomationCommand) {
      String currentPatch = normalize(instance.getScriptPatchVersion());
      Long currentEpoch = instance.getScriptPinEpoch();
      String currentRequestId = normalize(instance.getScriptPatchPinnedControlPlaneRequestId());
      boolean semanticUnpinned =
          currentPatch.isEmpty() && currentEpoch == null && currentRequestId.isEmpty();
      if (semanticUnpinned) {
        return failure(
            "STALE_SCRIPT_PIN_EPOCH",
            "Automation gameplay command cannot execute against an unpinned game instance");
      }
      if (currentPatch.isEmpty()
          || currentEpoch == null
          || currentEpoch <= 0
          || currentRequestId.isEmpty()) {
        return failure(
            "INCOMPLETE_SCRIPT_PIN_FENCE",
            "Game instance does not have a complete current script pin tuple");
      }
      if (!commandPatch.equals(currentPatch)) {
        return failure(
            "STALE_SCRIPT_PATCH_VERSION",
            "Gameplay command script patch no longer matches the pinned instance patch");
      }
      if (!command.getScriptPinEpoch().equals(currentEpoch)) {
        return failure(
            "STALE_SCRIPT_PIN_EPOCH",
            "Gameplay command script pin epoch no longer matches the pinned instance epoch");
      }
      if (!normalize(command.getScriptPinControlPlaneRequestId()).equals(currentRequestId)) {
        return failure(
            "STALE_SCRIPT_PIN_REQUEST_ID",
            "Gameplay command script pin request identity no longer matches the pinned instance");
      }
    }

    if (!commandPatch.isEmpty()
        && !commandPatch.equals(normalize(instance.getScriptPatchVersion()))) {
      return failure(
          "STALE_SCRIPT_PATCH_VERSION",
          "Gameplay command script patch no longer matches the pinned instance patch");
    }

    String pluginId = normalize(command.getPluginId());
    String pluginVersionId = normalize(command.getPluginVersionId());
    if (pluginId.isEmpty() != pluginVersionId.isEmpty()) {
      return failure(
          "INCOMPLETE_PLUGIN_VERSION_FENCE",
          "Gameplay command plugin identity must include both plugin id and version");
    }
    if (pluginId.isEmpty()) {
      return Optional.empty();
    }

    GetPluginStatusResponse status =
        automationScriptingControlPlaneClient.getPluginStatus(
            batch.getTenantId(), batch.getGameInstanceId(), pluginId);
    if (status.hasError()) {
      if ("AUTOMATION_SCRIPTING_UNAVAILABLE".equals(status.getError().getCode())) {
        throw new TickQueueControlService.QueueUnavailableException(
            "Plugin execution fence unavailable: " + status.getError().getCode());
      }
      return failure(
          "STALE_PLUGIN_VERSION",
          "Gameplay command plugin is no longer available for this game instance");
    }
    if (status.getPluginState() != PluginState.PLUGIN_STATE_ENABLED
        || !pluginVersionId.equals(status.getActivePluginVersionId())
        || !Objects.equals(normalize(status.getRuntimeRegionId()), normalize(batch.getRegionId()))
        || status.getRuntimeRegionEpoch() != batch.getRegionEpoch()) {
      return failure(
          "STALE_PLUGIN_VERSION",
          "Gameplay command plugin version is no longer active for this runtime scope");
    }
    return Optional.empty();
  }

  private static Optional<FenceFailure> failure(String code, String message) {
    return Optional.of(new FenceFailure(code, message));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  record FenceFailure(String code, String message) {}
}
