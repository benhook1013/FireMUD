package net.firedevops.firemud.gamesession.command.text;

import java.util.Optional;

interface TextCommandRegistry {
  Optional<TextCommandDefinition> findDefinition(TextCommandType type);
}
