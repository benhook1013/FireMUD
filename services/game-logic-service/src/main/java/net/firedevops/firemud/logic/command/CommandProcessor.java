package net.firedevops.firemud.logic.command;

import net.firedevops.firemud.logic.dto.CommandResult;

/** Processes parsed commands and returns a result object. */
public interface CommandProcessor {
  CommandResult process(Command command);
}
