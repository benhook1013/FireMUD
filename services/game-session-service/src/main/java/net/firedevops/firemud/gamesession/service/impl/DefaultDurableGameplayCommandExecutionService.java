package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.command.text.ActionStateCommandHandler;
import net.firedevops.firemud.gamesession.command.text.AfkCommandHandler;
import net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandler;
import net.firedevops.firemud.gamesession.command.text.ItemCommandHandler;
import net.firedevops.firemud.gamesession.command.text.MoveCommandHandler;
import net.firedevops.firemud.gamesession.command.text.PreparedMoveCommandResult;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService;
import net.firedevops.firemud.gamesession.service.DurableGameplayReplayService;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService.MoveEffectApplyResult;
import net.firedevops.firemud.gamesession.service.PlayerOutputDeliveryService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected service collaborators are framework-managed and retained internally")
public final class DefaultDurableGameplayCommandExecutionService
    implements DurableGameplayCommandExecutionService {
  private final MeterRegistry meterRegistry;
  private final TextCommandParser textCommandParser;
  private final SessionContextService sessionContextService;
  private final MoveCommandHandler moveCommandHandler;
  private final ItemCommandHandler itemCommandHandler;
  private final CommunicationCommandHandler communicationCommandHandler;
  private final AfkCommandHandler afkCommandHandler;
  private final ActionStateCommandHandler actionStateCommandHandler;
  private final DurableGameplayReplayService durableGameplayReplayService;
  private final MovementEffectIdempotencyService movementEffectIdempotencyService;
  private final PlayerOutputDeliveryService playerOutputDeliveryService;
  private final ScriptEventPublisher scriptEventPublisher;

  public DefaultDurableGameplayCommandExecutionService(
      MeterRegistry meterRegistry,
      TextCommandParser textCommandParser,
      SessionContextService sessionContextService,
      MoveCommandHandler moveCommandHandler,
      ItemCommandHandler itemCommandHandler,
      CommunicationCommandHandler communicationCommandHandler,
      AfkCommandHandler afkCommandHandler,
      ActionStateCommandHandler actionStateCommandHandler,
      DurableGameplayReplayService durableGameplayReplayService,
      MovementEffectIdempotencyService movementEffectIdempotencyService,
      PlayerOutputDeliveryService playerOutputDeliveryService,
      ScriptEventPublisher scriptEventPublisher) {
    this.meterRegistry = meterRegistry;
    this.textCommandParser = textCommandParser;
    this.sessionContextService = sessionContextService;
    this.moveCommandHandler = moveCommandHandler;
    this.itemCommandHandler = itemCommandHandler;
    this.communicationCommandHandler = communicationCommandHandler;
    this.afkCommandHandler = afkCommandHandler;
    this.actionStateCommandHandler = actionStateCommandHandler;
    this.durableGameplayReplayService = durableGameplayReplayService;
    this.movementEffectIdempotencyService = movementEffectIdempotencyService;
    this.playerOutputDeliveryService = playerOutputDeliveryService;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Override
  public Optional<DurableGameplayCommandExecutionResult> execute(
      TickEffect effect, GameplayCommand command) {
    TextCommand parsed = textCommandParser.parse(command.getCommandText());
    if (!isDurableCommand(parsed.type())) {
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
    if (isDurableItemMutation(parsed.type())) {
      return Optional.of(executeItemMutation(context, parsed, command, effect.getEffectId()));
    }
    if (isDurableCommunication(parsed.type())) {
      return Optional.of(
          executeCommunicationMutation(context, parsed, command, effect.getEffectId()));
    }
    if (parsed.type() == TextCommandType.AFK) {
      return Optional.of(executeAfkMutation(context, parsed, command, effect.getEffectId()));
    }
    if (parsed.type() == TextCommandType.BLOCK) {
      return Optional.of(
          executeActionStateMutation(context, parsed, command, effect.getEffectId()));
    }
    PreparedMoveCommandResult prepared = moveCommandHandler.prepare(context, parsed);
    if (!prepared.commandResult().accepted()) {
      if (prepared.responseOutput() != null) {
        playerOutputDeliveryService.deliver(context, List.of(prepared.responseOutput()), true);
      }
      return Optional.of(
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
    return Optional.of(
        recordResult(
            command, resultForApply(applyResult, prepared, context, effect.getEffectId())));
  }

  private Optional<SessionContext> resolveExecutionContext(GameplayCommand command) {
    if (command.getSessionId() != null && command.getSessionId() > 0) {
      Optional<SessionContext> bySession =
          sessionContextService.findBySessionId(command.getSessionId());
      if (bySession.isPresent()) {
        return bySession;
      }
    }
    Long characterId = gameplayCharacterId(command);
    if (characterId == null
        || characterId <= 0
        || command.getTenantId() == null
        || command.getTenantId() <= 0
        || command.getGameInstanceId() == null
        || command.getGameInstanceId() <= 0) {
      return Optional.empty();
    }
    return sessionContextService.findByGameplayIdentity(
        command.getTenantId(), command.getGameInstanceId(), characterId);
  }

  private static Long gameplayCharacterId(GameplayCommand command) {
    if (command.getCharacterId() != null && command.getCharacterId() > 0) {
      return command.getCharacterId();
    }
    if (command.getTargetEntityId() == null || command.getTargetEntityId().isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(command.getTargetEntityId());
    } catch (NumberFormatException ex) {
      return null;
    }
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

  private DurableGameplayCommandExecutionResult executeCommunicationMutation(
      SessionContext context, TextCommand parsed, GameplayCommand command, String effectId) {
    Optional<DurableGameplayReplayService.ReplayRecord> replay =
        durableGameplayReplayService.find(context.tenantId(), context.sessionId(), effectId);
    if (replay.isPresent()) {
      DurableGameplayReplayService.ReplayRecord record = replay.orElseThrow();
      if (!record.actorOutputs().isEmpty()) {
        playerOutputDeliveryService.deliver(context, record.actorOutputs(), true);
      }
      return recordResult(command, replayResult(record));
    }
    var result = communicationCommandHandler.handle(context, parsed, effectId);
    durableGameplayReplayService.save(
        context.tenantId(),
        context.sessionId(),
        effectId,
        result.commandResult().accepted(),
        result.commandResult().errorCode(),
        result.commandResult().errorMessage(),
        result.outputs());
    if (!result.outputs().isEmpty()) {
      playerOutputDeliveryService.deliver(context, result.outputs(), true);
    }
    return recordResult(command, resultForCommandResult(result.commandResult()));
  }

  private DurableGameplayCommandExecutionResult executeAfkMutation(
      SessionContext context, TextCommand parsed, GameplayCommand command, String effectId) {
    Optional<DurableGameplayReplayService.ReplayRecord> replay =
        durableGameplayReplayService.find(context.tenantId(), context.sessionId(), effectId);
    if (replay.isPresent()) {
      DurableGameplayReplayService.ReplayRecord record = replay.orElseThrow();
      if (!record.actorOutputs().isEmpty()) {
        playerOutputDeliveryService.deliver(context, record.actorOutputs(), true);
      }
      return recordResult(command, replayResult(record));
    }
    var result = afkCommandHandler.handle(context, parsed);
    durableGameplayReplayService.save(
        context.tenantId(),
        context.sessionId(),
        effectId,
        result.commandResult().accepted(),
        result.commandResult().errorCode(),
        result.commandResult().errorMessage(),
        result.outputs());
    if (!result.outputs().isEmpty()) {
      playerOutputDeliveryService.deliver(context, result.outputs(), true);
    }
    return recordResult(command, resultForCommandResult(result.commandResult()));
  }

  private DurableGameplayCommandExecutionResult executeActionStateMutation(
      SessionContext context, TextCommand parsed, GameplayCommand command, String effectId) {
    Optional<DurableGameplayReplayService.ReplayRecord> replay =
        durableGameplayReplayService.find(context.tenantId(), context.sessionId(), effectId);
    if (replay.isPresent()) {
      DurableGameplayReplayService.ReplayRecord record = replay.orElseThrow();
      if (!record.actorOutputs().isEmpty()) {
        playerOutputDeliveryService.deliver(context, record.actorOutputs(), true);
      }
      return recordResult(command, replayResult(record));
    }
    var result = actionStateCommandHandler.handle(context, parsed, effectId);
    durableGameplayReplayService.save(
        context.tenantId(),
        context.sessionId(),
        effectId,
        result.commandResult().accepted(),
        result.commandResult().errorCode(),
        result.commandResult().errorMessage(),
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

  private boolean isDurableItemMutation(TextCommandType type) {
    return switch (type) {
      case GET, DROP, PUT, TAKE, WEAR, REMOVE -> true;
      default -> false;
    };
  }

  private boolean isDurableCommunication(TextCommandType type) {
    return switch (type) {
      case SAY, WHISPER, TELL -> true;
      default -> false;
    };
  }

  private boolean isDurableCommand(TextCommandType type) {
    return type == TextCommandType.MOVE
        || type == TextCommandType.AFK
        || type == TextCommandType.BLOCK
        || isDurableItemMutation(type)
        || isDurableCommunication(type);
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
}
