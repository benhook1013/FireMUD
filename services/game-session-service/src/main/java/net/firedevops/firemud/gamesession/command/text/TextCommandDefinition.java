package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.settings.PlayerCommandCapability;

record TextCommandDefinition(
    String commandId,
    TextCommandType type,
    List<String> aliases,
    TextCommandDispatchGroup dispatchGroup,
    TextCommandStageRequirement stageRequirement,
    TextCommandPromptPolicy promptPolicy,
    TextCommandActionCategory actionCategory,
    List<TextCommandActionTag> actionTags,
    boolean historyRecordable,
    TextCommandSource source,
    String targetingMode,
    String cooldownKey,
    long cooldownTicks,
    String costKey,
    long costAmount,
    String executionHook,
    List<TextCommandEffectDeclaration> effects,
    PlayerCommandCapability capability) {
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
    effects = List.copyOf(effects == null ? List.of() : effects);
    capability = capability == null ? capabilityFor(type) : capability;
    targetingMode = targetingMode == null || targetingMode.isBlank() ? "NONE" : targetingMode;
    if (executionHook != null && executionHook.isBlank()) {
      executionHook = null;
    }
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
      TextCommandSource source,
      String targetingMode,
      String cooldownKey,
      long cooldownTicks,
      String costKey,
      long costAmount,
      String executionHook,
      List<TextCommandEffectDeclaration> effects) {
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
        targetingMode,
        cooldownKey,
        cooldownTicks,
        costKey,
        costAmount,
        executionHook,
        effects,
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
      TextCommandSource source,
      String targetingMode,
      String cooldownKey,
      long cooldownTicks,
      String costKey,
      long costAmount,
      String executionHook) {
    this(
        commandId,
        type,
        aliases,
        dispatchGroup,
        stageRequirement,
        promptPolicy,
        actionCategory,
        actionTags,
        true,
        source,
        targetingMode,
        cooldownKey,
        cooldownTicks,
        costKey,
        costAmount,
        executionHook,
        List.of());
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
      boolean historyRecordable,
      TextCommandSource source,
      String targetingMode,
      String cooldownKey,
      long cooldownTicks,
      String costKey,
      long costAmount,
      String executionHook) {
    this(
        commandId,
        type,
        aliases,
        dispatchGroup,
        stageRequirement,
        promptPolicy,
        actionCategory,
        actionTags,
        historyRecordable,
        source,
        targetingMode,
        cooldownKey,
        cooldownTicks,
        costKey,
        costAmount,
        executionHook,
        List.of());
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
        false,
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
        true,
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
      boolean historyRecordable,
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
        historyRecordable,
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
        true,
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
        true,
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
        true,
        source,
        "NONE",
        null,
        0L,
        null,
        0L,
        null);
  }

  private static PlayerCommandCapability capabilityFor(TextCommandType type) {
    return switch (type) {
      case SAY, WHISPER, TELL, FRIENDS -> PlayerCommandCapability.SOCIAL;
      case WHO -> PlayerCommandCapability.PRESENCE;
      case INVENTORY, EQUIPMENT, CONTAINER, GET, DROP, PUT, TAKE, WEAR, REMOVE ->
          PlayerCommandCapability.INVENTORY;
      case HISTORY -> PlayerCommandCapability.COMMAND_HISTORY;
      default -> PlayerCommandCapability.MANDATORY;
    };
  }
}
