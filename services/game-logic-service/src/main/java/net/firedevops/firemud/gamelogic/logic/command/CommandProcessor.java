package net.firedevops.firemud.gamelogic.logic.command;

import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;

/** Processes parsed commands and returns a result object. */
public interface CommandProcessor {
  CommandResult process(Command command);
}
