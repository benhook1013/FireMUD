package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Locale;
import net.firedevops.firemud.gamesession.command.text.BuiltInTextCommandMetadataResolvers;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionTag;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves renderer-facing command semantics from the canonical command metadata contract. */
@Component
final class TextCommandPresentationPolicy {
  private static final TextCommandMetadataResolver FALLBACK_METADATA_RESOLVER =
      BuiltInTextCommandMetadataResolvers.builtInOnly();

  private final TextCommandMetadataResolver textCommandMetadataResolver;

  TextCommandPresentationPolicy(TextCommandMetadataResolver textCommandMetadataResolver) {
    this.textCommandMetadataResolver = textCommandMetadataResolver;
  }

  static TextCommandPresentationPolicy fallback() {
    return new TextCommandPresentationPolicy(FALLBACK_METADATA_RESOLVER);
  }

  boolean rendersInlineMessageOnlyResponse(TextCommand command, List<PlayerOutput> outputs) {
    return outputs.stream().allMatch(output -> output.kind() == PlayerOutputKind.MESSAGE)
        && isCommunicationAction(command);
  }

  String responseCommandLabel(TextCommand command, List<PlayerOutput> outputs) {
    if (isMovementAction(command) && outputs.stream().allMatch(this::isViewOutput)) {
      return TextCommandType.LOOK.name();
    }
    if (outputs.stream().allMatch(this::isViewOutput)
        && outputs.stream()
            .map(PlayerOutput::payload)
            .filter(LookViewOutput.class::isInstance)
            .map(LookViewOutput.class::cast)
            .anyMatch(view -> view.refreshReason() == LookViewOutput.RefreshReason.MOVE_REFRESH)) {
      return TextCommandType.LOOK.name();
    }
    return canonicalResponseLabel(command);
  }

  private boolean isCommunicationAction(TextCommand command) {
    return hasActionTag(command, TextCommandActionTag.COMMUNICATION);
  }

  private boolean isMovementAction(TextCommand command) {
    if (hasActionTag(command, TextCommandActionTag.MOVEMENT)) {
      return true;
    }
    return command.type() == TextCommandType.MOVE;
  }

  private boolean hasActionTag(TextCommand command, TextCommandActionTag actionTag) {
    return textCommandMetadataResolver
        .resolve(command.commandId())
        .or(() -> resolveAliasMetadata(command))
        .map(metadata -> metadata.actionTags().contains(actionTag))
        .orElse(false);
  }

  private java.util.Optional<TextCommandMetadataResolver.ResolvedTextCommandMetadata>
      resolveAliasMetadata(TextCommand command) {
    if (!StringUtils.hasText(command.aliasUsed())) {
      return java.util.Optional.empty();
    }
    return textCommandMetadataResolver.resolve(command.aliasUsed());
  }

  private String canonicalResponseLabel(TextCommand command) {
    if (StringUtils.hasText(command.commandId())) {
      return command.commandId().toUpperCase(Locale.ROOT);
    }
    return command.type().name();
  }

  private boolean isViewOutput(PlayerOutput output) {
    return output.kind() == PlayerOutputKind.VIEW;
  }
}
