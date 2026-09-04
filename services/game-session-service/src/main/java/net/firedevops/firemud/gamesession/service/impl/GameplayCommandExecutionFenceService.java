package net.firedevops.firemud.gamesession.service.impl;

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

    String commandPatch = normalize(command.getScriptPatchVersion());
    boolean automationCommand = "AUTOMATION".equals(normalize(command.getSourceType()));
    if (automationCommand && commandPatch.isEmpty()) {
      return failure(
          "INCOMPLETE_SCRIPT_PATCH_FENCE",
          "Automation gameplay command must include its admitted script patch");
    }
    if (automationCommand
        && (command.getScriptPinEpoch() == null || command.getScriptPinEpoch() <= 0)) {
      return failure(
          "INCOMPLETE_SCRIPT_PIN_FENCE",
          "Automation gameplay command must include its admitted script pin epoch");
    }
    if (!commandPatch.isEmpty()
        && !commandPatch.equals(normalize(instance.getScriptPatchVersion()))) {
      return failure(
          "STALE_SCRIPT_PATCH_VERSION",
          "Gameplay command script patch no longer matches the pinned instance patch");
    }
    if (automationCommand
        && (instance.getScriptPinEpoch() == null
            || instance.getScriptPinEpoch() <= 0
            || !command.getScriptPinEpoch().equals(instance.getScriptPinEpoch()))) {
      return failure(
          "STALE_SCRIPT_PIN_EPOCH",
          "Gameplay command script pin epoch no longer matches the pinned instance epoch");
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
