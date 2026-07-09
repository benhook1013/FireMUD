package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves stable action-classification metadata for a command id or alias token. */
public interface TextCommandMetadataResolver {
  Optional<ResolvedTextCommandMetadata> resolve(String commandIdOrAlias);

  record ResolvedTextCommandMetadata(
      TextCommandDispatchGroup dispatchGroup,
      TextCommandActionCategory actionCategory,
      List<TextCommandActionTag> actionTags) {
    public ResolvedTextCommandMetadata {
      Objects.requireNonNull(dispatchGroup, "dispatchGroup must not be null");
      Objects.requireNonNull(actionCategory, "actionCategory must not be null");
      Objects.requireNonNull(actionTags, "actionTags must not be null");
      actionTags = List.copyOf(actionTags);
    }
  }
}
