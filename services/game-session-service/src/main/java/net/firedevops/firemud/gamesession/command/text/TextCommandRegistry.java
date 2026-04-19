package net.firedevops.firemud.gamesession.command.text;

import java.util.Optional;

interface TextCommandRegistry {
  default Optional<TextCommandDefinition> findDefinition(String commandId) {
    if (commandId == null || commandId.isBlank()) {
      return Optional.empty();
    }
    TextCommandType type = TextCommandType.fromToken(commandId);
    return type == TextCommandType.UNKNOWN ? Optional.empty() : findDefinition(type);
  }

  Optional<TextCommandDefinition> findDefinition(TextCommandType type);

  Optional<TextCommandDefinition> findDefinitionByAlias(String alias);
}
