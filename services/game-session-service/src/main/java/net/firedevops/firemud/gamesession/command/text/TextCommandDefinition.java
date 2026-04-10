package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;

record TextCommandDefinition(
    TextCommandType type,
    TextCommandDispatchGroup dispatchGroup,
    TextCommandStageRequirement stageRequirement,
    TextCommandPromptPolicy promptPolicy,
    TextCommandActionCategory actionCategory,
    TextCommandSource source) {
  TextCommandDefinition {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(dispatchGroup, "dispatchGroup must not be null");
    Objects.requireNonNull(stageRequirement, "stageRequirement must not be null");
    Objects.requireNonNull(promptPolicy, "promptPolicy must not be null");
    Objects.requireNonNull(actionCategory, "actionCategory must not be null");
    Objects.requireNonNull(source, "source must not be null");
  }
}
