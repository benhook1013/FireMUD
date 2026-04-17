package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;

record TextCommandDefinition(
    String commandId,
    TextCommandType type,
    List<String> aliases,
    TextCommandDispatchGroup dispatchGroup,
    TextCommandStageRequirement stageRequirement,
    TextCommandPromptPolicy promptPolicy,
    TextCommandActionCategory actionCategory,
    TextCommandSource source) {
  TextCommandDefinition {
    Objects.requireNonNull(commandId, "commandId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(aliases, "aliases must not be null");
    Objects.requireNonNull(dispatchGroup, "dispatchGroup must not be null");
    Objects.requireNonNull(stageRequirement, "stageRequirement must not be null");
    Objects.requireNonNull(promptPolicy, "promptPolicy must not be null");
    Objects.requireNonNull(actionCategory, "actionCategory must not be null");
    Objects.requireNonNull(source, "source must not be null");
    aliases = List.copyOf(aliases);
  }

  static TextCommandDefinition extensionDefinition(TextCommandType type, String commandId) {
    return new TextCommandDefinition(
        commandId,
        type,
        List.of(),
        TextCommandDispatchGroup.ENQUEUE_ONLY,
        TextCommandStageRequirement.NONE,
        TextCommandPromptPolicy.NEVER,
        TextCommandActionCategory.SYSTEM,
        TextCommandSource.EXTENSION);
  }

  TextCommandDefinition(
      TextCommandType type,
      List<String> aliases,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      TextCommandSource source) {
    this(
        type.name().toLowerCase(java.util.Locale.ROOT),
        type,
        aliases,
        dispatchGroup,
        stageRequirement,
        promptPolicy,
        actionCategory,
        source);
  }
}
