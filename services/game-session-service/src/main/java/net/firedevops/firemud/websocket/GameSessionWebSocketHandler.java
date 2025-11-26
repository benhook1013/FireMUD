package net.firedevops.firemud.websocket;

import net.firedevops.firemud.command.text.TextCommand;
import net.firedevops.firemud.command.text.TextCommandParser;
import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Handles incoming WebSocket text lines and routes them into the shared command queue. */
@Component
public class GameSessionWebSocketHandler implements WebSocketHandler {
  private static final String SESSION_HEADER = "X-Session-Id";
  private static final String SOLO_TICK_HEADER = "X-Requires-Solo-Tick";

  private final TextCommandInterpreter interpreter;
  private final TextCommandParser parser = new TextCommandParser();

  public GameSessionWebSocketHandler(TextCommandInterpreter interpreter) {
    this.interpreter = interpreter;
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    String sessionId = resolveSessionId(session);
    if (!StringUtils.hasText(sessionId)) {
      return session
          .send(Mono.just(session.textMessage("ERROR INVALID_ARGUMENT sessionId header required")))
          .then(session.close(CloseStatus.BAD_DATA));
    }
    boolean requiresSoloTick = parseSoloTick(session);
    Flux<WebSocketMessage> responses =
        session
            .receive()
            .map(WebSocketMessage::getPayloadAsText)
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(parser::parse)
            .map(command -> respond(session, sessionId, requiresSoloTick, command));
    return session.send(responses);
  }

  private WebSocketMessage respond(
      WebSocketSession session,
      String sessionId,
      boolean requiresSoloTick,
      TextCommand command) {
    CommandEnqueueResult result =
        interpreter.interpret(sessionId, command, requiresSoloTick);
    if (result.accepted()) {
      return session.textMessage("OK " + command.type().name());
    }
    String message = result.errorMessage() == null ? "" : result.errorMessage();
    return session.textMessage("ERROR " + result.errorCode() + " " + message);
  }

  private boolean parseSoloTick(WebSocketSession session) {
    String value = session.getHandshakeInfo().getHeaders().getFirst(SOLO_TICK_HEADER);
    return value != null && value.equalsIgnoreCase("true");
  }

  private String resolveSessionId(WebSocketSession session) {
    return session.getHandshakeInfo().getHeaders().getFirst(SESSION_HEADER);
  }
}
