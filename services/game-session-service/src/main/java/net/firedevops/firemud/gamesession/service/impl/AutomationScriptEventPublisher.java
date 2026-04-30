package net.firedevops.firemud.gamesession.service.impl;

import java.util.concurrent.Executor;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AutomationScriptEventPublisher implements ScriptEventPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(AutomationScriptEventPublisher.class);

  private final AutomationScriptingClient client;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final GameInstanceRepository gameInstanceRepository;
  private final Executor scriptEventExecutor;

  public AutomationScriptEventPublisher(
      AutomationScriptingClient client,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameInstanceRepository gameInstanceRepository,
      @Qualifier("scriptEventExecutor") Executor scriptEventExecutor) {
    this.client = client;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.gameInstanceRepository = gameInstanceRepository;
    this.scriptEventExecutor = scriptEventExecutor;
  }

  @Override
  public void publishCommandEvent(SessionContext context, GameplayCommand command) {
    submitBestEffort(
        () -> {
          PublishingScope scope = resolvePublishingScope(context);
          if (scope == null) {
            return;
          }
          TriggerScriptEventRequest request =
              TriggerScriptEventRequest.newBuilder()
                  .setTenantId(scope.tenantId())
                  .setGameInstanceId(scope.gameInstanceId())
                  .setRegionId(scope.regionId())
                  .setRegionEpoch(scope.regionEpoch())
                  .setEntityId(scope.entityId())
                  .setEventType("onCommand")
                  .setEventSchemaVersion("v1")
                  .setScriptPatchVersion(scope.scriptPatchVersion())
                  .setScriptEventId(command.getCommandId())
                  .setTriggerMode(TriggerMode.TRIGGER_MODE_NORMAL)
                  .setPlayableStateScope(scope.playableStateScope())
                  .setWorldSlug(scope.worldSlug())
                  .setRealmSlug(scope.realmSlug())
                  .setPointerVersion(scope.pointerVersion())
                  .setReadSnapshotToken(
                      "game-session:onCommand:"
                          + scope.gameInstanceId()
                          + ":"
                          + scope.regionEpoch()
                          + ":"
                          + command.getCommandId())
                  .setPayloadJson(commandPayload(command))
                  .build();
          logIfNotAdmitted(
              client.triggerScriptEvent(request),
              "onCommand",
              scope.gameInstanceId(),
              command.getCommandId());
        });
  }

  @Override
  public void publishSpawnEvent(SessionContext context, String spawnReason, String scriptEventId) {
    submitBestEffort(
        () -> {
          if (!StringUtils.hasText(scriptEventId)) {
            return;
          }
          PublishingScope scope = resolvePublishingScope(context);
          if (scope == null) {
            return;
          }
          publishLifecycleEvent(
              scope,
              "onSpawn",
              scriptEventId,
              "game-session:onSpawn:"
                  + scope.gameInstanceId()
                  + ":"
                  + scope.regionEpoch()
                  + ":"
                  + scriptEventId,
              spawnPayload(spawnReason));
        });
  }

  @Override
  public void publishRegionTransitionEvents(
      SessionContext previousContext, SessionContext currentContext, String effectId) {
    submitBestEffort(
        () -> {
          if (previousContext == null || currentContext == null || !StringUtils.hasText(effectId)) {
            return;
          }
          PublishingScope scope = resolvePublishingScope(currentContext);
          if (scope == null) {
            return;
          }
          String previousRoomId = normalize(previousContext.roomInstanceId());
          String currentRoomId = normalize(currentContext.roomInstanceId());
          if (!StringUtils.hasText(previousRoomId)
              || !StringUtils.hasText(currentRoomId)
              || previousRoomId.equals(currentRoomId)) {
            return;
          }
          publishLifecycleEvent(
              scope,
              "onLeaveRegion",
              effectId + ":leave",
              "game-session:onLeaveRegion:"
                  + scope.gameInstanceId()
                  + ":"
                  + scope.regionEpoch()
                  + ":"
                  + effectId,
              regionTransitionPayload(previousRoomId, currentRoomId));
          publishLifecycleEvent(
              scope,
              "onEnterRegion",
              effectId + ":enter",
              "game-session:onEnterRegion:"
                  + scope.gameInstanceId()
                  + ":"
                  + scope.regionEpoch()
                  + ":"
                  + effectId,
              regionTransitionPayload(previousRoomId, currentRoomId));
        });
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

  private void publishLifecycleEvent(
      PublishingScope scope,
      String eventType,
      String scriptEventId,
      String readSnapshotToken,
      String payloadJson) {
    TriggerScriptEventRequest request =
        TriggerScriptEventRequest.newBuilder()
            .setTenantId(scope.tenantId())
            .setGameInstanceId(scope.gameInstanceId())
            .setRegionId(scope.regionId())
            .setRegionEpoch(scope.regionEpoch())
            .setEntityId(scope.entityId())
            .setEventType(eventType)
            .setEventSchemaVersion("v1")
            .setScriptPatchVersion(scope.scriptPatchVersion())
            .setScriptEventId(scriptEventId)
            .setTriggerMode(TriggerMode.TRIGGER_MODE_NORMAL)
            .setPlayableStateScope(scope.playableStateScope())
            .setWorldSlug(scope.worldSlug())
            .setRealmSlug(scope.realmSlug())
            .setPointerVersion(scope.pointerVersion())
            .setReadSnapshotToken(readSnapshotToken)
            .setPayloadJson(payloadJson)
            .build();
    logIfNotAdmitted(
        client.triggerScriptEvent(request), eventType, scope.gameInstanceId(), scriptEventId);
  }

  private void logIfNotAdmitted(
      TriggerScriptEventResponse response,
      String eventType,
      String gameInstanceId,
      String scriptEventId) {
    if (!response.hasError()) {
      return;
    }
    LOG.warn(
        "Script {} event was not admitted gameInstanceId={} scriptEventId={} code={} message={}",
        eventType,
        gameInstanceId,
        scriptEventId,
        response.getError().getCode(),
        response.getError().getMessage());
  }

  private void submitBestEffort(Runnable publishAction) {
    try {
      scriptEventExecutor.execute(
          () -> {
            try {
              publishAction.run();
            } catch (RuntimeException ex) {
              LOG.warn("Script event publish task failed", ex);
            }
          });
    } catch (RuntimeException ex) {
      LOG.warn("Script event publish submission failed", ex);
    }
  }

  private PublishingScope resolvePublishingScope(SessionContext context) {
    if (context == null || context.gameInstanceId() <= 0 || context.characterId() <= 0) {
      return null;
    }
    String scriptPatchVersion =
        gameInstanceRepository
            .findById(context.gameInstanceId())
            .map(GameInstance::getScriptPatchVersion)
            .filter(StringUtils::hasText)
            .orElse("");
    if (scriptPatchVersion.isBlank()) {
      LOG.debug(
          "Skipping script event publish because no script patch is pinned tenantId={} gameInstanceId={} characterId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId());
      return null;
    }
    RuntimeRegionStatus ownership =
        runtimeRegionStatusRepository
            .findByTenantIdAndGameInstanceId(context.tenantId(), context.gameInstanceId())
            .orElse(null);
    if (ownership == null) {
      LOG.debug(
          "Skipping script event publish because runtime ownership is not initialized tenantId={} gameInstanceId={} characterId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId());
      return null;
    }
    return new PublishingScope(
        Long.toString(context.tenantId()),
        Long.toString(context.gameInstanceId()),
        StringUtils.hasText(ownership.getRegionId())
            ? ownership.getRegionId()
            : Long.toString(context.gameInstanceId()),
        ownership.getRegionEpoch(),
        Long.toString(context.characterId()),
        resolvePlayableStateScope(context),
        scriptPatchVersion,
        normalize(context.worldSlug()),
        normalize(context.realmSlug()),
        context.pointerVersion() > 0 ? Long.toString(context.pointerVersion()) : "");
  }

  private static String regionTransitionPayload(String fromRegionId, String toRegionId) {
    return "{\"fromRegionId\":\""
        + escape(fromRegionId)
        + "\",\"toRegionId\":\""
        + escape(toRegionId)
        + "\"}";
  }

  private static String spawnPayload(String spawnReason) {
    return "{\"spawnReason\":\"" + escape(normalize(spawnReason)) + "\"}";
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private record PublishingScope(
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String entityId,
      PlayableStateScope playableStateScope,
      String scriptPatchVersion,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {}

  private static PlayableStateScope resolvePlayableStateScope(SessionContext context) {
    if (!StringUtils.hasText(context.playableStateScope())) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    }
    return switch (context.playableStateScope()) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default ->
          throw new IllegalArgumentException(
              "Unsupported playableStateScope=" + context.playableStateScope());
    };
  }
}
