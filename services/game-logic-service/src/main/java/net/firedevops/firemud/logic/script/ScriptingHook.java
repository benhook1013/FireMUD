package net.firedevops.firemud.logic.script;

import net.firedevops.firemud.logic.command.Command;

/** Hook for invoking external scripting logic. */
public interface ScriptingHook {
  void execute(Command command);
}
