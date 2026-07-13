package net.firedevops.firemud.gamesession.command.text;

import java.util.List;

/** Legacy fixture adapter; production authored definitions come from admitted release bundles. */
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
                    TextCommandSource.GAME_AUTHORED,
                    action.targetingMode(),
                    action.cooldownKey(),
                    action.cooldownTicks(),
                    action.costKey(),
                    action.costAmount(),
                    action.executionHook()))
        .toList();
  }
}
