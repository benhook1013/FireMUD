package net.firedevops.firemud.command.text;

import java.util.Objects;
import net.firedevops.firemud.command.text.LoginCommandHandlingResult;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.SessionAuthenticationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TextCommandInterpreter {
  private final CommandService commandService;
  private final LookCommandHandler lookHandler;
  private final LoginCommandHandler loginHandler;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final TextCommandParser parser;

  @Autowired
  public TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      SessionAuthenticationService sessionAuthenticationService) {
    this(
        commandService,
        lookHandler,
        loginHandler,
        sessionAuthenticationService,
        new TextCommandParser());
  }

  TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      SessionAuthenticationService sessionAuthenticationService,
      TextCommandParser parser) {
    this.commandService =
        Objects.requireNonNull(commandService, "commandService must not be null");
    this.lookHandler = Objects.requireNonNull(lookHandler, "lookHandler must not be null");
    this.loginHandler = Objects.requireNonNull(loginHandler, "loginHandler must not be null");
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
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
    if (command.type() == TextCommandType.LOGIN) {
      LoginCommandHandlingResult loginResult =
          loginHandler.handle(sessionId, command, requiresSoloTick);
      return new TextCommandInterpretationResult(
          loginResult.commandResult(), loginResult.responseText());
    }

    if (requiresGameplayAuthentication(command.type())
        && !sessionAuthenticationService.isAuthenticated(sessionId)) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("NOT_AUTHENTICATED", "Login required"), null);
    }

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    String response = null;
    if (enqueueResult.accepted() && command.type() == TextCommandType.LOOK) {
      response = lookHandler.describe(sessionId);
    }
    return new TextCommandInterpretationResult(enqueueResult, response);
  }

  private static boolean requiresGameplayAuthentication(TextCommandType type) {
    return type == TextCommandType.LOOK || type == TextCommandType.SAY;
  }
}
