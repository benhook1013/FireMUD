package net.firedevops.firemud.gamesession.websocket;

import java.io.IOException;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
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

  private final TextCommandInterpreter interpreter;
  private final LookCommandHandler lookHandler;
  private final SessionContextService sessionContextService;
  private final ScreenBufferService screenBufferService;
  private final TextCommandParser parser = new TextCommandParser();

  public GameSessionWebSocketHandler(
      TextCommandInterpreter interpreter,
      LookCommandHandler lookHandler,
      SessionContextService sessionContextService,
      ScreenBufferService screenBufferService) {
    this.interpreter = interpreter;
    this.lookHandler = lookHandler;
    this.sessionContextService = sessionContextService;
    this.screenBufferService = screenBufferService;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    logger.debug(
        "WebSocket session {} established with sessionId={} tenantId={}",
        session.getId(),
        resolveTransportSessionId(session),
        resolveTenantId(session));
    bootstrapSessionContext(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    String sessionId = resolveTransportSessionId(session);
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
    String response = formatResponse(command, interpretation);
    sendProtocolMessage(session, response);
    maybeAppendToScreenBuffer(sessionId, command, interpretation, response);
    maybeReplayScreenBufferAndRefreshLook(session, sessionId, command, interpretation);
  }

  private boolean parseSoloTick(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.SOLO_TICK_ATTR);
    String value = cached instanceof String text ? text : null;
    return value != null && value.equalsIgnoreCase("true");
  }

  private String resolveTransportSessionId(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String resolveBootstrapGameInstanceId(WebSocketSession session) {
    Object cached =
        session
            .getAttributes()
            .get(GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String resolveTenantId(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String formatResponse(
      TextCommand command, TextCommandInterpretationResult interpretation) {
    CommandEnqueueResult result = interpretation.commandResult();
    if (result.accepted()) {
      if (interpretation.hasResponse()) {
        if (interpretation.protocolResponse()) {
          return interpretation.responseText();
        }
        String base = "OK " + command.type().name();
        return base + "\n" + interpretation.responseText() + "\n\n";
      }
      return "OK " + command.type().name();
    }
    String message = result.errorMessage() == null ? "" : result.errorMessage();
    return "ERROR " + result.errorCode() + " " + message;
  }

  private void sendProtocolMessage(WebSocketSession session, String text) throws IOException {
    if (!StringUtils.hasText(text)) {
      return;
    }
    session.sendMessage(new TextMessage(text));
  }

  private void maybeAppendToScreenBuffer(
      String sessionId,
      TextCommand command,
      TextCommandInterpretationResult interpretation,
      String response) {
    if (!shouldBuffer(command, interpretation, response)) {
      return;
    }
    sessionContextService
        .findBySessionId(Long.parseLong(sessionId))
        .filter(context -> context.gameInstanceId() > 0 && context.characterId() > 0)
        .ifPresent(
            context ->
                screenBufferService.append(
                    context.tenantId(), context.gameInstanceId(), context.characterId(), response));
  }

  private void maybeReplayScreenBufferAndRefreshLook(
      WebSocketSession session,
      String sessionId,
      TextCommand command,
      TextCommandInterpretationResult interpretation)
      throws IOException {
    if (command.type() != TextCommandType.PLAY || !interpretation.commandResult().accepted()) {
      return;
    }
    sessionContextService
        .findBySessionId(Long.parseLong(sessionId))
        .filter(context -> context.gameInstanceId() > 0 && context.characterId() > 0)
        .ifPresent(
            context -> {
              Optional<ScreenBufferService.BufferedScreen> maybeBuffer =
                  screenBufferService.get(
                      context.tenantId(), context.gameInstanceId(), context.characterId());
              if (maybeBuffer.isEmpty()) {
                return;
              }
              sendReplayChunk(session, maybeBuffer.orElseThrow().protocolText(), "screen buffer");
              String look = lookHandler.describeProtocol(sessionId);
              if (StringUtils.hasText(look)) {
                sendReplayChunk(session, look, "fresh LOOK");
              }
            });
  }

  private void sendReplayChunk(WebSocketSession session, String text, String label) {
    try {
      sendProtocolMessage(session, text);
    } catch (IOException ex) {
      logger.warn("Failed to send reconnect {}", label, ex);
    }
  }

  private boolean shouldBuffer(
      TextCommand command, TextCommandInterpretationResult interpretation, String response) {
    if (!interpretation.commandResult().accepted() || !StringUtils.hasText(response)) {
      return false;
    }
    return command.type() == TextCommandType.LOOK
        || command.type() == TextCommandType.MOVE
        || command.type() == TextCommandType.SAY
        || command.type() == TextCommandType.WHISPER
        || command.type() == TextCommandType.TELL;
  }

  private void bootstrapSessionContext(WebSocketSession session) {
    String transportSessionId = resolveTransportSessionId(session);
    String tenantId = resolveTenantId(session);
    String bootstrapGameInstanceId = resolveBootstrapGameInstanceId(session);
    if (!StringUtils.hasText(transportSessionId)
        || !StringUtils.hasText(tenantId)
        || !StringUtils.hasText(bootstrapGameInstanceId)) {
      return;
    }
    try {
      long sessionId = Long.parseLong(transportSessionId);
      long tenant = Long.parseLong(tenantId);
      long bootstrapGameInstance = Long.parseLong(bootstrapGameInstanceId);
      if (sessionContextService.findByTenantAndSessionId(tenant, sessionId).isPresent()) {
        return;
      }
      sessionContextService.save(
          new SessionContext(
              sessionId, tenant, 0L, null, 0L, null, 0L, null, null, bootstrapGameInstance));
    } catch (NumberFormatException ex) {
      logger.debug(
          "Skipping bootstrap session context for transportSessionId={} tenantId={} bootstrapGameInstanceId={}",
          transportSessionId,
          tenantId,
          bootstrapGameInstanceId,
          ex);
    }
  }
}
