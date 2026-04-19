package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class ConfiguredAuthoredActionDefinitionProvider implements TextCommandDefinitionProvider {
  private final ConfiguredAuthoredActionCatalog catalog;

  ConfiguredAuthoredActionDefinitionProvider(ConfiguredAuthoredActionCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public List<TextCommandDefinition> definitions() {
    return catalog.all().stream()
        .map(
            action ->
                new TextCommandDefinition(
                    action.commandId(),
                    TextCommandType.AUTHORED,
                    action.aliases(),
                    TextCommandDispatchGroup.AUTHORED,
                    action.stageRequirement(),
                    action.promptPolicy(),
                    action.actionCategory(),
                    action.actionTags(),
                    TextCommandSource.GAME_AUTHORED))
        .toList();
  }
}
