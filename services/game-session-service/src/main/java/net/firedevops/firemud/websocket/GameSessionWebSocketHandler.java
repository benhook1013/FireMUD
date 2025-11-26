package net.firedevops.firemud.websocket;

import java.io.IOException;
import net.firedevops.firemud.command.text.TextCommand;
import net.firedevops.firemud.command.text.TextCommandParser;
import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Handles WebSocket text commands from clients that span both Telnet and browser connections. */
@Component
public class GameSessionWebSocketHandler extends TextWebSocketHandler {
  private static final Logger logger = LoggerFactory.getLogger(GameSessionWebSocketHandler.class);
  private static final String SESSION_HEADER = "X-Session-Id";
  private static final String SOLO_TICK_HEADER = "X-Requires-Solo-Tick";

  private final TextCommandInterpreter interpreter;
  private final TextCommandParser parser = new TextCommandParser();

  public GameSessionWebSocketHandler(TextCommandInterpreter interpreter) {
    this.interpreter = interpreter;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    logger.debug("WebSocket session {} established with headers {}", session.getId(), session.getHandshakeHeaders());
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
    String sessionId = resolveSessionId(session);
    if (!StringUtils.hasText(sessionId)) {
      session.sendMessage(new TextMessage("ERROR INVALID_ARGUMENT sessionId header required"));
      session.close(CloseStatus.BAD_DATA);
      return;
    }
    boolean requiresSoloTick = parseSoloTick(session);
    TextCommand command = parser.parse(message.getPayload());
    CommandEnqueueResult result = interpreter.interpret(sessionId, command, requiresSoloTick);
    session.sendMessage(new TextMessage(formatResponse(command, result)));
  }

  private boolean parseSoloTick(WebSocketSession session) {
    String value = session.getHandshakeHeaders().getFirst(SOLO_TICK_HEADER);
    return value != null && value.equalsIgnoreCase("true");
  }

  private String resolveSessionId(WebSocketSession session) {
    return session.getHandshakeHeaders().getFirst(SESSION_HEADER);
  }

  private String formatResponse(TextCommand command, CommandEnqueueResult result) {
    if (result.accepted()) {
      return "OK " + command.type().name();
    }
    String message = result.errorMessage() == null ? "" : result.errorMessage();
    return "ERROR " + result.errorCode() + " " + message;
  }
}
