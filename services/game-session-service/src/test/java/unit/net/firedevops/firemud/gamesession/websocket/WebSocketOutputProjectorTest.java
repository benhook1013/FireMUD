package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class WebSocketOutputProjectorTest {

  private final PresentationProperties presentation = new PresentationProperties();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final WebSocketOutputProjector projector =
      new WebSocketOutputProjector(new TextPlayerOutputRenderer(presentation));

  @Test
  void genericWebSocketStillReceivesClassicText() {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes()).thenReturn(Map.of());

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(),
                List.of(PlayerOutput.notice("Entered world: demo"))),
            List.of(PlayerOutput.notice("Entered world: demo")),
            "en-NZ",
            presentation);

    assertThat(payload).startsWith("OK PLAY");
  }

  @Test
  void firstPartyWebReceivesStructuredCommandEnvelope() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectCommandResponse(
            session,
            new TextCommand(TextCommandType.LOOK, List.of(), "LOOK"),
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(PlayerOutput.prompt("demo> "))),
            List.of(PlayerOutput.prompt("demo> ")),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("command_result");
    assertThat(json.path("commandType").asText()).isEqualTo("LOOK");
    assertThat(json.path("accepted").asBoolean()).isTrue();
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("kind").asText()).isEqualTo("PROMPT");
    assertThat(json.path("outputs").get(0).path("payloadType").asText()).isEqualTo("prompt");
    assertThat(json.path("outputs").get(0).path("payload").path("text").asText())
        .isEqualTo("demo> ");
  }

  @Test
  void firstPartyWebReceivesStructuredAsyncOutputEnvelope() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));

    String payload =
        projector.projectPlayerOutput(
            session,
            PlayerOutput.message(
                "You whisper to Sora, \"Keep quiet\"",
                "communication.whisper.actor",
                Map.of("targetName", "Sora", "message", "Keep quiet")),
            "en-NZ",
            presentation);

    JsonNode json = objectMapper.readTree(payload);
    assertThat(json.path("eventType").asText()).isEqualTo("player_output");
    assertThat(json.path("outputs")).hasSize(1);
    assertThat(json.path("outputs").get(0).path("kind").asText()).isEqualTo("MESSAGE");
    assertThat(json.path("outputs").get(0).path("payloadType").asText()).isEqualTo("text_message");
    assertThat(json.path("outputs").get(0).path("payload").path("messageKey").asText())
        .isEqualTo("communication.whisper.actor");
  }
}
