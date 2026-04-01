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
    return switch (output.kind()) {
      case MESSAGE -> ((TextMessageOutput) output.payload()).text();
      case VIEW ->
          output.payload() instanceof LookViewOutput lookView
              ? renderLookView(lookView)
              : ((TextMessageOutput) output.payload()).text();
      case PROMPT -> renderPrompt((PromptOutput) output.payload());
      case ERROR -> throw new IllegalArgumentException("Error outputs are rendered elsewhere");
      case NOTICE -> ((NoticeOutput) output.payload()).text();
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
            .allMatch(output -> !output.protocolBlock() && output.kind() == PlayerOutputKind.MESSAGE);
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
    out.append("Room: ")
        .append(result.roomName())
        .append(" (ID: ")
        .append(result.roomId())
        .append(")\n");
    out.append("Short: ").append(result.shortDescription()).append("\n");
    if (!brief) {
      out.append("Long: ").append(result.longDescription()).append("\n");
    }
    out.append("Exits: ");
    out.append(
        result.exits().stream()
            .map(exit -> exit.label() + " (" + exit.description() + ")")
            .collect(Collectors.joining(", ")));
    out.append("\nEntities:\n");
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
    return output.text();
  }
}
