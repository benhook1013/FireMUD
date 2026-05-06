package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.gamesession.command.text.ActionStateCommandHandler;
import net.firedevops.firemud.gamesession.command.text.ActionStateCommandHandlingResult;
import net.firedevops.firemud.gamesession.command.text.AfkCommandHandler;
import net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandler;
import net.firedevops.firemud.gamesession.command.text.ItemCommandHandler;
import net.firedevops.firemud.gamesession.command.text.MoveCommandHandler;
import net.firedevops.firemud.gamesession.command.text.PreparedMoveCommandResult;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService.DurableGameplayCommandExecutionResult;
import net.firedevops.firemud.gamesession.service.DurableGameplayReplayService;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService.MoveEffectApplyResult;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService.MoveEffectApplyStatus;
import net.firedevops.firemud.gamesession.service.PlayerOutputDeliveryService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultDurableGameplayCommandExecutionServiceTest {
  private final TextCommandParser parser = Mockito.mock(TextCommandParser.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final MoveCommandHandler moveCommandHandler = Mockito.mock(MoveCommandHandler.class);
  private final ItemCommandHandler itemCommandHandler = Mockito.mock(ItemCommandHandler.class);
  private final CommunicationCommandHandler communicationCommandHandler =
      Mockito.mock(CommunicationCommandHandler.class);
  private final AfkCommandHandler afkCommandHandler = Mockito.mock(AfkCommandHandler.class);
  private final ActionStateCommandHandler actionStateCommandHandler =
      Mockito.mock(ActionStateCommandHandler.class);
  private final DurableGameplayReplayService durableGameplayReplayService =
      Mockito.mock(DurableGameplayReplayService.class);
  private final MovementEffectIdempotencyService movementEffectIdempotencyService =
      Mockito.mock(MovementEffectIdempotencyService.class);
  private final PlayerOutputDeliveryService playerOutputDeliveryService =
      Mockito.mock(PlayerOutputDeliveryService.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private DefaultDurableGameplayCommandExecutionService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultDurableGameplayCommandExecutionService(
            meterRegistry,
            parser,
            sessionContextService,
            moveCommandHandler,
            itemCommandHandler,
            communicationCommandHandler,
            afkCommandHandler,
            actionStateCommandHandler,
            durableGameplayReplayService,
            movementEffectIdempotencyService,
            playerOutputDeliveryService,
            scriptEventPublisher);
  }

  @Test
  void executeIgnoresNonDurableCommands() {
    GameplayCommand command = gameplayCommand("LOOK", "LOOK");
    when(parser.parse("LOOK"))
        .thenReturn(new TextCommand(TextCommandType.LOOK, java.util.List.of(), "LOOK"));

    Optional<DurableGameplayCommandExecutionResult> result =
        service.execute(tickEffect("tfx-1", "cmd-1"), command);

    assertThat(result).isEmpty();
    verify(moveCommandHandler, never()).prepare(Mockito.any(), Mockito.any());
  }

  @Test
  void executeReturnsReplayNoOpForIdempotentMoveReplay() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    SessionContext moved =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-2", "jwt-token");
    GameplayCommand command = gameplayCommand("MOVE", "north");
    TickEffect effect = tickEffect("tfx-1", "cmd-1");
    PlayerOutput output = PlayerOutput.message("You move north.");
    when(parser.parse("north"))
        .thenReturn(new TextCommand(TextCommandType.MOVE, java.util.List.of("north"), "north"));
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(moveCommandHandler.prepare(Mockito.eq(context), Mockito.any()))
        .thenReturn(new PreparedMoveCommandResult(CommandEnqueueResult.success(), output, moved));
    when(movementEffectIdempotencyService.apply("tfx-1", context, "R-2"))
        .thenReturn(new MoveEffectApplyResult(MoveEffectApplyStatus.REPLAYED, moved));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("REPLAY_NOOP");
    assertThat(result.commandExecutionOutcome()).isEqualTo("APPLIED");
    assertThat(result.gameplayResult()).isEqualTo("REPLAY_NOOP");
    assertThat(
            meterRegistry
                .get("gamesession.durable.effect.execution")
                .tag("command", "MOVE")
                .tag("effect_status", "REPLAY_NOOP")
                .tag("gameplay_result", "REPLAY_NOOP")
                .counter()
                .count())
        .isEqualTo(1.0);
    verify(playerOutputDeliveryService).deliver(moved, java.util.List.of(output), true);
    verify(scriptEventPublisher).publishRegionTransitionEvents(context, moved, "tfx-1");
  }

  @Test
  void executePublishesMovementLifecycleEventsAfterFirstApply() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    SessionContext moved =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-2", "jwt-token");
    GameplayCommand command = gameplayCommand("MOVE", "north");
    TickEffect effect = tickEffect("tfx-9", "cmd-9");
    when(parser.parse("north"))
        .thenReturn(new TextCommand(TextCommandType.MOVE, java.util.List.of("north"), "north"));
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(moveCommandHandler.prepare(Mockito.eq(context), Mockito.any()))
        .thenReturn(
            new PreparedMoveCommandResult(
                CommandEnqueueResult.success(), PlayerOutput.message("moved"), moved));
    when(movementEffectIdempotencyService.apply("tfx-9", context, "R-2"))
        .thenReturn(new MoveEffectApplyResult(MoveEffectApplyStatus.APPLIED, moved));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("APPLIED");
    verify(scriptEventPublisher).publishRegionTransitionEvents(context, moved, "tfx-9");
  }

  @Test
  void executeAppliesDurableItemMutationAndDeliversOutput() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("GET", "GET torch");
    TickEffect effect = tickEffect("tfx-2", "cmd-2");
    TextCommand parsed =
        new TextCommand(TextCommandType.GET, java.util.List.of("torch"), "GET torch");
    PlayerOutput output = PlayerOutput.message("You pick up Torch.");
    when(parser.parse("GET torch")).thenReturn(parsed);
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(itemCommandHandler.handle(context, parsed, "tfx-2"))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), java.util.List.of(output)));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("APPLIED");
    assertThat(result.commandExecutionOutcome()).isEqualTo("APPLIED");
    assertThat(result.gameplayResult()).isEqualTo("APPLIED");
    assertThat(
            meterRegistry
                .get("gamesession.durable.effect.execution")
                .tag("command", "GET")
                .tag("effect_status", "APPLIED")
                .tag("gameplay_result", "APPLIED")
                .counter()
                .count())
        .isEqualTo(1.0);
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
  }

  @Test
  void executeRejectsDurableItemMutationAndDeliversOutput() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("PUT", "PUT ration INTO torch");
    TickEffect effect = tickEffect("tfx-3", "cmd-3");
    TextCommand parsed =
        new TextCommand(
            TextCommandType.PUT,
            java.util.List.of("ration", "INTO", "torch"),
            "PUT ration INTO torch");
    PlayerOutput output =
        PlayerOutput.error(
            "INVALID_ARGUMENT",
            "No carried item matches \"ration\"",
            "error.container.invalid-argument",
            java.util.Map.of());
    when(parser.parse("PUT ration INTO torch")).thenReturn(parsed);
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(itemCommandHandler.handle(context, parsed, "tfx-3"))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.failure(
                    "INVALID_ARGUMENT", "No carried item matches \"ration\""),
                java.util.List.of(output)));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("REJECTED");
    assertThat(result.commandExecutionOutcome()).isEqualTo("COMPLETED");
    assertThat(result.gameplayResult()).isEqualTo("NOT_APPLIED");
    assertThat(result.failureCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.failureMessage()).isEqualTo("No carried item matches \"ration\"");
    assertThat(
            meterRegistry
                .get("gamesession.durable.effect.execution")
                .tag("command", "PUT")
                .tag("effect_status", "REJECTED")
                .tag("gameplay_result", "NOT_APPLIED")
                .counter()
                .count())
        .isEqualTo(1.0);
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
  }

  @Test
  void executeAppliesDurableCommunicationAndDeliversOutput() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("SAY", "SAY Hello there");
    TickEffect effect = tickEffect("tfx-4", "cmd-4");
    TextCommand parsed =
        new TextCommand(TextCommandType.SAY, java.util.List.of("Hello there"), "SAY Hello there");
    PlayerOutput output = PlayerOutput.message("You say, \"Hello there.\"");
    when(parser.parse("SAY Hello there")).thenReturn(parsed);
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(durableGameplayReplayService.find(22L, 42L, "tfx-4")).thenReturn(Optional.empty());
    when(communicationCommandHandler.handle(context, parsed, "tfx-4"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandlingResult(
                CommandEnqueueResult.success(), java.util.List.of(output)));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("APPLIED");
    assertThat(result.commandExecutionOutcome()).isEqualTo("APPLIED");
    assertThat(result.gameplayResult()).isEqualTo("APPLIED");
    verify(durableGameplayReplayService)
        .save(22L, 42L, "tfx-4", true, null, null, java.util.List.of(output));
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
    verify(scriptEventPublisher, never()).publishCommandEvent(context, command);
  }

  @Test
  void executeResolvesAutomationCommandByGameplayIdentityWhenSessionIdIsUnset() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("SAY", "SAY Hello there");
    command.setSessionId(0L);
    command.setTenantId(22L);
    command.setGameInstanceId(7L);
    command.setCharacterId(91L);
    command.setTargetEntityId("91");
    TickEffect effect = tickEffect("tfx-4b", "cmd-4b");
    TextCommand parsed =
        new TextCommand(TextCommandType.SAY, java.util.List.of("Hello there"), "SAY Hello there");
    PlayerOutput output = PlayerOutput.message("You say, \"Hello there.\"");
    when(parser.parse("SAY Hello there")).thenReturn(parsed);
    when(sessionContextService.findByGameplayIdentity(22L, 7L, 91L))
        .thenReturn(Optional.of(context));
    when(durableGameplayReplayService.find(22L, 42L, "tfx-4b")).thenReturn(Optional.empty());
    when(communicationCommandHandler.handle(context, parsed, "tfx-4b"))
        .thenReturn(
            new net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandlingResult(
                CommandEnqueueResult.success(), java.util.List.of(output)));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("APPLIED");
    verify(sessionContextService, never()).findBySessionId(0L);
    verify(sessionContextService).findByGameplayIdentity(22L, 7L, 91L);
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
    verify(scriptEventPublisher).publishCommandEvent(context, command);
  }

  @Test
  void executeAppliesDurableAfkAndDeliversOutput() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("AFK", "AFK");
    TickEffect effect = tickEffect("tfx-5", "cmd-5");
    TextCommand parsed =
        new TextCommand(
            TextCommandType.AFK,
            java.util.List.of(),
            "AFK",
            "AFK",
            new net.firedevops.firemud.gamesession.command.text.TextCommandPayload.AfkRequest(
                true));
    PlayerOutput output = PlayerOutput.notice("AFK enabled.");
    when(parser.parse("AFK")).thenReturn(parsed);
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(durableGameplayReplayService.find(22L, 42L, "tfx-5")).thenReturn(Optional.empty());
    when(afkCommandHandler.handle(context, parsed))
        .thenReturn(
            new net.firedevops.firemud.gamesession.command.text.AfkCommandHandlingResult(
                CommandEnqueueResult.success(), java.util.List.of(output)));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("APPLIED");
    assertThat(result.commandExecutionOutcome()).isEqualTo("APPLIED");
    assertThat(result.gameplayResult()).isEqualTo("APPLIED");
    verify(durableGameplayReplayService)
        .save(22L, 42L, "tfx-5", true, null, null, java.util.List.of(output));
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
  }

  @Test
  void executeAppliesDurableBlockAndStoresReplay() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("BLOCK", "BLOCK");
    TickEffect effect = tickEffect("tfx-6", "cmd-6");
    TextCommand parsed = new TextCommand(TextCommandType.BLOCK, java.util.List.of(), "BLOCK");
    PlayerOutput output = PlayerOutput.notice("You brace for the next blow.");
    when(parser.parse("BLOCK")).thenReturn(parsed);
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(durableGameplayReplayService.find(22L, 42L, "tfx-6")).thenReturn(Optional.empty());
    when(actionStateCommandHandler.handle(context, parsed, "tfx-6"))
        .thenReturn(
            new ActionStateCommandHandlingResult(
                CommandEnqueueResult.success(), java.util.List.of(output)));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("APPLIED");
    assertThat(result.commandExecutionOutcome()).isEqualTo("APPLIED");
    assertThat(result.gameplayResult()).isEqualTo("APPLIED");
    verify(durableGameplayReplayService)
        .save(22L, 42L, "tfx-6", true, null, null, java.util.List.of(output));
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
  }

  @Test
  void executeReplaysStoredCommunicationWithoutInvokingHandler() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("SAY", "SAY Hello there");
    TickEffect effect = tickEffect("tfx-6", "cmd-6");
    TextCommand parsed =
        new TextCommand(TextCommandType.SAY, java.util.List.of("Hello there"), "SAY Hello there");
    PlayerOutput output = PlayerOutput.message("You say, \"Hello there.\"");
    when(parser.parse("SAY Hello there")).thenReturn(parsed);
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(durableGameplayReplayService.find(22L, 42L, "tfx-6"))
        .thenReturn(
            Optional.of(
                new DurableGameplayReplayService.ReplayRecord(
                    true, null, null, java.util.List.of(output))));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("REPLAY_NOOP");
    assertThat(result.commandExecutionOutcome()).isEqualTo("APPLIED");
    assertThat(result.gameplayResult()).isEqualTo("REPLAY_NOOP");
    verify(communicationCommandHandler, never()).handle(Mockito.any(), Mockito.any());
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
  }

  @Test
  void executeReplaysStoredRejectedAfkWithoutInvokingHandler() {
    SessionContext context =
        new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");
    GameplayCommand command = gameplayCommand("AFK", "AFK");
    TickEffect effect = tickEffect("tfx-7", "cmd-7");
    TextCommand parsed =
        new TextCommand(
            TextCommandType.AFK,
            java.util.List.of(),
            "AFK",
            "AFK",
            new net.firedevops.firemud.gamesession.command.text.TextCommandPayload.AfkRequest(
                true));
    PlayerOutput output = PlayerOutput.error("INVALID_ARGUMENT", "AFK command rejected");
    when(parser.parse("AFK")).thenReturn(parsed);
    when(sessionContextService.findBySessionId(42L)).thenReturn(Optional.of(context));
    when(durableGameplayReplayService.find(22L, 42L, "tfx-7"))
        .thenReturn(
            Optional.of(
                new DurableGameplayReplayService.ReplayRecord(
                    false, "INVALID_ARGUMENT", "AFK command rejected", java.util.List.of(output))));

    DurableGameplayCommandExecutionResult result = service.execute(effect, command).orElseThrow();

    assertThat(result.effectStatus()).isEqualTo("REPLAY_NOOP");
    assertThat(result.commandExecutionOutcome()).isEqualTo("COMPLETED");
    assertThat(result.gameplayResult()).isEqualTo("NOT_APPLIED");
    assertThat(result.failureCode()).isEqualTo("INVALID_ARGUMENT");
    verify(afkCommandHandler, never())
        .handle(Mockito.any(SessionContext.class), Mockito.any(TextCommand.class));
    verify(playerOutputDeliveryService).deliver(context, java.util.List.of(output), true);
  }

  private GameplayCommand gameplayCommand(String commandName, String commandText) {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-1");
    command.setSessionId(42L);
    command.setCommandName(commandName);
    command.setCommandText(commandText);
    command.setSanitizedCommandText(commandText);
    return command;
  }

  private TickEffect tickEffect(String effectId, String commandId) {
    TickEffect effect = new TickEffect();
    effect.setEffectId(effectId);
    effect.setCommandId(commandId);
    return effect;
  }
}
