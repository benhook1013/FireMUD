package net.firedevops.firemud.gamesession.presentation;

import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Renders normalized player outputs for classic text-based clients. */
@Component
public class TextPlayerOutputRenderer {
  private final PresentationProperties presentationProperties;

  public TextPlayerOutputRenderer(PresentationProperties presentationProperties) {
    this.presentationProperties = presentationProperties;
  }

  public String render(PlayerOutput output) {
    if (output.protocolBlock()) {
      return output.text();
    }
    if (presentationProperties.briefEnabledByDefault()
        && output.briefRenderPolicy() == BriefRenderPolicy.SUPPRESS_IN_BRIEF) {
      return "";
    }
    return switch (output.kind()) {
      case MESSAGE -> renderMessage((TextMessageOutput) output.payload());
      case VIEW ->
          output.payload() instanceof LookViewOutput lookView
              ? renderLookView(lookView)
              : renderMessage((TextMessageOutput) output.payload());
      case PROMPT -> renderPrompt((PromptOutput) output.payload());
      case ERROR -> throw new IllegalArgumentException("Error outputs are rendered elsewhere");
      case NOTICE -> renderNotice((NoticeOutput) output.payload());
    };
  }

  public String renderAll(
      TextCommand command, CommandEnqueueResult result, java.util.List<PlayerOutput> outputs) {
    if (!result.accepted()) {
      String message = result.errorMessage() == null ? "" : result.errorMessage();
      return "ERROR " + result.errorCode() + " " + message;
    }
    if (outputs.isEmpty()) {
      return "OK " + command.type().name();
    }
    if (outputs.size() == 1 && outputs.get(0).protocolBlock()) {
      return render(outputs.get(0));
    }
    String body = outputs.stream().map(this::render).collect(Collectors.joining("\n"));
    if (body.isBlank()) {
      return "OK " + command.type().name();
    }
    boolean plainMessageOnly =
        outputs.stream()
            .allMatch(
                output -> !output.protocolBlock() && output.kind() == PlayerOutputKind.MESSAGE);
    boolean proseCommand =
        command.type().name().equals("SAY")
            || command.type().name().equals("WHISPER")
            || command.type().name().equals("TELL");
    if (plainMessageOnly && proseCommand) {
      return body;
    }
    return "OK " + command.type().name() + "\n" + body + "\n\n";
  }

  private String renderLookView(LookViewOutput result) {
    boolean brief = presentationProperties.briefEnabledByDefault();
    StringBuilder out = new StringBuilder();
    out.append(colorizeLabel("Room: "))
        .append(colorizeRoomName(result.roomName()))
        .append(" (ID: ")
        .append(result.roomId())
        .append(")\n");
    out.append(colorizeLabel("Short: ")).append(result.shortDescription()).append("\n");
    if (!brief) {
      out.append(colorizeLabel("Long: ")).append(result.longDescription()).append("\n");
    }
    out.append(colorizeLabel("Exits: "));
    out.append(
        result.exits().stream()
            .map(exit -> exit.label() + " (" + exit.description() + ")")
            .collect(Collectors.joining(", ")));
    out.append("\n").append(colorizeLabel("Entities:")).append("\n");
    for (LookViewEntity entity : result.entities()) {
      out.append("- ")
          .append(entity.entityType())
          .append(" \"")
          .append(entity.displayName())
          .append("\"")
          .append(entity.role().isEmpty() ? "" : " (" + entity.role() + ")");
      if (!entity.stateFlags().isEmpty()) {
        out.append(" [").append(String.join(",", entity.stateFlags())).append("]");
      }
      out.append("\n");
    }
    return out.toString().trim();
  }

  private String renderPrompt(PromptOutput output) {
    if (!presentationProperties.prompt().enabled() || !StringUtils.hasText(output.text())) {
      return "";
    }
    return colorizePrompt(output.text());
  }

  private String renderMessage(TextMessageOutput output) {
    return output.text();
  }

  private String renderNotice(NoticeOutput output) {
    return colorizeNotice(output.text());
  }

  private String colorizeLabel(String text) {
    return switch (presentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;36");
      case RICH -> ansi(text, "38;5;81");
    };
  }

  private String colorizeRoomName(String text) {
    return switch (presentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;33");
      case RICH -> ansi(text, "38;5;229");
    };
  }

  private String colorizePrompt(String text) {
    return switch (presentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;32");
      case RICH -> ansi(text, "38;5;119");
    };
  }

  private String colorizeNotice(String text) {
    return switch (presentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;33");
      case RICH -> ansi(text, "38;5;221");
    };
  }

  private String ansi(String text, String code) {
    if (!StringUtils.hasText(text)) {
      return text;
    }
    return "\u001B[" + code + "m" + text + "\u001B[0m";
  }
}
