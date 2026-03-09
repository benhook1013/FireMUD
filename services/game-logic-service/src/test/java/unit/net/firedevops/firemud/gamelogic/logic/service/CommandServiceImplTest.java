package net.firedevops.firemud.gamelogic.logic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.firedevops.firemud.gamelogic.logic.command.*;
import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;
import net.firedevops.firemud.gamelogic.logic.event.EventDispatcher;
import net.firedevops.firemud.gamelogic.logic.script.NoOpScriptingHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandServiceImplTest {
  private CommandService service;

  @BeforeEach
  void setUp() {
    CommandParser parser = new DefaultCommandParser();
    EventDispatcher dispatcher = new EventDispatcher();
    CommandProcessor processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    service = new CommandServiceImpl(parser, processor);
  }

  @Test
  void processesMoveCommand() {
    CommandResult result = service.handleCommand("north");
    assertEquals("You move north", result.result());
  }
}
