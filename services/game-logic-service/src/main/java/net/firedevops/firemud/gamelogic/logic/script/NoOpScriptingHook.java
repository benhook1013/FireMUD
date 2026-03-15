package net.firedevops.firemud.gamelogic.logic.script;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamelogic.logic.command.Command;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/** Default scripting hook that simply logs the command. */
@Component
public class NoOpScriptingHook implements ScriptingHook {
  private static final Logger logger = LoggingUtil.getLogger(NoOpScriptingHook.class);

  @Override
  public void execute(Command command) {
    logger.debug("No scripting for command: {}", command.raw());
  }
}
