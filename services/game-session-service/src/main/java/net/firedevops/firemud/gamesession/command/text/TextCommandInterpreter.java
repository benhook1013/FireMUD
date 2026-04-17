package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TextCommandInterpreter {
  private final SessionAuthenticationService sessionAuthenticationService;
  private final PromptComposer promptComposer;
  private final TextCommandParser parser;
  private final TextCommandRegistry registry;
  private final TextCommandDispatcher dispatcher;

  @Autowired
  public TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      LogoutCommandHandler logoutHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      AfkCommandHandler afkHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      FriendsCommandHandler friendsHandler,
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler,
      SessionAuthenticationService sessionAuthenticationService,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler,
      PromptComposer promptComposer,
      TextCommandRegistry registry,
      TextCommandParser parser) {
    this(
        sessionAuthenticationService,
        promptComposer,
        parser,
        registry,
        buildDispatcher(
            commandService,
            lookHandler,
            loginHandler,
            logoutHandler,
            playHandler,
            moveHandler,
            afkHandler,
            helpHandler,
            whoHandler,
            friendsHandler,
            new ItemCommandHandler(inventoryHandler, equipmentHandler, containerHandler),
            communicationHandler,
            worldsHandler));
  }

  TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      LogoutCommandHandler logoutHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      AfkCommandHandler afkHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      FriendsCommandHandler friendsHandler,
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler,
      SessionAuthenticationService sessionAuthenticationService,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler,
      PromptComposer promptComposer,
      TextCommandParser parser) {
    this(
        sessionAuthenticationService,
        promptComposer,
        parser,
        new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider())),
        buildDispatcher(
            commandService,
            lookHandler,
            loginHandler,
            logoutHandler,
            playHandler,
            moveHandler,
            afkHandler,
            helpHandler,
            whoHandler,
            friendsHandler,
            new ItemCommandHandler(inventoryHandler, equipmentHandler, containerHandler),
            communicationHandler,
            worldsHandler));
  }

  TextCommandInterpreter(
      SessionAuthenticationService sessionAuthenticationService,
      PromptComposer promptComposer,
      TextCommandParser parser,
      TextCommandRegistry registry,
      TextCommandDispatcher dispatcher) {
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, String rawLine, boolean requiresSoloTick) {
    return interpret(sessionId, parser.parse(rawLine), requiresSoloTick);
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    TextCommandDefinition definition =
        registry
            .findDefinition(command.type())
            .orElseGet(() -> TextCommandDefinition.extensionDefinition(command.type()));
    if (command.type() == TextCommandType.NOOP) {
      return new TextCommandInterpretationResult(CommandEnqueueResult.success());
    }
    if (command.type() == TextCommandType.UNKNOWN) {
      String message = "Unknown command";
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("UNKNOWN_COMMAND", message),
          List.of(
              PlayerOutput.error("UNKNOWN_COMMAND", message, "error.unknown-command", Map.of())));
    }
    Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    boolean hasLogin = maybeContext.isPresent();
    boolean hasPlay =
        maybeContext.isPresent() && StringUtils.hasText(maybeContext.get().roomInstanceId());

    if (definition.stageRequirement() != TextCommandStageRequirement.NONE && !hasLogin) {
      return stageFailure(
          GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
          GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE);
    }
    if (definition.stageRequirement() == TextCommandStageRequirement.GAMEPLAY && !hasPlay) {
      return stageFailure(
          GameplayStageCommandConstants.PLAY_REQUIRED_CODE,
          GameplayStageCommandConstants.PLAY_REQUIRED_MESSAGE);
    }

    TextCommandInterpretationResult dispatchResult =
        dispatcher.dispatch(
            definition.dispatchGroup(),
            new TextCommandDispatchRequest(sessionId, command, requiresSoloTick, maybeContext));
    Optional<net.firedevops.firemud.gamesession.service.SessionContext> promptContext =
        promptContextAfterDispatch(sessionId, definition, dispatchResult, maybeContext);
    TextCommandInterpretationResult promptApplied =
        applyPromptPolicy(dispatchResult, definition.promptPolicy(), promptContext);
    return withMeaningfulGameplayActivity(
        promptApplied,
        dispatchResult.commandResult().accepted() && isMeaningfulGameplay(definition));
  }

  private TextCommandInterpretationResult applyPromptPolicy(
      TextCommandInterpretationResult result,
      TextCommandPromptPolicy promptPolicy,
      Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext) {
    if (!result.commandResult().accepted() || result.reconnectRedrawRecommended()) {
      return result;
    }
    return switch (promptPolicy) {
      case NEVER -> result;
      case WHEN_LOGGED_IN ->
          maybeContext
              .map(
                  context ->
                      new TextCommandInterpretationResult(
                          result.commandResult(),
                          appendPrompt(context, result.outputs()),
                          result.reconnectRedrawRecommended(),
                          result.meaningfulGameplayActivity()))
              .orElse(result);
      case WHEN_GAMEPLAY ->
          maybeContext
              .filter(context -> StringUtils.hasText(context.roomInstanceId()))
              .map(
                  context ->
                      new TextCommandInterpretationResult(
                          result.commandResult(),
                          appendPrompt(context, result.outputs()),
                          result.reconnectRedrawRecommended(),
                          result.meaningfulGameplayActivity()))
              .orElse(result);
    };
  }

  private TextCommandInterpretationResult withMeaningfulGameplayActivity(
      TextCommandInterpretationResult result, boolean meaningfulGameplayActivity) {
    return new TextCommandInterpretationResult(
        result.commandResult(),
        result.outputs(),
        result.reconnectRedrawRecommended(),
        meaningfulGameplayActivity);
  }

  private boolean isMeaningfulGameplay(TextCommandDefinition definition) {
    return definition.actionCategory() == TextCommandActionCategory.GAMEPLAY;
  }

  private Optional<net.firedevops.firemud.gamesession.service.SessionContext>
      promptContextAfterDispatch(
          String sessionId,
          TextCommandDefinition definition,
          TextCommandInterpretationResult result,
          Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext) {
    if (!result.commandResult().accepted()) {
      return maybeContext;
    }
    if (definition.dispatchGroup() == TextCommandDispatchGroup.SESSION) {
      return sessionAuthenticationService.resolveSessionContext(sessionId);
    }
    return maybeContext;
  }

  private TextCommandInterpretationResult stageFailure(String code, String message) {
    String messageKey =
        switch (code) {
          case GameplayStageCommandConstants.LOGIN_REQUIRED_CODE -> "error.login-required";
          case GameplayStageCommandConstants.PLAY_REQUIRED_CODE -> "error.play-required";
          default -> null;
        };
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, messageKey, Map.of())));
  }

  private List<PlayerOutput> appendPrompt(
      net.firedevops.firemud.gamesession.service.SessionContext context,
      List<PlayerOutput> outputs) {
    return promptComposer
        .compose(context)
        .map(
            prompt -> {
              java.util.ArrayList<PlayerOutput> combined = new java.util.ArrayList<>(outputs);
              combined.add(prompt);
              return List.copyOf(combined);
            })
        .orElse(outputs);
  }

  private static TextCommandDispatcher buildDispatcher(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      LogoutCommandHandler logoutHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      AfkCommandHandler afkHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      FriendsCommandHandler friendsHandler,
      ItemCommandHandler itemHandler,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler) {
    return new TextCommandDispatcher(
        List.of(
            new EnqueueOnlyTextCommandDispatchHandler(commandService),
            new WorldsTextCommandDispatchHandler(worldsHandler),
            new SessionTextCommandDispatchHandler(loginHandler, logoutHandler, playHandler),
            new ActivityTextCommandDispatchHandler(afkHandler),
            new HelpTextCommandDispatchHandler(helpHandler),
            new WhoTextCommandDispatchHandler(whoHandler),
            new FriendsTextCommandDispatchHandler(friendsHandler),
            new ItemTextCommandDispatchHandler(itemHandler),
            new CommunicationTextCommandDispatchHandler(communicationHandler),
            new MoveTextCommandDispatchHandler(moveHandler),
            new LookTextCommandDispatchHandler(commandService, lookHandler)));
  }
}
