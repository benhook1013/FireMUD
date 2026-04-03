package net.firedevops.firemud.gamesession.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.NoticeOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputPayload;
import net.firedevops.firemud.gamesession.presentation.PromptOutput;
import net.firedevops.firemud.gamesession.presentation.TextMessageOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/** Projects structured player outputs to either classic text or first-party structured messages. */
@Component
public final class WebSocketOutputProjector {
  private final TextPlayerOutputRenderer textRenderer;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
      new com.fasterxml.jackson.databind.ObjectMapper();

  public WebSocketOutputProjector(TextPlayerOutputRenderer textRenderer) {
    this.textRenderer = textRenderer;
  }

  public String projectCommandResponse(
      WebSocketSession session,
      TextCommand command,
      TextCommandInterpretationResult interpretation,
      List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation) {
    if (!isFirstPartyWeb(session)) {
      return textRenderer.renderAll(
          command, interpretation.commandResult(), outputs, localeTag, effectivePresentation);
    }
    return toJson(
        new FirstPartyEnvelope(
            "command_result",
            command.type().name(),
            interpretation.commandResult().accepted(),
            interpretation.commandResult().errorCode(),
            interpretation.commandResult().errorMessage(),
            interpretation.reconnectRedrawRecommended(),
            outputs.stream().map(this::toEnvelope).toList()));
  }

  public String projectPlayerOutput(
      WebSocketSession session,
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentation) {
    if (!isFirstPartyWeb(session)) {
      return textRenderer.render(output, localeTag, effectivePresentation);
    }
    return toJson(
        new FirstPartyEnvelope(
            "player_output", null, null, null, null, null, List.of(toEnvelope(output))));
  }

  boolean isFirstPartyWeb(WebSocketSession session) {
    Object mode =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR);
    return "first_party_web".equals(mode);
  }

  private FirstPartyPlayerOutputEnvelope toEnvelope(PlayerOutput output) {
    return new FirstPartyPlayerOutputEnvelope(
        output.kind().name(),
        output.replayPolicy().name(),
        output.briefRenderPolicy().name(),
        payloadType(output.payload()),
        output.payload());
  }

  private String payloadType(PlayerOutputPayload payload) {
    return switch (payload) {
      case TextMessageOutput ignored -> "text_message";
      case PromptOutput ignored -> "prompt";
      case NoticeOutput ignored -> "notice";
      case ErrorOutput ignored -> "error";
      case LookViewOutput ignored -> "look_view";
      case WorldsViewOutput ignored -> "worlds_view";
      default -> "unknown";
    };
  }

  private String toJson(FirstPartyEnvelope envelope) {
    try {
      return objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize first-party output envelope", ex);
    }
  }

  private record FirstPartyEnvelope(
      String eventType,
      String commandType,
      Boolean accepted,
      String errorCode,
      String errorMessage,
      Boolean reconnectRedrawRecommended,
      List<FirstPartyPlayerOutputEnvelope> outputs) {}

  private record FirstPartyPlayerOutputEnvelope(
      String kind,
      String replayPolicy,
      String briefRenderPolicy,
      String payloadType,
      Object payload) {}
}
