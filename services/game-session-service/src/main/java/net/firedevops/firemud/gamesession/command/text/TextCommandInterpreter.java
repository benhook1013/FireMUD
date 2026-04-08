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
  private final CommandService commandService;
  private final LookCommandHandler lookHandler;
  private final LoginCommandHandler loginHandler;
  private final PlayCommandHandler playHandler;
  private final MoveCommandHandler moveHandler;
  private final HelpCommandHandler helpHandler;
  private final WhoCommandHandler whoHandler;
  private final ItemCommandHandler itemHandler;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final CommunicationCommandHandler communicationHandler;
  private final WorldsCommandHandler worldsHandler;
  private final PromptComposer promptComposer;
  private final TextCommandParser parser;
  private final BuiltInTextCommandRegistry registry;

  @Autowired
  public TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler,
      SessionAuthenticationService sessionAuthenticationService,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler,
      PromptComposer promptComposer) {
    this(
        commandService,
        lookHandler,
        loginHandler,
        playHandler,
        moveHandler,
        helpHandler,
        whoHandler,
        new ItemCommandHandler(inventoryHandler, equipmentHandler, containerHandler),
        sessionAuthenticationService,
        communicationHandler,
        worldsHandler,
        promptComposer,
        new TextCommandParser(),
        new BuiltInTextCommandRegistry());
  }

  TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler,
      SessionAuthenticationService sessionAuthenticationService,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler,
      PromptComposer promptComposer,
      TextCommandParser parser) {
    this(
        commandService,
        lookHandler,
        loginHandler,
        playHandler,
        moveHandler,
        helpHandler,
        whoHandler,
        new ItemCommandHandler(inventoryHandler, equipmentHandler, containerHandler),
        sessionAuthenticationService,
        communicationHandler,
        worldsHandler,
        promptComposer,
        parser,
        new BuiltInTextCommandRegistry());
  }

  TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      ItemCommandHandler itemHandler,
      SessionAuthenticationService sessionAuthenticationService,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler,
      PromptComposer promptComposer,
      TextCommandParser parser,
      BuiltInTextCommandRegistry registry) {
    this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    this.lookHandler = Objects.requireNonNull(lookHandler, "lookHandler must not be null");
    this.loginHandler = Objects.requireNonNull(loginHandler, "loginHandler must not be null");
    this.playHandler = Objects.requireNonNull(playHandler, "playHandler must not be null");
    this.moveHandler = Objects.requireNonNull(moveHandler, "moveHandler must not be null");
    this.helpHandler = Objects.requireNonNull(helpHandler, "helpHandler must not be null");
    this.whoHandler = Objects.requireNonNull(whoHandler, "whoHandler must not be null");
    this.itemHandler = Objects.requireNonNull(itemHandler, "itemHandler must not be null");
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.communicationHandler =
        Objects.requireNonNull(communicationHandler, "communicationHandler must not be null");
    this.worldsHandler = Objects.requireNonNull(worldsHandler, "worldsHandler must not be null");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, String rawLine, boolean requiresSoloTick) {
    return interpret(sessionId, parser.parse(rawLine), requiresSoloTick);
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    TextCommandDefinition definition = registry.definitionFor(command.type());
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

    return switch (definition.dispatchGroup()) {
      case WORLDS ->
          new TextCommandInterpretationResult(
              CommandEnqueueResult.success(),
              List.of(PlayerOutput.view(worldsHandler.browseView())));
      case LOGIN -> {
        LoginCommandHandlingResult loginResult =
            loginHandler.handle(sessionId, command, requiresSoloTick);
        yield new TextCommandInterpretationResult(
            loginResult.commandResult(), loginResult.outputs());
      }
      case HELP ->
          applyPromptPolicy(helpHandler.handle(command), definition.promptPolicy(), maybeContext);
      case PLAY -> handlePlay(sessionId, command);
      case WHO ->
          applyPromptPolicy(
              whoHandler.handle(maybeContext.orElseThrow()),
              definition.promptPolicy(),
              maybeContext);
      case ITEM ->
          applyPromptPolicy(
              itemHandler.handle(maybeContext.orElseThrow(), command),
              definition.promptPolicy(),
              maybeContext);
      case COMMUNICATION ->
          applyPromptPolicy(
              toInterpretationResult(
                  communicationHandler.handle(maybeContext.orElseThrow(), command)),
              definition.promptPolicy(),
              maybeContext);
      case MOVE -> handleMove(maybeContext.orElseThrow(), command, definition.promptPolicy());
      case LOOK ->
          handleLook(sessionId, command, requiresSoloTick, maybeContext, definition.promptPolicy());
      case ENQUEUE_ONLY ->
          new TextCommandInterpretationResult(
              commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick));
    };
  }

  private TextCommandInterpretationResult handlePlay(String sessionId, TextCommand command) {
    PlayCommandHandlingResult playResult = playHandler.handle(sessionId, command);
    List<PlayerOutput> outputs = playResult.outputs();
    if (playResult.commandResult().accepted() && !playResult.reconnectRedrawRecommended()) {
      outputs = appendPrompt(sessionId, outputs);
    }
    return new TextCommandInterpretationResult(
        playResult.commandResult(), outputs, playResult.reconnectRedrawRecommended());
  }

  private TextCommandInterpretationResult handleMove(
      net.firedevops.firemud.gamesession.service.SessionContext context,
      TextCommand command,
      TextCommandPromptPolicy promptPolicy) {
    MoveCommandHandlingResult moveResult = moveHandler.handle(context, command);
    List<PlayerOutput> outputs =
        moveResult.responseOutput() == null ? List.of() : List.of(moveResult.responseOutput());
    return applyPromptPolicy(
        new TextCommandInterpretationResult(moveResult.commandResult(), outputs),
        promptPolicy,
        Optional.of(context));
  }

  private TextCommandInterpretationResult handleLook(
      String sessionId,
      TextCommand command,
      boolean requiresSoloTick,
      Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext,
      TextCommandPromptPolicy promptPolicy) {
    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    if (!enqueueResult.accepted() || command.viewRequestPayload().isEmpty()) {
      return new TextCommandInterpretationResult(enqueueResult);
    }
    PlayerOutput lookOutput =
        lookHandler.describePlayerOutput(sessionId, command.type() != TextCommandType.QUICKLOOK);
    if (lookOutput == null) {
      return stageFailure(
          GameplayStageCommandConstants.PLAY_REQUIRED_CODE,
          GameplayStageCommandConstants.PLAY_REQUIRED_MESSAGE);
    }
    if (lookOutput.kind() == net.firedevops.firemud.gamesession.presentation.PlayerOutputKind.ERROR
        && lookOutput.payload()
            instanceof net.firedevops.firemud.gamesession.presentation.ErrorOutput errorOutput) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure(errorOutput.code(), errorOutput.message()),
          List.of(lookOutput));
    }
    return applyPromptPolicy(
        new TextCommandInterpretationResult(enqueueResult, List.of(lookOutput)),
        promptPolicy,
        maybeContext);
  }

  private TextCommandInterpretationResult applyPromptPolicy(
      TextCommandInterpretationResult result,
      TextCommandPromptPolicy promptPolicy,
      Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext) {
    if (!result.commandResult().accepted()) {
      return result;
    }
    return switch (promptPolicy) {
      case NEVER -> result;
      case WHEN_LOGGED_IN, WHEN_GAMEPLAY ->
          maybeContext
              .map(
                  context ->
                      new TextCommandInterpretationResult(
                          result.commandResult(),
                          appendPrompt(context, result.outputs()),
                          result.reconnectRedrawRecommended()))
              .orElse(result);
    };
  }

  private static TextCommandInterpretationResult toInterpretationResult(
      CommunicationCommandHandlingResult result) {
    return new TextCommandInterpretationResult(result.commandResult(), result.outputs());
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

  private List<PlayerOutput> appendPrompt(String sessionId, List<PlayerOutput> outputs) {
    return sessionAuthenticationService
        .resolveSessionContext(sessionId)
        .map(context -> appendPrompt(context, outputs))
        .orElse(outputs);
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
}
