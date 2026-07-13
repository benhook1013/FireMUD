package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.common.command.CommandEffectDeclarationConstraints;
import net.firedevops.firemud.gamesession.command.text.ActionStateCommandHandler;
import net.firedevops.firemud.gamesession.command.text.AdmittedTextCommandRegistryResolver;
import net.firedevops.firemud.gamesession.command.text.AfkCommandHandler;
import net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandler;
import net.firedevops.firemud.gamesession.command.text.ItemCommandHandler;
import net.firedevops.firemud.gamesession.command.text.MoveCommandHandler;
import net.firedevops.firemud.gamesession.command.text.PreparedMoveCommandResult;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandRegistry;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService;
import net.firedevops.firemud.gamesession.service.DurableGameplayReplayService;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService.MoveEffectApplyResult;
import net.firedevops.firemud.gamesession.service.PlayerOutputDeliveryService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected service collaborators are framework-managed and retained internally")
public final class DefaultDurableGameplayCommandExecutionService
    implements DurableGameplayCommandExecutionService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final MeterRegistry meterRegistry;
  private final TextCommandParser textCommandParser;
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final MoveCommandHandler moveCommandHandler;
  private final ItemCommandHandler itemCommandHandler;
  private final CommunicationCommandHandler communicationCommandHandler;
  private final AfkCommandHandler afkCommandHandler;
  private final ActionStateCommandHandler actionStateCommandHandler;
  private final TextCommandMetadataResolver textCommandMetadataResolver;
  private final DurableGameplayReplayService durableGameplayReplayService;
  private final MovementEffectIdempotencyService movementEffectIdempotencyService;
  private final PlayerOutputDeliveryService playerOutputDeliveryService;
  private final ScriptEventPublisher scriptEventPublisher;

  public DefaultDurableGameplayCommandExecutionService(
      MeterRegistry meterRegistry,
      TextCommandParser textCommandParser,
      SessionAuthenticationService sessionAuthenticationService,
      MoveCommandHandler moveCommandHandler,
      ItemCommandHandler itemCommandHandler,
      CommunicationCommandHandler communicationCommandHandler,
      AfkCommandHandler afkCommandHandler,
      ActionStateCommandHandler actionStateCommandHandler,
      TextCommandMetadataResolver textCommandMetadataResolver,
      DurableGameplayReplayService durableGameplayReplayService,
      MovementEffectIdempotencyService movementEffectIdempotencyService,
      PlayerOutputDeliveryService playerOutputDeliveryService,
      ScriptEventPublisher scriptEventPublisher) {
    this(
        meterRegistry,
        textCommandParser,
        null,
        sessionAuthenticationService,
        moveCommandHandler,
        itemCommandHandler,
        communicationCommandHandler,
        afkCommandHandler,
        actionStateCommandHandler,
        textCommandMetadataResolver,
        durableGameplayReplayService,
        movementEffectIdempotencyService,
        playerOutputDeliveryService,
        scriptEventPublisher);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DefaultDurableGameplayCommandExecutionService(
      MeterRegistry meterRegistry,
      TextCommandParser textCommandParser,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver,
      SessionAuthenticationService sessionAuthenticationService,
      MoveCommandHandler moveCommandHandler,
      ItemCommandHandler itemCommandHandler,
      CommunicationCommandHandler communicationCommandHandler,
      AfkCommandHandler afkCommandHandler,
      ActionStateCommandHandler actionStateCommandHandler,
      TextCommandMetadataResolver textCommandMetadataResolver,
      DurableGameplayReplayService durableGameplayReplayService,
      MovementEffectIdempotencyService movementEffectIdempotencyService,
      PlayerOutputDeliveryService playerOutputDeliveryService,
      ScriptEventPublisher scriptEventPublisher) {
    this.meterRegistry = meterRegistry;
    this.textCommandParser = textCommandParser;
    this.admittedRegistryResolver = admittedRegistryResolver;
    this.sessionAuthenticationService = sessionAuthenticationService;
    this.moveCommandHandler = moveCommandHandler;
    this.itemCommandHandler = itemCommandHandler;
    this.communicationCommandHandler = communicationCommandHandler;
    this.afkCommandHandler = afkCommandHandler;
    this.actionStateCommandHandler = actionStateCommandHandler;
    this.textCommandMetadataResolver = textCommandMetadataResolver;
    this.durableGameplayReplayService = durableGameplayReplayService;
    this.movementEffectIdempotencyService = movementEffectIdempotencyService;
    this.playerOutputDeliveryService = playerOutputDeliveryService;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Override
  public Optional<DurableGameplayCommandExecutionResult> execute(
      TickEffect effect, GameplayCommand command) {
    TextCommand parsed = textCommandParser.parse(command.getCommandText());
    if (resolveRoute(command, parsed, null) == CommandExecutionRoute.IGNORE
        && parsed.type() != TextCommandType.UNKNOWN) {
      return Optional.empty();
    }
    Optional<SessionContext> maybeContext = resolveExecutionContext(command);
    if (maybeContext.isEmpty()) {
      return Optional.of(
          recordResult(
              command,
              new DurableGameplayCommandExecutionResult(
                  "REJECTED",
                  "COMPLETED",
                  "NOT_APPLIED",
                  "SESSION_NOT_FOUND",
                  "Session context no longer exists for command execution")));
    }
    SessionContext context = maybeContext.orElseThrow();
    TextCommandRegistry activeRegistry = null;
    if (admittedRegistryResolver != null) {
      activeRegistry = admittedRegistryResolver.resolve(context);
      parsed = textCommandParser.parse(command.getCommandText(), activeRegistry);
    }
    // An admitted authored snapshot is the execution contract. Do not reclassify it from a
    // later live registry read, otherwise a released-definition change can strand a command.
    CommandExecutionRoute route =
        hasAuthoredActionSnapshot(command)
            ? CommandExecutionRoute.AUTHORED
            : resolveRoute(command, parsed, activeRegistry);
    if (route == CommandExecutionRoute.IGNORE) {
      return Optional.empty();
    }
    TextCommand activeParsed = parsed;
    return switch (route) {
      case ITEM_MUTATION -> {
        publishCommandEventForLiveExecution(context, command);
        yield Optional.of(
            executeItemMutation(context, activeParsed, command, effect.getEffectId()));
      }
      case COMMUNICATION ->
          Optional.of(
              executeReplayBackedLocalMutation(
                  context,
                  command,
                  effect.getEffectId(),
                  () -> {
                    var result =
                        communicationCommandHandler.handle(
                            context, activeParsed, effect.getEffectId());
                    return new ReplayBackedMutationResult(result.commandResult(), result.outputs());
                  }));
      case AFK ->
          Optional.of(
              executeReplayBackedLocalMutation(
                  context,
                  command,
                  effect.getEffectId(),
                  () -> {
                    var result = afkCommandHandler.handle(context, activeParsed);
                    return new ReplayBackedMutationResult(result.commandResult(), result.outputs());
                  }));
      case ACTION_STATE ->
          Optional.of(
              executeReplayBackedLocalMutation(
                  context,
                  command,
                  effect.getEffectId(),
                  () -> {
                    var result =
                        actionStateCommandHandler.handle(
                            context, activeParsed, effect.getEffectId());
                    return new ReplayBackedMutationResult(result.commandResult(), result.outputs());
                  }));
      case AUTHORED ->
          Optional.of(executeAuthoredActionState(context, command, effect.getEffectId()));
      case MOVE -> {
        publishCommandEventForLiveExecution(context, command);
        PreparedMoveCommandResult prepared = moveCommandHandler.prepare(context, activeParsed);
        if (!prepared.commandResult().accepted()) {
          if (prepared.responseOutput() != null) {
            playerOutputDeliveryService.deliver(context, List.of(prepared.responseOutput()), true);
          }
          yield Optional.of(
              recordResult(
                  command,
                  new DurableGameplayCommandExecutionResult(
                      "REJECTED",
                      "COMPLETED",
                      "NOT_APPLIED",
                      prepared.commandResult().errorCode(),
                      prepared.commandResult().errorMessage())));
        }
        MoveEffectApplyResult applyResult =
            movementEffectIdempotencyService.apply(
                effect.getEffectId(), context, prepared.updatedContext().roomInstanceId());
        yield Optional.of(
            recordResult(
                command, resultForApply(applyResult, prepared, context, effect.getEffectId())));
      }
      case IGNORE -> Optional.empty();
    };
  }

  private Optional<SessionContext> resolveExecutionContext(GameplayCommand command) {
    if (command.getSessionId() != null && command.getSessionId() > 0) {
      Optional<SessionContext> bySession =
          sessionAuthenticationService.resolveUnverifiedSessionContext(
              Long.toString(command.getSessionId()));
      if (bySession.isPresent()) {
        return bySession;
      }
    }
    Long characterId =
        GameplayCharacterIdParser.parseGameplayCharacterId(
            command.getCharacterId(), command.getTargetEntityId());
    if (characterId == null
        || characterId <= 0
        || command.getTenantId() == null
        || command.getTenantId() <= 0
        || command.getGameInstanceId() == null
        || command.getGameInstanceId() <= 0) {
      return Optional.empty();
    }
    return sessionAuthenticationService.resolveByGameplayIdentity(
        command.getTenantId(), command.getGameInstanceId(), characterId);
  }

  private DurableGameplayCommandExecutionResult executeItemMutation(
      SessionContext context, TextCommand parsed, GameplayCommand command, String effectId) {
    TextCommandInterpretationResult result = itemCommandHandler.handle(context, parsed, effectId);
    if (!result.outputs().isEmpty()) {
      playerOutputDeliveryService.deliver(context, result.outputs(), true);
    }
    if (result.commandResult().accepted()) {
      return recordResult(
          command,
          new DurableGameplayCommandExecutionResult("APPLIED", "APPLIED", "APPLIED", null, null));
    }
    return recordResult(
        command,
        new DurableGameplayCommandExecutionResult(
            "REJECTED",
            "COMPLETED",
            "NOT_APPLIED",
            result.commandResult().errorCode(),
            result.commandResult().errorMessage()));
  }

  private DurableGameplayCommandExecutionResult executeAuthoredActionState(
      SessionContext context, GameplayCommand command, String effectId) {
    if (!matchesAuthoredCommandIdentity(context, command)) {
      return recordResult(
          command,
          new DurableGameplayCommandExecutionResult(
              "REJECTED",
              "COMPLETED",
              "NOT_APPLIED",
              "STALE_SESSION_CONTEXT",
              "The gameplay session identity changed before the authored action could execute"));
    }
    Optional<AuthoredActionState> actionState = authoredActionState(command);
    if (actionState.isEmpty()) {
      return recordResult(
          command,
          new DurableGameplayCommandExecutionResult(
              "REJECTED",
              "COMPLETED",
              "NOT_APPLIED",
              "AUTHORED_ACTION_SNAPSHOT_INVALID",
              "Authored action declaration snapshot is unavailable or unsupported"));
    }
    AuthoredActionState declaration = actionState.orElseThrow();
    return executeReplayBackedLocalMutation(
        context,
        command,
        effectId,
        () -> {
          var result =
              actionStateCommandHandler.apply(
                  context,
                  declaration.conditionKey(),
                  declaration.duration(),
                  declaration.effectPayloadJson(),
                  effectId,
                  "Action applied.");
          return new ReplayBackedMutationResult(result.commandResult(), result.outputs());
        });
  }

  private boolean matchesAuthoredCommandIdentity(SessionContext context, GameplayCommand command) {
    return command.getTenantId() != null
        && command.getTenantId() > 0L
        && command.getGameInstanceId() != null
        && command.getGameInstanceId() > 0L
        && command.getCharacterId() != null
        && command.getCharacterId() > 0L
        && context.tenantId() == command.getTenantId()
        && context.gameInstanceId() == command.getGameInstanceId()
        && context.characterId() == command.getCharacterId();
  }

  private Optional<AuthoredActionState> authoredActionState(GameplayCommand command) {
    if (command.getAdmittedReleaseBundleId() == null
        || command.getAdmittedReleaseBundleId() <= 0L
        || command.getAdmittedVersionId() == null
        || command.getAdmittedVersionId() <= 0L
        || command.getDeclaredEffectsJson() == null) {
      return Optional.empty();
    }
    try {
      JsonNode effects = JSON.readTree(command.getDeclaredEffectsJson());
      if (!effects.isArray() || effects.size() != 1) {
        return Optional.empty();
      }
      JsonNode effect = effects.get(0);
      JsonNode payload = effect.path("payload");
      JsonNode effectPayload = payload.path("effectPayload");
      if (!effect.isObject()
          || !"APPLY_ACTION_STATE".equals(effect.path("effectKind").asText())
          || !effect.path("schemaVersion").isInt()
          || effect.path("schemaVersion").asInt() != 1
          || !"SELF".equals(effect.path("targeting").asText())
          || !"EFFECT_IDEMPOTENT".equals(effect.path("replayPolicy").asText())
          || !payload.isObject()
          || !isIdentifier(payload.path("conditionKey"))
          || !payload.path("durationSeconds").isInt()
          || !CommandEffectDeclarationConstraints.isValidDurationSeconds(
              payload.path("durationSeconds").asInt())
          || !validActionStatePayload(effectPayload)) {
        return Optional.empty();
      }
      return Optional.of(
          new AuthoredActionState(
              payload.path("conditionKey").asText(),
              java.time.Duration.ofSeconds(payload.path("durationSeconds").asInt()),
              effectPayload.toString()));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private boolean validActionStatePayload(JsonNode effectPayload) {
    JsonNode modifiers = effectPayload.path("modifiers");
    if (!effectPayload.isObject() || !modifiers.isArray()) {
      return false;
    }
    for (JsonNode modifier : modifiers) {
      if (!modifier.isObject()
          || !CommandEffectDeclarationConstraints.SUPPORTED_MODIFIER_OPERATIONS.contains(
              modifier.path("operation").asText())
          || !isIdentifier(modifier.path("target_key"))
          || !modifier.path("value").isNumber()
          || (!modifier.path("priority").isMissingNode() && !modifier.path("priority").isInt())
          || (!modifier.path("scope_kind").isMissingNode()
              && !isIdentifier(modifier.path("scope_kind")))
          || (!modifier.path("scope_key").isMissingNode()
              && !isIdentifier(modifier.path("scope_key")))) {
        return false;
      }
    }
    return true;
  }

  private boolean isIdentifier(JsonNode value) {
    return value.isTextual() && CommandEffectDeclarationConstraints.isIdentifier(value.asText());
  }

  private boolean hasAuthoredActionSnapshot(GameplayCommand command) {
    return command.getDeclaredEffectsJson() != null && !command.getDeclaredEffectsJson().isBlank();
  }

  private DurableGameplayCommandExecutionResult executeReplayBackedLocalMutation(
      SessionContext context,
      GameplayCommand command,
      String effectId,
      Supplier<ReplayBackedMutationResult> liveExecution) {
    Optional<DurableGameplayReplayService.ReplayRecord> replay =
        durableGameplayReplayService.find(context.tenantId(), context.sessionId(), effectId);
    if (replay.isPresent()) {
      DurableGameplayReplayService.ReplayRecord record = replay.orElseThrow();
      if (!record.actorOutputs().isEmpty()) {
        playerOutputDeliveryService.deliver(context, record.actorOutputs(), true);
      }
      return recordResult(command, replayResult(record));
    }
    publishCommandEventForLiveExecution(context, command);
    ReplayBackedMutationResult result = liveExecution.get();
    durableGameplayReplayService.save(
        context.tenantId(),
        context.sessionId(),
        effectId,
        result.commandResult.accepted(),
        result.commandResult.errorCode(),
        result.commandResult.errorMessage(),
        result.outputs());
    if (!result.outputs().isEmpty()) {
      playerOutputDeliveryService.deliver(context, result.outputs(), true);
    }
    return recordResult(command, resultForCommandResult(result.commandResult()));
  }

  private DurableGameplayCommandExecutionResult resultForCommandResult(
      net.firedevops.firemud.gamesession.dto.CommandEnqueueResult commandResult) {
    if (commandResult.accepted()) {
      return new DurableGameplayCommandExecutionResult("APPLIED", "APPLIED", "APPLIED", null, null);
    }
    return new DurableGameplayCommandExecutionResult(
        "REJECTED",
        "COMPLETED",
        "NOT_APPLIED",
        commandResult.errorCode(),
        commandResult.errorMessage());
  }

  private DurableGameplayCommandExecutionResult replayResult(
      DurableGameplayReplayService.ReplayRecord replayRecord) {
    if (replayRecord.accepted()) {
      return new DurableGameplayCommandExecutionResult(
          "REPLAY_NOOP", "APPLIED", "REPLAY_NOOP", null, null);
    }
    return new DurableGameplayCommandExecutionResult(
        "REPLAY_NOOP",
        "COMPLETED",
        "NOT_APPLIED",
        replayRecord.failureCode(),
        replayRecord.failureMessage());
  }

  private DurableGameplayCommandExecutionResult resultForApply(
      MoveEffectApplyResult applyResult,
      PreparedMoveCommandResult prepared,
      SessionContext originalContext,
      String effectId) {
    return switch (applyResult.status()) {
      case APPLIED -> {
        deliverPreparedOutputs(applyResult.context(), prepared);
        publishRegionTransitionEvents(originalContext, applyResult.context(), effectId);
        yield new DurableGameplayCommandExecutionResult(
            "APPLIED", "APPLIED", "APPLIED", null, null);
      }
      case REPLAYED -> {
        deliverPreparedOutputs(applyResult.context(), prepared);
        publishRegionTransitionEvents(originalContext, applyResult.context(), effectId);
        yield new DurableGameplayCommandExecutionResult(
            "REPLAY_NOOP", "APPLIED", "REPLAY_NOOP", null, null);
      }
      case CONFLICT ->
          new DurableGameplayCommandExecutionResult(
              "REJECTED",
              "COMPLETED",
              "NOT_APPLIED",
              "STALE_SESSION_CONTEXT",
              "Movement effect expected an older room snapshot than the current session context");
      case NOT_FOUND ->
          new DurableGameplayCommandExecutionResult(
              "REJECTED",
              "COMPLETED",
              "NOT_APPLIED",
              "SESSION_NOT_FOUND",
              "Session context no longer exists for command execution");
    };
  }

  private void deliverPreparedOutputs(SessionContext context, PreparedMoveCommandResult prepared) {
    if (prepared.responseOutput() == null) {
      return;
    }
    playerOutputDeliveryService.deliver(context, List.of(prepared.responseOutput()), true);
  }

  private void publishRegionTransitionEvents(
      SessionContext originalContext, SessionContext updatedContext, String effectId) {
    scriptEventPublisher.publishRegionTransitionEvents(originalContext, updatedContext, effectId);
  }

  private void publishCommandEventForLiveExecution(
      SessionContext context, GameplayCommand command) {
    scriptEventPublisher.publishCommandEvent(context, command);
  }

  private record ReplayBackedMutationResult(
      CommandEnqueueResult commandResult, List<PlayerOutput> outputs) {}

  private record AuthoredActionState(
      String conditionKey, java.time.Duration duration, String effectPayloadJson) {}

  private CommandExecutionRoute resolveRoute(
      GameplayCommand command, TextCommand parsed, TextCommandRegistry activeRegistry) {
    return resolveMetadata(command, parsed, activeRegistry)
        .map(metadata -> routeForMetadata(metadata, parsed))
        .orElseGet(() -> fallbackRoute(parsed.type()));
  }

  private Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata> resolveMetadata(
      GameplayCommand command, TextCommand parsed, TextCommandRegistry activeRegistry) {
    if (activeRegistry != null) {
      return admittedRegistryResolver.resolveMetadata(
          activeRegistry, parsed.commandId(), command.getCommandName(), parsed.aliasUsed());
    }
    return textCommandMetadataResolver
        .resolve(command.getCommandName())
        .or(() -> textCommandMetadataResolver.resolve(parsed.commandId()))
        .or(() -> textCommandMetadataResolver.resolve(parsed.aliasUsed()));
  }

  private CommandExecutionRoute routeForMetadata(
      TextCommandMetadataResolver.ResolvedTextCommandMetadata metadata, TextCommand parsed) {
    return switch (metadata.dispatchGroup()) {
      case ITEM -> CommandExecutionRoute.ITEM_MUTATION;
      case COMMUNICATION -> CommandExecutionRoute.COMMUNICATION;
      case MOVE -> CommandExecutionRoute.MOVE;
      case AUTHORED -> CommandExecutionRoute.AUTHORED;
      case ACTIVITY ->
          switch (parsed.type()) {
            case AFK -> CommandExecutionRoute.AFK;
            case BLOCK -> CommandExecutionRoute.ACTION_STATE;
            default -> CommandExecutionRoute.IGNORE;
          };
      default -> CommandExecutionRoute.IGNORE;
    };
  }

  private CommandExecutionRoute fallbackRoute(TextCommandType type) {
    return switch (type) {
      case GET, DROP, PUT, TAKE, WEAR, REMOVE -> CommandExecutionRoute.ITEM_MUTATION;
      case SAY, WHISPER, TELL -> CommandExecutionRoute.COMMUNICATION;
      case AFK -> CommandExecutionRoute.AFK;
      case BLOCK -> CommandExecutionRoute.ACTION_STATE;
      case AUTHORED -> CommandExecutionRoute.AUTHORED;
      case MOVE -> CommandExecutionRoute.MOVE;
      default -> CommandExecutionRoute.IGNORE;
    };
  }

  private DurableGameplayCommandExecutionResult recordResult(
      GameplayCommand command, DurableGameplayCommandExecutionResult result) {
    meterRegistry
        .counter(
            "gamesession.durable.effect.execution",
            "command",
            command.getCommandName(),
            "effect_status",
            result.effectStatus(),
            "gameplay_result",
            result.gameplayResult())
        .increment();
    return result;
  }

  private enum CommandExecutionRoute {
    IGNORE,
    ITEM_MUTATION,
    COMMUNICATION,
    AFK,
    ACTION_STATE,
    AUTHORED,
    MOVE
  }
}
