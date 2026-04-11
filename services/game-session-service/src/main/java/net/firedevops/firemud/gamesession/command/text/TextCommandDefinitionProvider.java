package net.firedevops.firemud.gamesession.command.text;

import java.util.List;

interface TextCommandDefinitionProvider {
  List<TextCommandDefinition> definitions();
}
