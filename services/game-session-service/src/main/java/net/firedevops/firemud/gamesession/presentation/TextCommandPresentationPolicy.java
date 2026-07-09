package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionCategory;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionTag;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import org.springframework.stereotype.Component;

/** Resolves renderer-facing command semantics from the canonical command metadata contract. */
@Component
final class TextCommandPresentationPolicy {
  private static final TextCommandMetadataResolver FALLBACK_METADATA_RESOLVER =
      commandId -> {
        if (commandId == null || commandId.isBlank()) {
          return Optional.empty();
        }
        String normalized = commandId.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("say") || normalized.equals("whisper") || normalized.equals("tell")) {
          return Optional.of(
              new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                  TextCommandActionCategory.SOCIAL, List.of(TextCommandActionTag.COMMUNICATION)));
        }
        return Optional.empty();
      };

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
    return command.type().name();
  }

  private boolean isCommunicationAction(TextCommand command) {
    return textCommandMetadataResolver
        .resolve(command.commandId())
        .map(metadata -> metadata.actionTags().contains(TextCommandActionTag.COMMUNICATION))
        .orElseGet(
            () ->
                switch (command.type()) {
                  case SAY, WHISPER, TELL -> true;
                  default -> false;
                });
  }

  private boolean isViewOutput(PlayerOutput output) {
    return output.kind() == PlayerOutputKind.VIEW;
  }
}
