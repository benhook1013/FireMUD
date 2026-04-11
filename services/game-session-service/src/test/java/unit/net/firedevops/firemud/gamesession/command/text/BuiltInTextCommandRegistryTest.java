package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuiltInTextCommandRegistryTest {

  private final BuiltInTextCommandRegistry registry = new BuiltInTextCommandRegistry();

  @Test
  void builtInDefinitionsCarryClassificationAndPlatformSourceMetadata() {
    assertDefinition(
        TextCommandType.LOGIN,
        TextCommandDispatchGroup.SESSION,
        TextCommandActionCategory.META,
        TextCommandSource.PLATFORM_BUILT_IN);
    assertDefinition(
        TextCommandType.LOGOUT,
        TextCommandDispatchGroup.SESSION,
        TextCommandActionCategory.META,
        TextCommandSource.PLATFORM_BUILT_IN);
    assertDefinition(
        TextCommandType.AFK,
        TextCommandDispatchGroup.ACTIVITY,
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
  void builtInRegistryOnlyOwnsExplicitPlatformCommands() {
    assertFalse(registry.findDefinition(TextCommandType.NOOP).isPresent());
    assertFalse(registry.findDefinition(TextCommandType.UNKNOWN).isPresent());
    assertTrue(registry.findDefinition(TextCommandType.LOGIN).isPresent());
  }

  @Test
  void activeBuiltInCommandSurfaceIsExplicitlyRegistered() {
    for (TextCommandType type : TextCommandType.values()) {
      if (type == TextCommandType.NOOP || type == TextCommandType.UNKNOWN) {
        continue;
      }
      TextCommandDefinition definition = registry.findDefinition(type).orElseThrow();

      assertEquals(TextCommandSource.PLATFORM_BUILT_IN, definition.source(), type.name());
      assertNotEquals(
          TextCommandDispatchGroup.ENQUEUE_ONLY, definition.dispatchGroup(), type.name());
    }
  }

  private void assertDefinition(
      TextCommandType type,
      TextCommandDispatchGroup expectedGroup,
      TextCommandActionCategory expectedCategory,
      TextCommandSource expectedSource) {
    TextCommandDefinition definition = registry.findDefinition(type).orElseThrow();

    assertEquals(expectedGroup, definition.dispatchGroup());
    assertEquals(expectedCategory, definition.actionCategory());
    assertEquals(expectedSource, definition.source());
  }
}
