package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
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
  private final SayCommandHandler sayHandler;
  private final WorldsCommandHandler worldsHandler;
  private final TextCommandParser parser;

  @Autowired
  public TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      SessionAuthenticationService sessionAuthenticationService,
      SayCommandHandler sayHandler,
      WorldsCommandHandler worldsHandler) {
    this(
        commandService,
        lookHandler,
        loginHandler,
        playHandler,
        moveHandler,
        sessionAuthenticationService,
        sayHandler,
        worldsHandler,
        new TextCommandParser());
  }

  TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      SessionAuthenticationService sessionAuthenticationService,
      SayCommandHandler sayHandler,
      WorldsCommandHandler worldsHandler,
      TextCommandParser parser) {
    this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    this.lookHandler = Objects.requireNonNull(lookHandler, "lookHandler must not be null");
    this.loginHandler = Objects.requireNonNull(loginHandler, "loginHandler must not be null");
    this.playHandler = Objects.requireNonNull(playHandler, "playHandler must not be null");
    this.moveHandler = Objects.requireNonNull(moveHandler, "moveHandler must not be null");
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.sayHandler = Objects.requireNonNull(sayHandler, "sayHandler must not be null");
    this.worldsHandler = Objects.requireNonNull(worldsHandler, "worldsHandler must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, String rawLine, boolean requiresSoloTick) {
    return interpret(sessionId, parser.parse(rawLine), requiresSoloTick);
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    if (command.type() == TextCommandType.NOOP) {
      return new TextCommandInterpretationResult(CommandEnqueueResult.success(), null);
    }
    if (command.type() == TextCommandType.UNKNOWN) {
      String message = "Unknown command";
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("UNKNOWN_COMMAND", message), null);
    }
    if (command.type() == TextCommandType.WORLDS) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.success(), worldsHandler.describe(), true);
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
      return new TextCommandInterpretationResult(
          playResult.commandResult(), playResult.responseText(), true);
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

    if (command.type() == TextCommandType.SAY) {
      SayCommandHandlingResult sayResult = sayHandler.handle(sessionId, command);
      return new TextCommandInterpretationResult(
          sayResult.commandResult(), sayResult.responseText());
    }

    if (command.type() == TextCommandType.MOVE) {
      MoveCommandHandlingResult moveResult = moveHandler.handle(sessionId, command);
      return new TextCommandInterpretationResult(
          moveResult.commandResult(), moveResult.responseText(), true);
    }

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    String response = null;
    if (enqueueResult.accepted() && command.type() == TextCommandType.LOOK) {
      String lookText = lookHandler.describe(sessionId);
      if (isLookError(lookText)) {
        enqueueResult = failureForLookError(lookText);
      } else {
        response = lookText;
      }
    }
    return new TextCommandInterpretationResult(enqueueResult, response);
  }

  private static boolean requiresGameplayAuthentication(TextCommandType type) {
    return type == TextCommandType.LOOK
        || type == TextCommandType.SAY
        || type == TextCommandType.MOVE;
  }

  private TextCommandInterpretationResult stageFailure(String code, String message) {
    return new TextCommandInterpretationResult(CommandEnqueueResult.failure(code, message), null);
  }

  private boolean isLookError(String text) {
    return text != null && text.startsWith("ERROR ");
  }

  private CommandEnqueueResult failureForLookError(String errorText) {
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
    return CommandEnqueueResult.failure(code, message);
  }
}
