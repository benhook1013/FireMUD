package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.command.text.AdmittedTextCommandRegistryResolver;
import net.firedevops.firemud.gamesession.command.text.BuiltInTextCommandAliasResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver.ResolvedTextCommandMetadata;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.GameplayRuntimeRoomIds;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects shared repository singletons for script-event publishing.")
public class AutomationScriptEventPublisher implements ScriptEventPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(AutomationScriptEventPublisher.class);

  private final AutomationScriptingClient client;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final GameInstanceRepository gameInstanceRepository;
  private final TextCommandMetadataResolver textCommandMetadataResolver;
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver;
  private final BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver;
  private final Executor scriptEventExecutor;

  public AutomationScriptEventPublisher(
      AutomationScriptingClient client,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameInstanceRepository gameInstanceRepository,
      TextCommandMetadataResolver textCommandMetadataResolver,
      BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver,
      @Qualifier("scriptEventExecutor") Executor scriptEventExecutor) {
    this(
        client,
        runtimeRegionStatusRepository,
        gameInstanceRepository,
        textCommandMetadataResolver,
        null,
        builtInTextCommandAliasResolver,
        scriptEventExecutor);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AutomationScriptEventPublisher(
      AutomationScriptingClient client,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameInstanceRepository gameInstanceRepository,
      TextCommandMetadataResolver textCommandMetadataResolver,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver,
      BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver,
      @Qualifier("scriptEventExecutor") Executor scriptEventExecutor) {
    this.client = client;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.gameInstanceRepository = gameInstanceRepository;
    this.textCommandMetadataResolver = textCommandMetadataResolver;
    this.admittedRegistryResolver = admittedRegistryResolver;
    this.builtInTextCommandAliasResolver = builtInTextCommandAliasResolver;
    this.scriptEventExecutor = scriptEventExecutor;
  }

  @Override
  public void publishCommandEvent(SessionContext context, GameplayCommand command) {
    submitBestEffort(
        () -> {
          PublishingScope scope = resolvePublishingScope(context, command);
          if (scope == null) {
            return;
          }
          TriggerScriptEventRequest request =
              TriggerScriptEventRequestFactory.builder(
                      new TriggerScriptEventRequestFactory.CommonFields(
                          scope.tenantId(),
                          scope.gameInstanceId(),
                          scope.regionId(),
                          scope.regionEpoch(),
                          scope.entityId(),
                          "onCommand",
                          "v1",
                          scope.scriptPatchVersion(),
                          command.getCommandId(),
                          false,
                          TriggerMode.TRIGGER_MODE_NORMAL,
                          scope.playableStateScope(),
                          "game-session:onCommand:"
                              + scope.gameInstanceId()
                              + ":"
                              + scope.regionEpoch()
                              + ":"
                              + command.getCommandId(),
                          commandPayload(context, command)),
                      scope.routingBundle())
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
          String previousRoomId = canonicalPayloadRoomId(previousContext.roomInstanceId());
          String currentRoomId = canonicalPayloadRoomId(currentContext.roomInstanceId());
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

  @Override
  public void publishRegionExitEvent(
      SessionContext context, String scriptEventId, String exitReason) {
    submitBestEffort(
        () -> {
          if (context == null || !StringUtils.hasText(scriptEventId)) {
            return;
          }
          PublishingScope scope = resolvePublishingScope(context);
          if (scope == null) {
            return;
          }
          String previousRoomId = canonicalPayloadRoomId(context.roomInstanceId());
          if (!StringUtils.hasText(previousRoomId)) {
            return;
          }
          publishLifecycleEvent(
              scope,
              "onLeaveRegion",
              scriptEventId,
              "game-session:onLeaveRegion:"
                  + scope.gameInstanceId()
                  + ":"
                  + scope.regionEpoch()
                  + ":"
                  + scriptEventId,
              regionExitPayload(previousRoomId, exitReason));
        });
  }

  private String commandPayload(SessionContext context, GameplayCommand command) {
    StringBuilder payload =
        new StringBuilder("{\"commandId\":\"")
            .append(escape(command.getCommandId()))
            .append("\",\"commandName\":\"")
            .append(escape(command.getCommandName()))
            .append("\"");
    if (command.getExecutionHook() != null && !command.getExecutionHook().isBlank()) {
      payload
          .append(",\"executionHook\":\"")
          .append(escape(command.getExecutionHook()))
          .append("\"");
    }
    resolveBuiltInCommandAlias(command)
        .ifPresent(
            alias -> payload.append(",\"commandAlias\":\"").append(escape(alias)).append("\""));
    resolveCommandMetadata(context, command)
        .ifPresent(
            metadata -> {
              payload
                  .append(",\"actionCategory\":\"")
                  .append(metadata.actionCategory().name())
                  .append("\",\"actionTags\":")
                  .append(actionTagsJson(metadata.actionTags()));
            });
    return payload.append("}").toString();
  }

  private Optional<ResolvedTextCommandMetadata> resolveCommandMetadata(
      SessionContext context, GameplayCommand command) {
    if (command == null) {
      return Optional.empty();
    }
    if (admittedRegistryResolver != null && context != null) {
      return admittedRegistryResolver.resolveMetadata(
          context,
          command.getCommandName(),
          firstCommandToken(command.getCommandText()).orElse(null));
    }
    return textCommandMetadataResolver
        .resolve(command.getCommandName())
        .or(
            () ->
                textCommandMetadataResolver.resolve(
                    firstCommandToken(command.getCommandText()).orElse(null)));
  }

  private Optional<String> resolveBuiltInCommandAlias(GameplayCommand command) {
    if (command == null) {
      return Optional.empty();
    }
    return firstCommandToken(command.getCommandText())
        .flatMap(builtInTextCommandAliasResolver::resolve)
        .or(() -> builtInTextCommandAliasResolver.resolve(command.getCommandName()));
  }

  private static Optional<String> firstCommandToken(String commandText) {
    if (commandText == null || commandText.isBlank()) {
      return Optional.empty();
    }
    String trimmed = commandText.trim();
    int firstSpace = trimmed.indexOf(' ');
    return Optional.of(firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace));
  }

  private static String actionTagsJson(List<?> actionTags) {
    StringBuilder payload = new StringBuilder("[");
    for (int i = 0; i < actionTags.size(); i++) {
      if (i > 0) {
        payload.append(',');
      }
      payload.append('"').append(escape(String.valueOf(actionTags.get(i)))).append('"');
    }
    return payload.append(']').toString();
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
        TriggerScriptEventRequestFactory.builder(
                new TriggerScriptEventRequestFactory.CommonFields(
                    scope.tenantId(),
                    scope.gameInstanceId(),
                    scope.regionId(),
                    scope.regionEpoch(),
                    scope.entityId(),
                    eventType,
                    "v1",
                    scope.scriptPatchVersion(),
                    scriptEventId,
                    false,
                    TriggerMode.TRIGGER_MODE_NORMAL,
                    scope.playableStateScope(),
                    readSnapshotToken,
                    payloadJson),
                scope.routingBundle())
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
    return resolvePublishingScope(context, null);
  }

  private PublishingScope resolvePublishingScope(SessionContext context, GameplayCommand command) {
    long tenantId =
        positive(
            command == null ? null : command.getTenantId(),
            context == null ? 0L : context.tenantId());
    long gameInstanceId =
        positive(
            command == null ? null : command.getGameInstanceId(),
            context == null ? 0L : context.gameInstanceId());
    String entityId = resolveEntityId(context, command);
    if (gameInstanceId <= 0 || !StringUtils.hasText(entityId)) {
      return null;
    }
    String scriptPatchVersion =
        gameInstanceRepository
            .findById(gameInstanceId)
            .map(GameInstance::getScriptPatchVersion)
            .filter(StringUtils::hasText)
            .orElse("");
    if (scriptPatchVersion.isBlank()) {
      LOG.debug(
          "Skipping script event publish because no script patch is pinned tenantId={} gameInstanceId={} characterId={}",
          tenantId,
          gameInstanceId,
          entityId);
      return null;
    }
    PublishedRegionScope scopeRegion =
        resolvePublishedRegionScope(command)
            .orElseGet(() -> resolveCurrentRegionScope(tenantId, gameInstanceId).orElse(null));
    if (scopeRegion == null) {
      LOG.debug(
          "Skipping script event publish because runtime ownership is not initialized tenantId={} gameInstanceId={} characterId={}",
          tenantId,
          gameInstanceId,
          entityId);
      return null;
    }
    return new PublishingScope(
        Long.toString(tenantId),
        Long.toString(gameInstanceId),
        scopeRegion.regionId(),
        scopeRegion.regionEpoch(),
        entityId,
        resolvePlayableStateScope(context, command),
        scriptPatchVersion,
        resolveRoutingBundle(context, command));
  }

  private Optional<PublishedRegionScope> resolveCurrentRegionScope(
      long tenantId, long gameInstanceId) {
    return runtimeRegionStatusRepository
        .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
        .map(
            ownership ->
                new PublishedRegionScope(
                    StringUtils.hasText(ownership.getRegionId())
                        ? ownership.getRegionId()
                        : Long.toString(gameInstanceId),
                    ownership.getRegionEpoch()));
  }

  private Optional<PublishedRegionScope> resolvePublishedRegionScope(GameplayCommand command) {
    if (command == null
        || !StringUtils.hasText(command.getRegionId())
        || command.getRegionEpoch() == null
        || command.getRegionEpoch() <= 0) {
      return Optional.empty();
    }
    return Optional.of(new PublishedRegionScope(command.getRegionId(), command.getRegionEpoch()));
  }

  private static String resolveEntityId(SessionContext context, GameplayCommand command) {
    if (command != null && StringUtils.hasText(command.getTargetEntityId())) {
      return command.getTargetEntityId();
    }
    if (command != null && command.getCharacterId() != null && command.getCharacterId() > 0) {
      return Long.toString(command.getCharacterId());
    }
    if (context != null && context.characterId() > 0) {
      return Long.toString(context.characterId());
    }
    return null;
  }

  private static PlayableStateScope resolvePlayableStateScope(
      SessionContext context, GameplayCommand command) {
    if (command != null && StringUtils.hasText(command.getPlayableStateScope())) {
      return resolvePlayableStateScope(command.getPlayableStateScope());
    }
    return context == null
        ? PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
        : resolvePlayableStateScope(context.playableStateScope());
  }

  private static TriggerScriptEventRequestFactory.RoutingBundle resolveRoutingBundle(
      SessionContext context, GameplayCommand command) {
    TriggerScriptEventRequestFactory.RoutingBundle commandBundle =
        completeRoutingBundle(
            command == null ? null : command.getWorldSlug(),
            command == null ? null : command.getRealmSlug(),
            command == null
                    || command.getPointerVersion() == null
                    || command.getPointerVersion() <= 0
                ? null
                : Long.toString(command.getPointerVersion()));
    if (commandBundle != null) {
      return commandBundle;
    }
    TriggerScriptEventRequestFactory.RoutingBundle contextBundle =
        completeRoutingBundle(
            context == null ? null : context.worldSlug(),
            context == null ? null : context.realmSlug(),
            context != null && context.pointerVersion() > 0
                ? Long.toString(context.pointerVersion())
                : null);
    return contextBundle == null
        ? new TriggerScriptEventRequestFactory.RoutingBundle("", "", "")
        : contextBundle;
  }

  private static TriggerScriptEventRequestFactory.RoutingBundle completeRoutingBundle(
      String worldSlug, String realmSlug, String pointerVersion) {
    String normalizedWorldSlug = normalize(worldSlug);
    String normalizedRealmSlug = normalize(realmSlug);
    String normalizedPointerVersion = normalize(pointerVersion);
    boolean hasAll =
        StringUtils.hasText(normalizedWorldSlug)
            && StringUtils.hasText(normalizedRealmSlug)
            && StringUtils.hasText(normalizedPointerVersion);
    if (hasAll) {
      return new TriggerScriptEventRequestFactory.RoutingBundle(
          normalizedWorldSlug, normalizedRealmSlug, normalizedPointerVersion);
    }
    return null;
  }

  private static String regionTransitionPayload(String fromRegionId, String toRegionId) {
    return "{\"fromRegionId\":\""
        + escape(fromRegionId)
        + "\",\"toRegionId\":\""
        + escape(toRegionId)
        + "\"}";
  }

  private static String regionExitPayload(String fromRegionId, String exitReason) {
    StringBuilder payload =
        new StringBuilder()
            .append("{\"fromRegionId\":\"")
            .append(escape(fromRegionId))
            .append("\",\"toRegionId\":\"\"}");
    if (StringUtils.hasText(exitReason)) {
      payload.setLength(payload.length() - 1);
      payload.append(",\"exitReason\":\"").append(escape(exitReason)).append("\"}");
    }
    return payload.toString();
  }

  private static String spawnPayload(String spawnReason) {
    return "{\"spawnReason\":\"" + escape(normalize(spawnReason)) + "\"}";
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static String canonicalPayloadRoomId(String roomInstanceId) {
    String normalized = normalize(roomInstanceId);
    if (!StringUtils.hasText(normalized)) {
      return "";
    }
    try {
      return GameplayRuntimeRoomIds.requireCanonical(normalized, "roomInstanceId");
    } catch (IllegalArgumentException ex) {
      LOG.warn(
          "Skipping script event room id because it is not canonical roomInstanceId={} message={}",
          normalized,
          ex.getMessage());
      return "";
    }
  }

  private record PublishingScope(
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String entityId,
      PlayableStateScope playableStateScope,
      String scriptPatchVersion,
      TriggerScriptEventRequestFactory.RoutingBundle routingBundle) {}

  private record PublishedRegionScope(String regionId, long regionEpoch) {}

  private static PlayableStateScope resolvePlayableStateScope(String playableStateScope) {
    if (!StringUtils.hasText(playableStateScope)) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    }
    return switch (playableStateScope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default ->
          throw new IllegalArgumentException(
              "Unsupported playableStateScope=" + playableStateScope);
    };
  }

  private static long positive(Long value, long fallback) {
    return value != null && value > 0 ? value : fallback;
  }
}
