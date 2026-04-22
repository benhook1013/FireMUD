package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AutomationScriptEventPublisher implements ScriptEventPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(AutomationScriptEventPublisher.class);

  private final AutomationScriptingClient client;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final GameInstanceRepository gameInstanceRepository;

  public AutomationScriptEventPublisher(
      AutomationScriptingClient client,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameInstanceRepository gameInstanceRepository) {
    this.client = client;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.gameInstanceRepository = gameInstanceRepository;
  }

  @Override
  public void publishCommandEvent(SessionContext context, GameplayCommand command) {
    if (context == null || context.gameInstanceId() <= 0 || context.characterId() <= 0) {
      return;
    }
    String scriptPatchVersion =
        gameInstanceRepository
            .findById(context.gameInstanceId())
            .map(GameInstance::getScriptPatchVersion)
            .filter(value -> !value.isBlank())
            .orElse("");
    if (scriptPatchVersion.isBlank()) {
      LOG.debug(
          "Skipping script onCommand event because no script patch is pinned tenantId={} gameInstanceId={} commandId={}",
          context.tenantId(),
          context.gameInstanceId(),
          command.getCommandId());
      return;
    }
    RuntimeRegionStatus ownership =
        runtimeRegionStatusRepository
            .findByTenantIdAndGameInstanceId(context.tenantId(), context.gameInstanceId())
            .orElse(null);
    if (ownership == null) {
      LOG.debug(
          "Skipping script onCommand event because runtime ownership is not initialized tenantId={} gameInstanceId={} commandId={}",
          context.tenantId(),
          context.gameInstanceId(),
          command.getCommandId());
      return;
    }
    TriggerScriptEventRequest request =
        TriggerScriptEventRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setGameInstanceId(Long.toString(context.gameInstanceId()))
            .setRegionId(Long.toString(context.gameInstanceId()))
            .setRegionEpoch(ownership.getRegionEpoch())
            .setEntityId(Long.toString(context.characterId()))
            .setEventType("onCommand")
            .setEventSchemaVersion("v1")
            .setScriptPatchVersion(scriptPatchVersion)
            .setScriptEventId(command.getCommandId())
            .setTriggerMode(TriggerMode.TRIGGER_MODE_NORMAL)
            .setReadSnapshotToken(
                "game-session:onCommand:"
                    + context.gameInstanceId()
                    + ":"
                    + ownership.getRegionEpoch()
                    + ":"
                    + command.getCommandId())
            .setPayloadJson(commandPayload(command))
            .build();
    TriggerScriptEventResponse response = client.triggerScriptEvent(request);
    if (response.hasError()) {
      LOG.warn(
          "Script onCommand event was not admitted commandId={} code={} message={}",
          command.getCommandId(),
          response.getError().getCode(),
          response.getError().getMessage());
    }
  }

  private static String commandPayload(GameplayCommand command) {
    return "{\"commandId\":\""
        + escape(command.getCommandId())
        + "\",\"commandName\":\""
        + escape(command.getCommandName())
        + "\"}";
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
