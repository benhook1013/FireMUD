package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import org.junit.jupiter.api.Test;

class ConfiguredAuthoredActionDefinitionProviderTest {

  @Test
  void providerPublishesAuthoredDefinitionsThroughSharedRegistryMetadata() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setActionId("wave-salute");
    action.setCommandId("wave-salute");
    action.setAliases(List.of("salute", "hail"));
    action.setStageRequirement(TextCommandStageRequirement.GAMEPLAY);
    action.setPromptPolicy(TextCommandPromptPolicy.WHEN_GAMEPLAY);
    action.setActionCategory(TextCommandActionCategory.SOCIAL);
    properties.setActions(List.of(action));

    ConfiguredAuthoredActionDefinitionProvider provider =
        new ConfiguredAuthoredActionDefinitionProvider(
            new ConfiguredAuthoredActionCatalog(properties));

    TextCommandDefinition definition = provider.definitions().getFirst();

    assertEquals("wave-salute", definition.commandId());
    assertEquals(TextCommandType.AUTHORED, definition.type());
    assertEquals(List.of("salute", "hail"), definition.aliases());
    assertEquals(TextCommandDispatchGroup.AUTHORED, definition.dispatchGroup());
    assertEquals(TextCommandActionCategory.SOCIAL, definition.actionCategory());
    assertEquals(TextCommandSource.GAME_AUTHORED, definition.source());
  }

  @Test
  void catalogRejectsDuplicateAuthoredCommandIds() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action first = new AuthoredActionProperties.Action();
    first.setCommandId("wave-salute");
    AuthoredActionProperties.Action second = new AuthoredActionProperties.Action();
    second.setCommandId("wave-salute");
    properties.setActions(List.of(first, second));

    assertThrows(
        IllegalStateException.class, () -> new ConfiguredAuthoredActionCatalog(properties));
  }

  @Test
  void catalogRejectsDuplicateAuthoredAliases() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action first = new AuthoredActionProperties.Action();
    first.setCommandId("wave-salute");
    first.setAliases(List.of("salute"));
    AuthoredActionProperties.Action second = new AuthoredActionProperties.Action();
    second.setCommandId("wave-bow");
    second.setAliases(List.of("salute"));
    properties.setActions(List.of(first, second));

    assertThrows(
        IllegalStateException.class, () -> new ConfiguredAuthoredActionCatalog(properties));
  }
}
