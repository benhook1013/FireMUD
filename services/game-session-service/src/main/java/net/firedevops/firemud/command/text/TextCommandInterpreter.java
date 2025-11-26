package net.firedevops.firemud.command.text;

import java.util.Objects;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TextCommandInterpreter {
  private final CommandService commandService;
  private final TextCommandParser parser;

  @Autowired
  public TextCommandInterpreter(CommandService commandService) {
    this(commandService, new TextCommandParser());
  }

  TextCommandInterpreter(CommandService commandService, TextCommandParser parser) {
    this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
  }

  public CommandEnqueueResult interpret(String sessionId, String rawLine, boolean requiresSoloTick) {
    return interpret(sessionId, parser.parse(rawLine), requiresSoloTick);
  }

  public CommandEnqueueResult interpret(String sessionId, TextCommand command, boolean requiresSoloTick) {
    if (command.type() == TextCommandType.UNKNOWN) {
      return CommandEnqueueResult.failure("UNKNOWN_COMMAND", command.rawLine());
    }
    return commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
  }
}
