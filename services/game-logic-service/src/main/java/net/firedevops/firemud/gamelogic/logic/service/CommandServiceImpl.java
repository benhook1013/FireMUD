package net.firedevops.firemud.gamelogic.logic.service;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.gamelogic.logic.command.Command;
import net.firedevops.firemud.gamelogic.logic.command.CommandParser;
import net.firedevops.firemud.gamelogic.logic.command.CommandProcessor;
import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;
import org.springframework.stereotype.Service;

@Service
public class CommandServiceImpl implements CommandService {
  private final CommandParser parser;
  private final CommandProcessor processor;

  public CommandServiceImpl(CommandParser parser, CommandProcessor processor) {
    this.parser = parser;
    this.processor = processor;
  }

  @Override
  @Timed(value = "game_logic.handle_command")
  public CommandResult handleCommand(String commandText) {
    Command cmd = parser.parse(commandText);
    return processor.process(cmd);
  }
}
