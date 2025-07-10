package net.firedevops.firemud.logic.command;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.logic.event.EventDispatcher;
import net.firedevops.firemud.logic.event.GameEvent;
import net.firedevops.firemud.logic.event.GameEventType;
import net.firedevops.firemud.logic.script.ScriptingHook;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/** Basic action processor used for demonstrations. */
@Service
public class SimpleCommandProcessor implements CommandProcessor {
  private static final Logger logger = LoggingUtil.getLogger(SimpleCommandProcessor.class);

  private final EventDispatcher dispatcher;
  private final ScriptingHook scriptingHook;

  public SimpleCommandProcessor(EventDispatcher dispatcher, ScriptingHook scriptingHook) {
    this.dispatcher = dispatcher;
    this.scriptingHook = scriptingHook;
  }

  @Override
  public String process(Command command) {
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
    return result;
  }
}
