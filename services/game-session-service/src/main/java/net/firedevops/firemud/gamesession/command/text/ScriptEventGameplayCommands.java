package net.firedevops.firemud.gamesession.command.text;

import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import org.springframework.util.StringUtils;

final class ScriptEventGameplayCommands {
  private ScriptEventGameplayCommands() {}

  static GameplayCommand synthetic(String prefix, TextCommand command) {
    return synthetic(prefix, command, command.type().name(), null);
  }

  static GameplayCommand synthetic(String prefix, String commandName, String rawLine) {
    return syntheticWithId(syntheticId(prefix), commandName, rawLine, null);
  }

  static GameplayCommand synthetic(
      String prefix, TextCommand command, String commandName, String executionHook) {
    return syntheticWithId(syntheticId(prefix), command, commandName, executionHook);
  }

  static GameplayCommand syntheticWithId(String commandId, String commandName, String rawLine) {
    return syntheticWithId(commandId, commandName, rawLine, null);
  }

  private static String syntheticId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  static GameplayCommand syntheticWithId(
      String commandId, TextCommand command, String commandName, String executionHook) {
    return syntheticWithId(commandId, commandName, command.rawLine(), executionHook);
  }

  static GameplayCommand syntheticWithId(
      String commandId, String commandName, String rawLine, String executionHook) {
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId(commandId);
    gameplayCommand.setCommandName(commandName);
    if (StringUtils.hasText(rawLine)) {
      gameplayCommand.setCommandText(rawLine);
    }
    if (StringUtils.hasText(executionHook)) {
      gameplayCommand.setExecutionHook(executionHook);
    }
    return gameplayCommand;
  }
}
