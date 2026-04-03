package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
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
          List.of(PlayerOutput.error("UNKNOWN_COMMAND", message)));
    }
    if (command.type() == TextCommandType.WORLDS) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.success(), List.of(PlayerOutput.notice(worldsHandler.describe())));
    }
    if (command.type() == TextCommandType.LOGIN) {
      LoginCommandHandlingResult loginResult =
          loginHandler.handle(sessionId, command, requiresSoloTick);
      return new TextCommandInterpretationResult(
          loginResult.commandResult(), loginResult.responseText());
    }

    Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    boolean hasLogin = maybeContext.isPresent();
    boolean hasPlay =
        maybeContext.isPresent() && StringUtils.hasText(maybeContext.get().roomInstanceId());

    if (command.type() == TextCommandType.PLAY) {
      if (!hasLogin) {
        return stageFailure(
            GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
            GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE);
      }
      PlayCommandHandlingResult playResult = playHandler.handle(sessionId, command);
      List<PlayerOutput> outputs =
          playResult.responseText() == null
              ? List.of()
              : List.of(PlayerOutput.notice(playResult.responseText()));
      if (playResult.commandResult().accepted() && !playResult.reconnectRedrawRecommended()) {
        outputs = appendPrompt(sessionId, outputs);
      }
      return new TextCommandInterpretationResult(
          playResult.commandResult(), outputs, playResult.reconnectRedrawRecommended());
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

    if (isCommunicationCommand(command.type())) {
      CommunicationCommandHandlingResult communicationResult =
          communicationHandler.handle(maybeContext.orElseThrow(), command);
      List<PlayerOutput> outputs =
          communicationResult.responseText() == null
              ? List.of()
              : List.of(PlayerOutput.message(communicationResult.responseText()));
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
    if (enqueueResult.accepted() && command.type() == TextCommandType.LOOK) {
      String lookText = lookHandler.describe(sessionId);
      if (isLookError(lookText)) {
        return failureResultForLookError(lookText);
      } else {
        List<PlayerOutput> outputs =
            lookText == null ? List.of() : List.of(PlayerOutput.view(lookText));
        outputs = appendPrompt(maybeContext.orElseThrow(), outputs);
        return new TextCommandInterpretationResult(enqueueResult, outputs);
      }
    }
    return new TextCommandInterpretationResult(enqueueResult);
  }

  private static boolean requiresGameplayAuthentication(TextCommandType type) {
    return type == TextCommandType.LOOK
        || isCommunicationCommand(type)
        || type == TextCommandType.MOVE;
  }

  private static boolean isCommunicationCommand(TextCommandType type) {
    return type == TextCommandType.SAY
        || type == TextCommandType.WHISPER
        || type == TextCommandType.TELL;
  }

  private TextCommandInterpretationResult stageFailure(String code, String message) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
  }

  private boolean isLookError(String text) {
    return text != null && text.startsWith("ERROR ");
  }

  private TextCommandInterpretationResult failureResultForLookError(String errorText) {
    String payload = errorText.substring("ERROR ".length());
    String code;
    String message = "";
    int firstSpace = payload.indexOf(' ');
    if (firstSpace >= 0) {
      code = payload.substring(0, firstSpace);
      message = payload.substring(firstSpace + 1).trim();
    } else {
      code = payload;
    }
    if (code.isBlank()) {
      code = "LOOK_UNAVAILABLE";
    }
    if (message.isBlank()) {
      message = "Look unavailable";
    }
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.error(code, message)));
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
