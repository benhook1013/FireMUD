package net.firedevops.firemud.gamesession.command.text;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
final class BuiltInTextCommandRegistry implements TextCommandRegistry {
  private final Map<TextCommandType, TextCommandDefinition> definitions;

  BuiltInTextCommandRegistry() {
    EnumMap<TextCommandType, TextCommandDefinition> definitions =
        new EnumMap<>(TextCommandType.class);
    register(
        definitions,
        TextCommandType.WORLDS,
        TextCommandDispatchGroup.WORLDS,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.LOGIN,
        TextCommandDispatchGroup.SESSION,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.LOGOUT,
        TextCommandDispatchGroup.SESSION,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.HELP,
        TextCommandDispatchGroup.HELP,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.WHEN_LOGGED_IN,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.AFK,
        TextCommandDispatchGroup.ACTIVITY,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.PLAY,
        TextCommandDispatchGroup.SESSION,
        TextCommandStageRequirement.LOGIN,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META);
    register(
        definitions,
        TextCommandType.WHO,
        TextCommandDispatchGroup.WHO,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META);
    registerGroup(
        definitions,
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.INVENTORY,
        TextCommandType.GET,
        TextCommandType.DROP);
    registerGroup(
        definitions,
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.EQUIPMENT,
        TextCommandType.WEAR,
        TextCommandType.REMOVE);
    registerGroup(
        definitions,
        TextCommandDispatchGroup.ITEM,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY,
        TextCommandType.CONTAINER,
        TextCommandType.PUT,
        TextCommandType.TAKE);
    registerGroup(
        definitions,
        TextCommandDispatchGroup.COMMUNICATION,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.SOCIAL,
        TextCommandType.SAY,
        TextCommandType.WHISPER,
        TextCommandType.TELL);
    register(
        definitions,
        TextCommandType.MOVE,
        TextCommandDispatchGroup.MOVE,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.GAMEPLAY);
    registerGroup(
        definitions,
        TextCommandDispatchGroup.LOOK,
        TextCommandStageRequirement.GAMEPLAY,
        TextCommandPromptPolicy.WHEN_GAMEPLAY,
        TextCommandActionCategory.META,
        TextCommandType.LOOK,
        TextCommandType.QUICKLOOK);
    this.definitions = Map.copyOf(definitions);
  }

  @Override
  public Optional<TextCommandDefinition> findDefinition(TextCommandType type) {
    return Optional.ofNullable(definitions.get(type));
  }

  private static void registerGroup(
      EnumMap<TextCommandType, TextCommandDefinition> definitions,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      TextCommandType... types) {
    for (TextCommandType type : types) {
      register(definitions, type, dispatchGroup, stageRequirement, promptPolicy, actionCategory);
    }
  }

  private static void register(
      EnumMap<TextCommandType, TextCommandDefinition> definitions,
      TextCommandType type,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory) {
    definitions.put(
        type,
        new TextCommandDefinition(
            type,
            dispatchGroup,
            stageRequirement,
            promptPolicy,
            actionCategory,
            TextCommandSource.PLATFORM_BUILT_IN));
  }
}
