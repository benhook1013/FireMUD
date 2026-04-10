package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuiltInTextCommandRegistryTest {

  private final BuiltInTextCommandRegistry registry = new BuiltInTextCommandRegistry();

  @Test
  void builtInDefinitionsCarryClassificationAndPlatformSourceMetadata() {
    assertDefinition(
        TextCommandType.LOGIN,
        TextCommandDispatchGroup.LOGIN,
        TextCommandActionCategory.META,
        TextCommandSource.PLATFORM_BUILT_IN);
    assertDefinition(
        TextCommandType.WHO,
        TextCommandDispatchGroup.WHO,
        TextCommandActionCategory.META,
        TextCommandSource.PLATFORM_BUILT_IN);
    assertDefinition(
        TextCommandType.MOVE,
        TextCommandDispatchGroup.MOVE,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandSource.PLATFORM_BUILT_IN);
    assertDefinition(
        TextCommandType.SAY,
        TextCommandDispatchGroup.COMMUNICATION,
        TextCommandActionCategory.SOCIAL,
        TextCommandSource.PLATFORM_BUILT_IN);
    assertDefinition(
        TextCommandType.GET,
        TextCommandDispatchGroup.ITEM,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandSource.PLATFORM_BUILT_IN);
  }

  @Test
  void unregisteredCommandFallsBackToExtensionMetadata() {
    TextCommandDefinition definition = registry.definitionFor(TextCommandType.NOOP);

    assertEquals(TextCommandDispatchGroup.ENQUEUE_ONLY, definition.dispatchGroup());
    assertEquals(TextCommandActionCategory.SYSTEM, definition.actionCategory());
    assertEquals(TextCommandSource.EXTENSION, definition.source());
  }

  private void assertDefinition(
      TextCommandType type,
      TextCommandDispatchGroup expectedGroup,
      TextCommandActionCategory expectedCategory,
      TextCommandSource expectedSource) {
    TextCommandDefinition definition = registry.definitionFor(type);

    assertEquals(expectedGroup, definition.dispatchGroup());
    assertEquals(expectedCategory, definition.actionCategory());
    assertEquals(expectedSource, definition.source());
  }
}
