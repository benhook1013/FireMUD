package net.firedevops.firemud.gamesession.websocket;

import java.io.IOException;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
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
  private static final String GAME_INSTANCE_HEADER = "X-Game-Instance-Id";
  private static final String LEGACY_SESSION_HEADER = "X-Session-Id";
  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String SOLO_TICK_HEADER = "X-Requires-Solo-Tick";

  private final TextCommandInterpreter interpreter;
  private final LookCommandHandler lookHandler;
  private final TextCommandParser parser = new TextCommandParser();

  public GameSessionWebSocketHandler(
      TextCommandInterpreter interpreter, LookCommandHandler lookHandler) {
    this.interpreter = interpreter;
    this.lookHandler = lookHandler;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    logger.debug(
        "WebSocket session {} established with headers {}",
        session.getId(),
        session.getHandshakeHeaders());
    String sessionId = resolveSessionId(session);
    String tenantId = resolveTenantId(session);
    if (StringUtils.hasText(sessionId) && StringUtils.hasText(tenantId)) {
      lookHandler.cachedLook(tenantId, sessionId).ifPresent(text -> sendCachedLook(session, text));
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    String sessionId = resolveSessionId(session);
    if (!StringUtils.hasText(sessionId)) {
      session.sendMessage(new TextMessage("ERROR INVALID_ARGUMENT sessionId header required"));
      session.close(CloseStatus.BAD_DATA);
      return;
    }
    boolean requiresSoloTick = parseSoloTick(session);
    TextCommand command = parser.parse(message.getPayload());
    if (command.type() == TextCommandType.NOOP) {
      return;
    }
    TextCommandInterpretationResult interpretation =
        interpreter.interpret(sessionId, command, requiresSoloTick);
    session.sendMessage(new TextMessage(formatResponse(command, interpretation)));
  }

  private boolean parseSoloTick(WebSocketSession session) {
    String value = session.getHandshakeHeaders().getFirst(SOLO_TICK_HEADER);
    return value != null && value.equalsIgnoreCase("true");
  }

  private String resolveSessionId(WebSocketSession session) {
    String gameInstanceId = session.getHandshakeHeaders().getFirst(GAME_INSTANCE_HEADER);
    if (StringUtils.hasText(gameInstanceId)) {
      return gameInstanceId;
    }
    return session.getHandshakeHeaders().getFirst(LEGACY_SESSION_HEADER);
  }

  private String resolveTenantId(WebSocketSession session) {
    return session.getHandshakeHeaders().getFirst(TENANT_HEADER);
  }

  private String formatResponse(
      TextCommand command, TextCommandInterpretationResult interpretation) {
    CommandEnqueueResult result = interpretation.commandResult();
    if (result.accepted()) {
      String base = "OK " + command.type().name();
      if (interpretation.hasResponse()) {
        return base + "\n" + interpretation.responseText() + "\n\n";
      }
      return base;
    }
    String message = result.errorMessage() == null ? "" : result.errorMessage();
    return "ERROR " + result.errorCode() + " " + message;
  }

  private void sendCachedLook(WebSocketSession session, String text) {
    try {
      session.sendMessage(new TextMessage(text));
    } catch (IOException ex) {
      logger.warn("Failed to send cached LOOK text", ex);
    }
  }
}
