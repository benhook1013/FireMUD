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
    if (command.type() == TextCommandType.MOVE && outputs.stream().allMatch(this::isViewOutput)) {
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
    return textCommandMetadataResolver
        .resolve(command.commandId())
        .map(metadata -> metadata.actionTags().contains(TextCommandActionTag.COMMUNICATION))
        .orElse(false);
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
