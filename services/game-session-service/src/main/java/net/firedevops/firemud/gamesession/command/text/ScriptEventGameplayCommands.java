package net.firedevops.firemud.gamesession.command.text;

import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import org.springframework.util.StringUtils;

final class ScriptEventGameplayCommands {
  private ScriptEventGameplayCommands() {}

  static GameplayCommand synthetic(String prefix, TextCommand command) {
    return syntheticWithId(prefix + "-" + UUID.randomUUID(), command, command.type().name(), null);
  }

  static GameplayCommand synthetic(
      String prefix, TextCommand command, String commandName, String executionHook) {
    return syntheticWithId(prefix + "-" + UUID.randomUUID(), command, commandName, executionHook);
  }

  static GameplayCommand syntheticWithId(
      String commandId, TextCommand command, String commandName, String executionHook) {
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId(commandId);
    gameplayCommand.setCommandName(commandName);
    if (StringUtils.hasText(command.rawLine())) {
      gameplayCommand.setCommandText(command.rawLine());
    }
    if (StringUtils.hasText(executionHook)) {
      gameplayCommand.setExecutionHook(executionHook);
    }
    return gameplayCommand;
  }
}
