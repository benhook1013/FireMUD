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
    action.setActionTags(
        List.of(TextCommandActionTag.AUTHORING, TextCommandActionTag.COMMUNICATION));
    action.setTargetingMode("TARGET_CHARACTER");
    action.setCooldownKey("wave-cooldown");
    action.setCooldownMs(10_000L);
    action.setCostKey("stamina");
    action.setCostAmount(3L);
    action.setExecutionHook("action:wave");
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
    assertEquals(
        List.of(TextCommandActionTag.AUTHORING, TextCommandActionTag.COMMUNICATION),
        definition.actionTags());
    assertEquals(TextCommandSource.GAME_AUTHORED, definition.source());
    assertEquals("TARGET_CHARACTER", definition.targetingMode());
    assertEquals("wave-cooldown", definition.cooldownKey());
    assertEquals(10_000L, definition.cooldownMs());
    assertEquals("stamina", definition.costKey());
    assertEquals(3L, definition.costAmount());
    assertEquals("action:wave", definition.executionHook());
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

  @Test
  void catalogRejectsCooldownMetadataWithoutCooldownKey() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setCommandId("wave-salute");
    action.setCooldownMs(5000);
    properties.setActions(List.of(action));

    assertThrows(
        IllegalStateException.class, () -> new ConfiguredAuthoredActionCatalog(properties));
  }

  @Test
  void catalogRejectsCostMetadataWithoutCostKey() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setCommandId("wave-salute");
    action.setCostAmount(2);
    properties.setActions(List.of(action));

    assertThrows(
        IllegalStateException.class, () -> new ConfiguredAuthoredActionCatalog(properties));
  }

  @Test
  void providerNormalizesBlankExecutionHookToNull() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setActionId("wave-salute");
    action.setCommandId("wave-salute");
    action.setExecutionHook("  ");
    properties.setActions(List.of(action));

    ConfiguredAuthoredActionDefinitionProvider provider =
        new ConfiguredAuthoredActionDefinitionProvider(
            new ConfiguredAuthoredActionCatalog(properties));

    TextCommandDefinition definition = provider.definitions().getFirst();
    assertEquals(null, definition.executionHook());
  }

  @Test
  void catalogRejectsNegativeCooldownMs() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setCommandId("wave-salute");
    action.setCooldownMs(-1);
    properties.setActions(List.of(action));

    assertThrows(
        IllegalStateException.class, () -> new ConfiguredAuthoredActionCatalog(properties));
  }

  @Test
  void catalogRejectsOrphanedCooldownKey() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setCommandId("wave-salute");
    action.setCooldownMs(0);
    action.setCooldownKey("wave-cooldown");
    properties.setActions(List.of(action));

    assertThrows(
        IllegalStateException.class, () -> new ConfiguredAuthoredActionCatalog(properties));
  }

  @Test
  void catalogRejectsOrphanedCostKey() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setCommandId("wave-salute");
    action.setCostAmount(0);
    action.setCostKey("stamina");
    properties.setActions(List.of(action));

    assertThrows(
        IllegalStateException.class, () -> new ConfiguredAuthoredActionCatalog(properties));
  }
}
