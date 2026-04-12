package net.firedevops.firemud.gamesession.command.text;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class BuiltInTextCommandDefinitionProvider implements TextCommandDefinitionProvider {
  private final Map<TextCommandType, TextCommandDefinition> definitions;

  BuiltInTextCommandDefinitionProvider() {
    EnumMap<TextCommandType, TextCommandDefinition> definitions =
        new EnumMap<>(TextCommandType.class);
    register(
        definitions,
        TextCommandType.WORLDS,
        List.of("worlds"),
        TextCommandDispatchGroup.WORLDS,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.REALMS,
        List.of("realms"),
        TextCommandDispatchGroup.WORLDS,
        TextCommandStageRequirement.LOGIN,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.CHARS,
        List.of("chars"),
        TextCommandDispatchGroup.WORLDS,
        TextCommandStageRequirement.LOGIN,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.LOGIN,
        List.of("login", "logon"),
        TextCommandDispatchGroup.SESSION,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.LOGOUT,
        List.of("logout", "logoff", "quit"),
        TextCommandDispatchGroup.SESSION,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.HELP,
        List.of("help"),
        TextCommandDispatchGroup.HELP,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.WHEN_LOGGED_IN,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.AFK,
        List.of("afk", "brb"),
        TextCommandDispatchGroup.ACTIVITY,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.PLAY,
        List.of("play"),
        TextCommandDispatchGroup.SESSION,
        TextCommandStageRequirement.LOGIN,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.WHO,
        List.of("who"),
        TextCommandDispatchGroup.WHO,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META);
    registerGroup(
        definitions,
        List.of("inventory", "inv", "i"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.INVENTORY);
    registerGroup(
        definitions,
        List.of("get"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.GET);
    registerGroup(
        definitions,
        List.of("drop"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.DROP);
    registerGroup(
        definitions,
        List.of("equipment", "equip", "eq"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.EQUIPMENT);
    registerGroup(
        definitions,
        List.of("container", "cont"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.CONTAINER);
    registerGroup(
        definitions,
        List.of("put"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.PUT);
    registerGroup(
        definitions,
        List.of("take"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.TAKE);
    registerGroup(
        definitions,
        List.of("wear"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.WEAR);
    registerGroup(
        definitions,
        List.of("remove"),
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.REMOVE);
    registerGroup(
        definitions,
        List.of("say"),
        TextCommandDispatchGroup.COMMUNICATION,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.SOCIAL,
        TextCommandType.SAY);
    registerGroup(
        definitions,
        List.of("whisper"),
        TextCommandDispatchGroup.COMMUNICATION,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.SOCIAL,
        TextCommandType.WHISPER);
    registerGroup(
        definitions,
        List.of("tell"),
        TextCommandDispatchGroup.COMMUNICATION,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.SOCIAL,
        TextCommandType.TELL);
    register(
        definitions,
        TextCommandType.MOVE,
        List.of(
            "move", "go", "north", "south", "east", "west", "up", "down", "n", "s", "e", "w", "u",
            "d"),
        TextCommandDispatchGroup.MOVE,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY);
    registerGroup(
        definitions,
        List.of("look", "l"),
        TextCommandDispatchGroup.LOOK,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META,
        TextCommandType.LOOK);
    registerGroup(
        definitions,
        List.of("quicklook", "qlook"),
        TextCommandDispatchGroup.LOOK,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META,
        TextCommandType.QUICKLOOK);
    this.definitions = Map.copyOf(definitions);
  }

  @Override
  public List<TextCommandDefinition> definitions() {
    return List.copyOf(definitions.values());
  }

  private static void registerGroup(
      EnumMap<TextCommandType, TextCommandDefinition> definitions,
      List<String> aliases,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      TextCommandType... types) {
    for (TextCommandType type : types) {
      register(
          definitions,
          type,
          aliases,
          dispatchGroup,
          stageRequirement,
          promptPolicy,
          actionCategory);
    }
  }

  private static void register(
      EnumMap<TextCommandType, TextCommandDefinition> definitions,
      TextCommandType type,
      List<String> aliases,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory) {
    definitions.put(
        type,
        new TextCommandDefinition(
            type,
            aliases,
            dispatchGroup,
            stageRequirement,
            promptPolicy,
            actionCategory,
            TextCommandSource.PLATFORM_BUILT_IN));
  }
}
