package net.firedevops.firemud.logic.service;

import net.firedevops.firemud.logic.command.Command;
import net.firedevops.firemud.logic.command.CommandParser;
import net.firedevops.firemud.logic.command.CommandProcessor;
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
  public String handleCommand(String commandText) {
    Command cmd = parser.parse(commandText);
    return processor.process(cmd);
  }
}
