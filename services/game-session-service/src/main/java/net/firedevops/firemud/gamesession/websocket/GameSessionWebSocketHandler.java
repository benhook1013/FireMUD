package net.firedevops.firemud.gamesession.websocket;

import java.io.IOException;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final ActiveTransportSessionRegistry activeTransportSessionRegistry;
  private final FirstPartyConnectContextService firstPartyConnectContextService;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final ScreenBufferService screenBufferService;
  private final TextPlayerOutputRenderer outputRenderer;
  private final PromptComposer promptComposer;
  private final net.firedevops.firemud.gamesession.config.PresentationProperties
      presentationProperties;
  private final RuntimeIdentity runtimeIdentity;
  private final TextCommandParser parser = new TextCommandParser();

  @Autowired
  public GameSessionWebSocketHandler(
      TextCommandInterpreter interpreter,
      LookCommandHandler lookHandler,
      SessionContextService sessionContextService,
      ActiveTransportSessionRegistry activeTransportSessionRegistry,
      FirstPartyConnectContextService firstPartyConnectContextService,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      ScreenBufferService screenBufferService,
      TextPlayerOutputRenderer outputRenderer,
      PromptComposer promptComposer,
      net.firedevops.firemud.gamesession.config.PresentationProperties presentationProperties,
      RuntimeIdentity runtimeIdentity) {
    this.interpreter = interpreter;
    this.lookHandler = lookHandler;
    this.sessionContextService = sessionContextService;
    this.activeTransportSessionRegistry = activeTransportSessionRegistry;
    this.firstPartyConnectContextService = firstPartyConnectContextService;
    this.firstPartyConnectContextRegistry = firstPartyConnectContextRegistry;
    this.screenBufferService = screenBufferService;
    this.outputRenderer = outputRenderer;
    this.promptComposer = promptComposer;
    this.presentationProperties = presentationProperties;
    this.runtimeIdentity = runtimeIdentity;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
      logger.debug(
          "WebSocket session {} established with sessionId={} tenantId={}",
          session.getId(),
          resolveTransportSessionId(session),
          resolveTenantId(session));
      bootstrapSessionContext(session);
      parseNumericSessionId(resolveTransportSessionId(session))
          .ifPresent(sessionId -> activeTransportSessionRegistry.register(sessionId, session));
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    parseNumericSessionId(resolveTransportSessionId(session))
        .ifPresent(
            sessionId -> {
              activeTransportSessionRegistry.unregister(sessionId, session);
              firstPartyConnectContextRegistry.unregister(sessionId);
            });
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
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

  private String resolveConnectionMode(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String resolveConnectContext(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String formatResponse(
      TextCommand command, TextCommandInterpretationResult interpretation) {
    return outputRenderer.renderAll(
        command, interpretation.commandResult(), interpretation.outputs());
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
              if (!interpretation.reconnectRedrawRecommended() && maybeBuffer.isEmpty()) {
                return;
              }
              maybeBuffer.ifPresent(
                  buffer -> sendReplayChunk(session, buffer.protocolText(), "screen buffer"));
              String look = lookHandler.describeProtocol(sessionId);
              if (StringUtils.hasText(look)) {
                sendReplayChunk(session, look, "fresh LOOK");
              }
              if (presentationProperties.prompt().emitAfterReconnectRestore()) {
                renderPrompt(context)
                    .ifPresent(prompt -> sendReplayChunk(session, prompt, "fresh prompt"));
              }
            });
  }

  private void sendReplayChunk(WebSocketSession session, String text, String label) {
    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
      try {
        sendProtocolMessage(session, text);
      } catch (IOException ex) {
        logger.warn("Failed to send reconnect {}", label, ex);
      }
    }
  }

  private boolean shouldBuffer(
      TextCommand command, TextCommandInterpretationResult interpretation, String response) {
    if (!interpretation.commandResult().accepted() || !StringUtils.hasText(response)) {
      return false;
    }
    return interpretation.outputs().stream().anyMatch(PlayerOutput::screenBufferEligible);
  }

  private void bootstrapSessionContext(WebSocketSession session) {
    String transportSessionId = resolveTransportSessionId(session);
    if (!StringUtils.hasText(transportSessionId)) {
      return;
    }
    try {
      long sessionId = Long.parseLong(transportSessionId);
      if ("first_party_web".equals(resolveConnectionMode(session))) {
        bootstrapFirstPartySessionContext(session, sessionId);
        return;
      }
      bootstrapGenericSessionContext(session, sessionId);
    } catch (NumberFormatException ex) {
      logger.debug(
          "Skipping bootstrap session context for transportSessionId={}", transportSessionId, ex);
    }
  }

  private RuntimeLoggingContext openLoggingContext(WebSocketSession session) {
    String correlationId = resolveTransportSessionId(session);
    if (!StringUtils.hasText(correlationId)) {
      correlationId = session.getId();
    }
    return RuntimeLoggingContext.open(runtimeIdentity, correlationId);
  }

  private Optional<String> renderPrompt(SessionContext context) {
    return promptComposer.compose(context).map(outputRenderer::render).filter(StringUtils::hasText);
  }

  private void bootstrapGenericSessionContext(WebSocketSession session, long sessionId) {
    String tenantId = resolveTenantId(session);
    String bootstrapGameInstanceId = resolveBootstrapGameInstanceId(session);
    if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(bootstrapGameInstanceId)) {
      return;
    }
    try {
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
          "Skipping generic bootstrap session context for transportSessionId={} tenantId={} bootstrapGameInstanceId={}",
          sessionId,
          tenantId,
          bootstrapGameInstanceId,
          ex);
    }
  }

  private void bootstrapFirstPartySessionContext(WebSocketSession session, long sessionId) {
    Optional<net.firedevops.firemud.gamesession.service.FirstPartyConnectContext> maybeContext =
        firstPartyConnectContextService.parse(resolveConnectContext(session));
    if (maybeContext.isEmpty()) {
      closeInvalidFirstPartyContext(session);
      return;
    }
    var connectContext = maybeContext.orElseThrow();
    firstPartyConnectContextRegistry.register(sessionId, connectContext);
    if (sessionContextService
        .findByTenantAndSessionId(connectContext.tenantId(), sessionId)
        .isPresent()) {
      return;
    }
    sessionContextService.save(
        new SessionContext(
            sessionId,
            connectContext.tenantId(),
            0L,
            null,
            0L,
            null,
            0L,
            null,
            null,
            connectContext.gameInstanceId()));
  }

  private void closeInvalidFirstPartyContext(WebSocketSession session) {
    try {
      session.close(
          new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "CONNECT_CONTEXT_INVALID"));
    } catch (IOException ex) {
      logger.warn("Failed to close session with invalid first-party connect context", ex);
    }
  }

  private Optional<Long> parseNumericSessionId(String text) {
    if (!StringUtils.hasText(text)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(text));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }
}
