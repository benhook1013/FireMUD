package net.firedevops.firemud.gamelogic.logic.script;

import net.firedevops.firemud.gamelogic.logic.command.Command;

/** Hook for invoking external scripting logic. */
public interface ScriptingHook {
  void execute(Command command);
}
