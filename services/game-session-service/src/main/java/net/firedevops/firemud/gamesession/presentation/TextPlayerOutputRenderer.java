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
      case ERROR -> renderError((ErrorOutput) output.payload());
      case NOTICE -> renderNotice((NoticeOutput) output.payload());
    };
  }

  public String renderAll(
      TextCommand command, CommandEnqueueResult result, java.util.List<PlayerOutput> outputs) {
    if (!result.accepted()) {
      String renderedError =
          outputs.stream()
              .filter(output -> output.kind() == PlayerOutputKind.ERROR)
              .reduce((first, second) -> second)
              .map(this::render)
              .orElse(null);
      if (StringUtils.hasText(renderedError)) {
        return renderedError;
      }
      String message = result.errorMessage() == null ? "" : result.errorMessage();
      return "ERROR " + result.errorCode() + " " + message;
    }
    String prompt = renderTrailingPrompt(outputs);
    java.util.List<PlayerOutput> nonPromptOutputs =
        outputs.stream().filter(output -> output.kind() != PlayerOutputKind.PROMPT).toList();
    if (nonPromptOutputs.isEmpty()) {
      if (StringUtils.hasText(prompt)) {
        return prompt;
      }
      return "OK " + command.type().name();
    }
    if (nonPromptOutputs.size() == 1 && nonPromptOutputs.get(0).protocolBlock()) {
      String rendered = render(nonPromptOutputs.get(0));
      return appendPrompt(rendered, prompt, false);
    }
    String body =
        nonPromptOutputs.stream()
            .map(this::render)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining("\n"));
    if (body.isBlank()) {
      if (StringUtils.hasText(prompt)) {
        return prompt;
      }
      return "OK " + command.type().name();
    }
    boolean plainMessageOnly =
        nonPromptOutputs.stream()
            .allMatch(
                output -> !output.protocolBlock() && output.kind() == PlayerOutputKind.MESSAGE);
    boolean proseCommand =
        command.type().name().equals("SAY")
            || command.type().name().equals("WHISPER")
            || command.type().name().equals("TELL");
    if (plainMessageOnly && proseCommand) {
      return appendPrompt(body, prompt, true);
    }
    return appendPrompt("OK " + command.type().name() + "\n" + body + "\n\n", prompt, false);
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

  private String renderError(ErrorOutput output) {
    return "ERROR "
        + output.code()
        + (StringUtils.hasText(output.message()) ? " " + output.message() : "");
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

  private String renderTrailingPrompt(java.util.List<PlayerOutput> outputs) {
    for (int i = outputs.size() - 1; i >= 0; i--) {
      PlayerOutput output = outputs.get(i);
      if (output.kind() == PlayerOutputKind.PROMPT) {
        return render(output);
      }
    }
    return "";
  }

  private String appendPrompt(String base, String prompt, boolean inline) {
    if (!StringUtils.hasText(prompt)) {
      return base;
    }
    if (!StringUtils.hasText(base)) {
      return prompt;
    }
    return inline ? base + "\n" + prompt : base + prompt;
  }
}
