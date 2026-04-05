package net.firedevops.firemud.gamesession.presentation;

import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Renders normalized player outputs for classic text-based clients. */
@Component
public class TextPlayerOutputRenderer {
  private final PresentationProperties presentationProperties;
  private final PresentationMessageCatalog presentationMessageCatalog;

  public TextPlayerOutputRenderer(PresentationProperties presentationProperties) {
    this(presentationProperties, new PresentationMessageCatalog());
  }

  @Autowired
  public TextPlayerOutputRenderer(
      PresentationProperties presentationProperties,
      PresentationMessageCatalog presentationMessageCatalog) {
    this.presentationProperties = presentationProperties;
    this.presentationMessageCatalog = presentationMessageCatalog;
  }

  public String render(PlayerOutput output) {
    return render(output, presentationProperties.defaultLocaleTag(), presentationProperties);
  }

  public String render(PlayerOutput output, String localeTag) {
    return render(output, localeTag, presentationProperties);
  }

  public String render(
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    if (effectivePresentationProperties.briefEnabledByDefault()
        && output.briefRenderPolicy() == BriefRenderPolicy.SUPPRESS_IN_BRIEF) {
      return "";
    }
    return switch (output.kind()) {
      case MESSAGE -> renderMessage((TextMessageOutput) output.payload(), localeTag);
      case VIEW -> {
        if (output.payload() instanceof LookViewOutput lookView) {
          yield renderLookView(lookView, localeTag, effectivePresentationProperties);
        }
        if (output.payload() instanceof InventoryViewOutput inventoryView) {
          yield renderInventoryView(inventoryView, localeTag, effectivePresentationProperties);
        }
        if (output.payload() instanceof WorldsViewOutput worldsView) {
          yield renderWorldsView(worldsView);
        }
        throw new IllegalArgumentException(
            "Unsupported view payload: " + output.payload().getClass().getName());
      }
      case PROMPT -> renderPrompt((PromptOutput) output.payload(), effectivePresentationProperties);
      case ERROR -> renderError((ErrorOutput) output.payload(), localeTag);
      case NOTICE ->
          renderNotice((NoticeOutput) output.payload(), localeTag, effectivePresentationProperties);
    };
  }

  public String renderAll(
      TextCommand command, CommandEnqueueResult result, java.util.List<PlayerOutput> outputs) {
    return renderAll(
        command,
        result,
        outputs,
        presentationProperties.defaultLocaleTag(),
        presentationProperties);
  }

  public String renderAll(
      TextCommand command,
      CommandEnqueueResult result,
      java.util.List<PlayerOutput> outputs,
      String localeTag) {
    return renderAll(command, result, outputs, localeTag, presentationProperties);
  }

  public String renderAll(
      TextCommand command,
      CommandEnqueueResult result,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    return renderAllForCommandType(
        command.type(), result, outputs, localeTag, effectivePresentationProperties);
  }

  public String renderAllForCommandType(
      TextCommandType commandType,
      CommandEnqueueResult result,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    TextCommand command = syntheticCommand(commandType);
    if (!result.accepted()) {
      String renderedError =
          outputs.stream()
              .filter(output -> output.kind() == PlayerOutputKind.ERROR)
              .reduce((first, second) -> second)
              .map(output -> render(output, localeTag, effectivePresentationProperties))
              .orElse(null);
      if (StringUtils.hasText(renderedError)) {
        return renderedError;
      }
      String message = result.errorMessage() == null ? "" : result.errorMessage();
      return "ERROR " + result.errorCode() + " " + message;
    }
    String prompt = renderTrailingPrompt(outputs, effectivePresentationProperties, localeTag);
    java.util.List<PlayerOutput> nonPromptOutputs =
        outputs.stream().filter(output -> output.kind() != PlayerOutputKind.PROMPT).toList();
    if (nonPromptOutputs.isEmpty()) {
      if (StringUtils.hasText(prompt)) {
        return prompt;
      }
      return "OK " + command.type().name();
    }
    if (nonPromptOutputs.size() == 1 && nonPromptOutputs.get(0).kind() == PlayerOutputKind.NOTICE) {
      String rendered =
          renderNoticeWithCommand(
              command, nonPromptOutputs.get(0), localeTag, effectivePresentationProperties);
      return appendPrompt(
          rendered, prompt, !rendered.contains("\n") && StringUtils.hasText(prompt));
    }
    String body =
        nonPromptOutputs.stream()
            .map(output -> render(output, localeTag, effectivePresentationProperties))
            .filter(StringUtils::hasText)
            .collect(Collectors.joining("\n"));
    if (body.isBlank()) {
      if (StringUtils.hasText(prompt)) {
        return prompt;
      }
      return "OK " + command.type().name();
    }
    String commandLabel = responseCommandLabel(command, nonPromptOutputs);
    boolean plainMessageOnly =
        nonPromptOutputs.stream().allMatch(output -> output.kind() == PlayerOutputKind.MESSAGE);
    boolean proseCommand =
        command.type().name().equals("SAY")
            || command.type().name().equals("WHISPER")
            || command.type().name().equals("TELL");
    if (plainMessageOnly && proseCommand) {
      return appendPrompt(body, prompt, true);
    }
    return appendPrompt("OK " + commandLabel + "\n" + body + "\n\n", prompt, false);
  }

  public String renderSuccessfulForCommandType(
      TextCommandType commandType,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    return renderAllForCommandType(
        commandType,
        CommandEnqueueResult.success(),
        outputs,
        localeTag,
        effectivePresentationProperties);
  }

  private String renderLookView(
      LookViewOutput result,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    boolean brief = shouldUseBriefRendering(result, effectivePresentationProperties);
    StringBuilder out = new StringBuilder();
    out.append(
            colorizeLabel(
                localizedLabel("label.room", "Room: ", localeTag), effectivePresentationProperties))
        .append(colorizeRoomName(result.roomName(), effectivePresentationProperties))
        .append(" (ID: ")
        .append(result.roomId())
        .append(")\n");
    out.append(
            colorizeLabel(
                localizedLabel("label.short", "Short: ", localeTag),
                effectivePresentationProperties))
        .append(result.shortDescription())
        .append("\n");
    if (!brief && result.includeLongDescription()) {
      out.append(
              colorizeLabel(
                  localizedLabel("label.long", "Long: ", localeTag),
                  effectivePresentationProperties))
          .append(result.longDescription())
          .append("\n");
    }
    out.append(
        colorizeLabel(
            localizedLabel("label.exits", "Exits: ", localeTag), effectivePresentationProperties));
    out.append(
        result.exits().stream()
            .map(exit -> exit.label() + " (" + exit.description() + ")")
            .collect(Collectors.joining(", ")));
    out.append("\n")
        .append(
            colorizeLabel(
                localizedLabel("label.entities", "Entities:", localeTag),
                effectivePresentationProperties))
        .append("\n");
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

  private String renderPrompt(
      PromptOutput output, PresentationProperties effectivePresentationProperties) {
    if (!effectivePresentationProperties.prompt().enabled()
        || !StringUtils.hasText(output.text())) {
      return "";
    }
    return colorizePrompt(output.text(), effectivePresentationProperties);
  }

  private String renderWorldsView(WorldsViewOutput output) {
    return output.worlds().stream()
        .map(world -> world.ordinal() + ") " + world.displayName() + " (" + world.slug() + ")")
        .collect(Collectors.joining("\n"));
  }

  private String renderInventoryView(
      InventoryViewOutput output,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    StringBuilder out = new StringBuilder();
    out.append(
            colorizeLabel(
                localizedLabel("label.inventory", output.title(), localeTag),
                effectivePresentationProperties))
        .append("\n");
    if (output.lines().isEmpty()) {
      out.append("(empty)");
    } else {
      for (String line : output.lines()) {
        out.append(line).append("\n");
      }
      if (out.charAt(out.length() - 1) == '\n') {
        out.setLength(out.length() - 1);
      }
    }
    return out.toString();
  }

  private String renderMessage(TextMessageOutput output, String localeTag) {
    return presentationMessageCatalog.render(
        output.text(), output.messageKey(), output.arguments(), localeTag);
  }

  private String renderNotice(
      NoticeOutput output,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    return colorizeNotice(
        presentationMessageCatalog.render(
            output.text(), output.messageKey(), output.arguments(), localeTag),
        effectivePresentationProperties);
  }

  private String renderNoticeWithCommand(
      TextCommand command,
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    String body = render(output, localeTag, effectivePresentationProperties);
    String commandLabel = responseCommandLabel(command, java.util.List.of(output));
    if (body.contains("\n")) {
      return "OK " + commandLabel + "\n" + body + "\n\n";
    }
    return "OK " + commandLabel + " " + body;
  }

  private String renderError(ErrorOutput output, String localeTag) {
    return "ERROR "
        + output.code()
        + (StringUtils.hasText(output.message())
            ? " "
                + presentationMessageCatalog.render(
                    output.message(), output.messageKey(), output.arguments(), localeTag)
            : "");
  }

  private String localizedLabel(String messageKey, String fallbackText, String localeTag) {
    return presentationMessageCatalog.render(
        fallbackText, messageKey, java.util.Map.of(), localeTag);
  }

  private String colorizeLabel(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;36");
      case RICH -> ansi(text, "38;5;81");
    };
  }

  private String colorizeRoomName(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;33");
      case RICH -> ansi(text, "38;5;229");
    };
  }

  private String colorizePrompt(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
      case NONE -> text;
      case BASIC -> ansi(text, "1;32");
      case RICH -> ansi(text, "38;5;119");
    };
  }

  private String colorizeNotice(
      String text, PresentationProperties effectivePresentationProperties) {
    return switch (effectivePresentationProperties.defaultColorMode()) {
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

  private String renderTrailingPrompt(
      java.util.List<PlayerOutput> outputs,
      PresentationProperties effectivePresentationProperties,
      String localeTag) {
    for (int i = outputs.size() - 1; i >= 0; i--) {
      PlayerOutput output = outputs.get(i);
      if (output.kind() == PlayerOutputKind.PROMPT) {
        return render(output, localeTag, effectivePresentationProperties);
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

  private String responseCommandLabel(TextCommand command, java.util.List<PlayerOutput> outputs) {
    if (command.type() == net.firedevops.firemud.gamesession.command.text.TextCommandType.MOVE
        && outputs.stream().allMatch(output -> output.kind() == PlayerOutputKind.VIEW)) {
      return net.firedevops.firemud.gamesession.command.text.TextCommandType.LOOK.name();
    }
    if (outputs.stream().allMatch(output -> output.kind() == PlayerOutputKind.VIEW)
        && outputs.stream()
            .map(PlayerOutput::payload)
            .filter(LookViewOutput.class::isInstance)
            .map(LookViewOutput.class::cast)
            .anyMatch(view -> view.refreshReason() == LookViewOutput.RefreshReason.MOVE_REFRESH)) {
      return net.firedevops.firemud.gamesession.command.text.TextCommandType.LOOK.name();
    }
    return command.type().name();
  }

  private boolean shouldUseBriefRendering(
      LookViewOutput result, PresentationProperties effectivePresentationProperties) {
    if (!result.includeLongDescription()) {
      return true;
    }
    if (effectivePresentationProperties.briefEnabledByDefault()) {
      return true;
    }
    return result.briefRenderingHint() == LookViewOutput.BriefRenderingHint.PREFER_BRIEF;
  }

  private TextCommand syntheticCommand(TextCommandType commandType) {
    return new TextCommand(commandType, java.util.List.of(), commandType.name());
  }
}
