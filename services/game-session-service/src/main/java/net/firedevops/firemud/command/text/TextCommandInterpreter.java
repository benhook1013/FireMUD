package net.firedevops.firemud.command.text;

import java.util.Objects;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TextCommandInterpreter {
  private final CommandService commandService;
  private final LookCommandHandler lookHandler;
  private final TextCommandParser parser;

  @Autowired
  public TextCommandInterpreter(CommandService commandService, LookCommandHandler lookHandler) {
    this(commandService, lookHandler, new TextCommandParser());
  }

  TextCommandInterpreter(
      CommandService commandService, LookCommandHandler lookHandler, TextCommandParser parser) {
    this.commandService =
        Objects.requireNonNull(commandService, "commandService must not be null");
    this.lookHandler = Objects.requireNonNull(lookHandler, "lookHandler must not be null");
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
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("UNKNOWN_COMMAND", command.rawLine()), null);
    }
    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    String response = null;
    if (enqueueResult.accepted() && command.type() == TextCommandType.LOOK) {
      response = lookHandler.describe(sessionId);
    }
    return new TextCommandInterpretationResult(enqueueResult, response);
  }
}
