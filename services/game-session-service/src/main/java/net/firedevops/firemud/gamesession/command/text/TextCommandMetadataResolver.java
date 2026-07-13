package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.settings.PlayerCommandCapability;

/** Resolves stable action-classification metadata for a command id or alias token. */
public interface TextCommandMetadataResolver {
  Optional<ResolvedTextCommandMetadata> resolve(String commandIdOrAlias);

  record ResolvedTextCommandMetadata(
      TextCommandDispatchGroup dispatchGroup,
      TextCommandPromptPolicy promptPolicy,
      TextCommandActionCategory actionCategory,
      List<TextCommandActionTag> actionTags,
      PlayerCommandCapability capability) {
    public ResolvedTextCommandMetadata {
      Objects.requireNonNull(dispatchGroup, "dispatchGroup must not be null");
      Objects.requireNonNull(promptPolicy, "promptPolicy must not be null");
      Objects.requireNonNull(actionCategory, "actionCategory must not be null");
      Objects.requireNonNull(actionTags, "actionTags must not be null");
      Objects.requireNonNull(capability, "capability must not be null");
      actionTags = List.copyOf(actionTags);
    }

    public ResolvedTextCommandMetadata(
        TextCommandDispatchGroup dispatchGroup,
        TextCommandPromptPolicy promptPolicy,
        TextCommandActionCategory actionCategory,
        List<TextCommandActionTag> actionTags) {
      this(
          dispatchGroup,
          promptPolicy,
          actionCategory,
          actionTags,
          PlayerCommandCapability.MANDATORY);
    }

    public ResolvedTextCommandMetadata(
        TextCommandDispatchGroup dispatchGroup,
        TextCommandActionCategory actionCategory,
        List<TextCommandActionTag> actionTags) {
      this(
          dispatchGroup,
          TextCommandPromptPolicy.NEVER,
          actionCategory,
          actionTags,
          PlayerCommandCapability.MANDATORY);
    }
  }
}
