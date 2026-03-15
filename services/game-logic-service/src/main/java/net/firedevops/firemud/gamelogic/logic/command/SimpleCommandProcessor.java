package net.firedevops.firemud.gamelogic.logic.command;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;
import net.firedevops.firemud.gamelogic.logic.event.EventDispatcher;
import net.firedevops.firemud.gamelogic.logic.event.GameEvent;
import net.firedevops.firemud.gamelogic.logic.event.GameEventType;
import net.firedevops.firemud.gamelogic.logic.script.ScriptingHook;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/** Basic action processor used for demonstrations. */
@Service
public class SimpleCommandProcessor implements CommandProcessor {
  private static final Logger logger = LoggingUtil.getLogger(SimpleCommandProcessor.class);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "EventDispatcher is injected and not exposed")
  private final EventDispatcher dispatcher;

  private final ScriptingHook scriptingHook;

  public SimpleCommandProcessor(EventDispatcher dispatcher, ScriptingHook scriptingHook) {
    this.dispatcher = dispatcher;
    this.scriptingHook = scriptingHook;
  }

  @Override
  public CommandResult process(Command command) {
    scriptingHook.execute(command);
    String result =
        switch (command.actionType()) {
          case MOVE -> "You move " + command.target();
          case ATTACK -> "You attack " + command.target();
          case INTERACT -> "You interact with " + command.target();
          case EMOTE -> command.target();
          default -> "Unknown action";
        };
    logger.info("Processed action {} -> {}", command.actionType(), result);
    dispatcher.dispatch(new GameEvent(GameEventType.ACTION_EXECUTED, command, result));
    if ("Unknown action".equals(result)) {
      ErrorDetail error = new ErrorDetail("UNKNOWN_COMMAND", "Command not recognized");
      return new CommandResult(result, error);
    }
    return new CommandResult(result, null);
  }
}
