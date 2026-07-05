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
    List<TextCommandActionTag> actionTags,
    TextCommandSource source,
    String targetingMode,
    String cooldownKey,
    long cooldownMs,
    String costKey,
    long costAmount,
    String executionHook) {
  TextCommandDefinition {
    Objects.requireNonNull(commandId, "commandId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(aliases, "aliases must not be null");
    Objects.requireNonNull(dispatchGroup, "dispatchGroup must not be null");
    Objects.requireNonNull(stageRequirement, "stageRequirement must not be null");
    Objects.requireNonNull(promptPolicy, "promptPolicy must not be null");
    Objects.requireNonNull(actionCategory, "actionCategory must not be null");
    Objects.requireNonNull(actionTags, "actionTags must not be null");
    Objects.requireNonNull(source, "source must not be null");
    aliases = List.copyOf(aliases);
    actionTags = List.copyOf(actionTags);
    targetingMode = targetingMode == null || targetingMode.isBlank() ? "NONE" : targetingMode;
    if (executionHook != null && executionHook.isBlank()) {
      executionHook = null;
    }
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
        List.of(),
        TextCommandSource.EXTENSION,
        "NONE",
        null,
        0L,
        null,
        0L,
        null);
  }

  TextCommandDefinition(
      String commandId,
      TextCommandType type,
      List<String> aliases,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      TextCommandSource source) {
    this(
        commandId,
        type,
        aliases,
        dispatchGroup,
        stageRequirement,
        promptPolicy,
        actionCategory,
        List.of(),
        source,
        "NONE",
        null,
        0L,
        null,
        0L,
        null);
  }

  TextCommandDefinition(
      String commandId,
      TextCommandType type,
      List<String> aliases,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      List<TextCommandActionTag> actionTags,
      TextCommandSource source) {
    this(
        commandId,
        type,
        aliases,
        dispatchGroup,
        stageRequirement,
        promptPolicy,
        actionCategory,
        actionTags,
        source,
        "NONE",
        null,
        0L,
        null,
        0L,
        null);
  }

  TextCommandDefinition(
      TextCommandType type,
      List<String> aliases,
      TextCommandDispatchGroup dispatchGroup,
      TextCommandStageRequirement stageRequirement,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      List<TextCommandActionTag> actionTags,
      TextCommandSource source) {
    this(
        type.name().toLowerCase(java.util.Locale.ROOT),
        type,
        aliases,
        dispatchGroup,
        stageRequirement,
        promptPolicy,
        actionCategory,
        actionTags,
        source,
        "NONE",
        null,
        0L,
        null,
        0L,
        null);
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
        List.of(),
        source,
        "NONE",
        null,
        0L,
        null,
        0L,
        null);
  }
}
