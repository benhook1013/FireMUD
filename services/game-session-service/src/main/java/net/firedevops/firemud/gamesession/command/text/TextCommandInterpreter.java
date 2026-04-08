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
  private final InventoryCommandHandler inventoryHandler;
  private final EquipmentCommandHandler equipmentHandler;
  private final ContainerCommandHandler containerHandler;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final CommunicationCommandHandler communicationHandler;
  private final WorldsCommandHandler worldsHandler;
  private final PromptComposer promptComposer;
  private final TextCommandParser parser;

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
        inventoryHandler,
        equipmentHandler,
        containerHandler,
        sessionAuthenticationService,
        communicationHandler,
        worldsHandler,
        promptComposer,
        new TextCommandParser());
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
    this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    this.lookHandler = Objects.requireNonNull(lookHandler, "lookHandler must not be null");
    this.loginHandler = Objects.requireNonNull(loginHandler, "loginHandler must not be null");
    this.playHandler = Objects.requireNonNull(playHandler, "playHandler must not be null");
    this.moveHandler = Objects.requireNonNull(moveHandler, "moveHandler must not be null");
    this.helpHandler = Objects.requireNonNull(helpHandler, "helpHandler must not be null");
    this.whoHandler = Objects.requireNonNull(whoHandler, "whoHandler must not be null");
    this.inventoryHandler =
        Objects.requireNonNull(inventoryHandler, "inventoryHandler must not be null");
    this.equipmentHandler =
        Objects.requireNonNull(equipmentHandler, "equipmentHandler must not be null");
    this.containerHandler =
        Objects.requireNonNull(containerHandler, "containerHandler must not be null");
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.communicationHandler =
        Objects.requireNonNull(communicationHandler, "communicationHandler must not be null");
    this.worldsHandler = Objects.requireNonNull(worldsHandler, "worldsHandler must not be null");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, String rawLine, boolean requiresSoloTick) {
    return interpret(sessionId, parser.parse(rawLine), requiresSoloTick);
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
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
    if (command.viewRequestPayload().isPresent() && command.type() == TextCommandType.WORLDS) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.success(), List.of(PlayerOutput.view(worldsHandler.browseView())));
    }
    if (command.type() == TextCommandType.LOGIN) {
      LoginCommandHandlingResult loginResult =
          loginHandler.handle(sessionId, command, requiresSoloTick);
      return new TextCommandInterpretationResult(
          loginResult.commandResult(), loginResult.outputs());
    }

    Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    boolean hasLogin = maybeContext.isPresent();
    boolean hasPlay =
        maybeContext.isPresent() && StringUtils.hasText(maybeContext.get().roomInstanceId());

    if (command.type() == TextCommandType.HELP) {
      TextCommandInterpretationResult helpResult = helpHandler.handle(command);
      List<PlayerOutput> outputs = helpResult.outputs();
      if (helpResult.commandResult().accepted() && hasLogin) {
        outputs = appendPrompt(maybeContext.get(), outputs);
      }
      return new TextCommandInterpretationResult(helpResult.commandResult(), outputs);
    }

    if (command.type() == TextCommandType.PLAY) {
      if (!hasLogin) {
        return stageFailure(
            GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
            GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE);
      }
      PlayCommandHandlingResult playResult = playHandler.handle(sessionId, command);
      List<PlayerOutput> outputs = playResult.outputs();
      if (playResult.commandResult().accepted() && !playResult.reconnectRedrawRecommended()) {
        outputs = appendPrompt(sessionId, outputs);
      }
      return new TextCommandInterpretationResult(
          playResult.commandResult(), outputs, playResult.reconnectRedrawRecommended());
    }

    if (command.type() == TextCommandType.WHO) {
      if (!hasLogin) {
        return stageFailure(
            GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
            GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE);
      }
      if (!hasPlay) {
        return stageFailure(
            GameplayStageCommandConstants.PLAY_REQUIRED_CODE,
            GameplayStageCommandConstants.PLAY_REQUIRED_MESSAGE);
      }
      TextCommandInterpretationResult whoResult = whoHandler.handle(maybeContext.orElseThrow());
      List<PlayerOutput> outputs = appendPrompt(maybeContext.orElseThrow(), whoResult.outputs());
      return new TextCommandInterpretationResult(whoResult.commandResult(), outputs);
    }

    if (requiresGameplayAuthentication(command.type()) && !hasLogin) {
      return stageFailure(
          GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
          GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE);
    }
    if (requiresGameplayAuthentication(command.type()) && !hasPlay) {
      return stageFailure(
          GameplayStageCommandConstants.PLAY_REQUIRED_CODE,
          GameplayStageCommandConstants.PLAY_REQUIRED_MESSAGE);
    }

    if (isInventoryCommand(command.type())) {
      InventoryCommandHandlingResult inventoryResult =
          inventoryHandler.handle(maybeContext.orElseThrow(), command);
      List<PlayerOutput> outputs = inventoryResult.outputs();
      if (inventoryResult.commandResult().accepted()) {
        outputs = appendPrompt(maybeContext.orElseThrow(), outputs);
      }
      return new TextCommandInterpretationResult(inventoryResult.commandResult(), outputs);
    }

    if (isEquipmentCommand(command.type())) {
      TextCommandInterpretationResult equipmentResult =
          equipmentHandler.handle(maybeContext.orElseThrow(), command);
      List<PlayerOutput> outputs = equipmentResult.outputs();
      if (equipmentResult.commandResult().accepted()) {
        outputs = appendPrompt(maybeContext.orElseThrow(), outputs);
      }
      return new TextCommandInterpretationResult(equipmentResult.commandResult(), outputs);
    }

    if (isContainerCommand(command.type())) {
      TextCommandInterpretationResult containerResult =
          containerHandler.handle(maybeContext.orElseThrow(), command);
      List<PlayerOutput> outputs = containerResult.outputs();
      if (containerResult.commandResult().accepted()) {
        outputs = appendPrompt(maybeContext.orElseThrow(), outputs);
      }
      return new TextCommandInterpretationResult(containerResult.commandResult(), outputs);
    }

    if (isCommunicationCommand(command.type())) {
      CommunicationCommandHandlingResult communicationResult =
          communicationHandler.handle(maybeContext.orElseThrow(), command);
      List<PlayerOutput> outputs = communicationResult.outputs();
      if (communicationResult.commandResult().accepted()) {
        outputs = appendPrompt(maybeContext.orElseThrow(), outputs);
      }
      return new TextCommandInterpretationResult(communicationResult.commandResult(), outputs);
    }

    if (command.type() == TextCommandType.MOVE) {
      MoveCommandHandlingResult moveResult =
          moveHandler.handle(maybeContext.orElseThrow(), command);
      List<PlayerOutput> outputs =
          moveResult.responseOutput() == null ? List.of() : List.of(moveResult.responseOutput());
      if (moveResult.commandResult().accepted()) {
        outputs = appendPrompt(maybeContext.orElseThrow(), outputs);
      }
      return new TextCommandInterpretationResult(moveResult.commandResult(), outputs);
    }

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    String response = null;
    if (enqueueResult.accepted()
        && command.viewRequestPayload().isPresent()
        && isLookCommand(command.type())) {
      PlayerOutput lookOutput =
          lookHandler.describePlayerOutput(sessionId, command.type() != TextCommandType.QUICKLOOK);
      if (lookOutput == null) {
        return stageFailure(
            GameplayStageCommandConstants.PLAY_REQUIRED_CODE,
            GameplayStageCommandConstants.PLAY_REQUIRED_MESSAGE);
      }
      if (lookOutput.kind()
              == net.firedevops.firemud.gamesession.presentation.PlayerOutputKind.ERROR
          && lookOutput.payload()
              instanceof net.firedevops.firemud.gamesession.presentation.ErrorOutput errorOutput) {
        return new TextCommandInterpretationResult(
            CommandEnqueueResult.failure(errorOutput.code(), errorOutput.message()),
            List.of(lookOutput));
      }
      List<PlayerOutput> outputs = appendPrompt(maybeContext.orElseThrow(), List.of(lookOutput));
      return new TextCommandInterpretationResult(enqueueResult, outputs);
    }
    return new TextCommandInterpretationResult(enqueueResult);
  }

  private static boolean requiresGameplayAuthentication(TextCommandType type) {
    return isLookCommand(type)
        || isCommunicationCommand(type)
        || isInventoryCommand(type)
        || isEquipmentCommand(type)
        || isContainerCommand(type)
        || type == TextCommandType.MOVE;
  }

  private static boolean isLookCommand(TextCommandType type) {
    return type == TextCommandType.LOOK || type == TextCommandType.QUICKLOOK;
  }

  private static boolean isCommunicationCommand(TextCommandType type) {
    return type == TextCommandType.SAY
        || type == TextCommandType.WHISPER
        || type == TextCommandType.TELL;
  }

  private static boolean isInventoryCommand(TextCommandType type) {
    return type == TextCommandType.INVENTORY
        || type == TextCommandType.GET
        || type == TextCommandType.DROP;
  }

  private static boolean isEquipmentCommand(TextCommandType type) {
    return type == TextCommandType.EQUIPMENT
        || type == TextCommandType.WEAR
        || type == TextCommandType.REMOVE;
  }

  private static boolean isContainerCommand(TextCommandType type) {
    return type == TextCommandType.CONTAINER
        || type == TextCommandType.PUT
        || type == TextCommandType.TAKE;
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
