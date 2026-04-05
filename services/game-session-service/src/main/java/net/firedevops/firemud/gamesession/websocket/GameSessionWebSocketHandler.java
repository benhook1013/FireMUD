package net.firedevops.firemud.gamesession.websocket;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.presentation.PromptBurstCoordinator;
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
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are framework-managed and retained internally")
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
  private final WebSocketOutputProjector outputProjector;
  private final PromptBurstCoordinator promptBurstCoordinator;
  private final PromptComposer promptComposer;
  private final EffectiveSettingsResolver settingsResolver;
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
      WebSocketOutputProjector outputProjector,
      PromptBurstCoordinator promptBurstCoordinator,
      PromptComposer promptComposer,
      EffectiveSettingsResolver settingsResolver,
      RuntimeIdentity runtimeIdentity) {
    this.interpreter = interpreter;
    this.lookHandler = lookHandler;
    this.sessionContextService = sessionContextService;
    this.activeTransportSessionRegistry = activeTransportSessionRegistry;
    this.firstPartyConnectContextService = firstPartyConnectContextService;
    this.firstPartyConnectContextRegistry = firstPartyConnectContextRegistry;
    this.screenBufferService = screenBufferService;
    this.outputRenderer = outputRenderer;
    this.outputProjector = outputProjector;
    this.promptBurstCoordinator = promptBurstCoordinator;
    this.promptComposer = promptComposer;
    this.settingsResolver = settingsResolver;
    this.runtimeIdentity = runtimeIdentity;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    try (CombinedLoggingContext ignored = openLoggingContext(session)) {
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
              promptBurstCoordinator.evict(Long.toString(sessionId));
            });
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    try (CombinedLoggingContext ignored = openLoggingContext(session)) {
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
      Optional<SessionContext> maybeContext =
          parseNumericSessionId(sessionId).flatMap(sessionContextService::findBySessionId);
      PresentationProperties effectivePresentation =
          settingsResolver.presentation(maybeContext.orElse(null));
      java.util.List<PlayerOutput> outputs =
          promptBurstCoordinator.applyPromptWindow(
              sessionId,
              maybeContext.orElse(null),
              interpretation.outputs(),
              shouldForcePromptEmission(command, interpretation));
      maybePersistLocaleTag(sessionId, resolveLocaleTag(session));
      String response =
          formatResponse(command, interpretation, session, outputs, effectivePresentation);
      sendProtocolMessage(session, response);
      promptBurstCoordinator.recordPromptEmission(sessionId, outputs);
      maybeAppendToScreenBuffer(
          sessionId,
          interpretation,
          outputs,
          resolveLocaleTag(session, sessionId),
          effectivePresentation);
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

  private String resolveLocaleTag(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.LOCALE_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String resolveLocaleTag(WebSocketSession session, String sessionId) {
    return parseNumericSessionId(sessionId)
        .flatMap(sessionContextService::findBySessionId)
        .map(SessionContext::localeTag)
        .filter(StringUtils::hasText)
        .orElse(resolveLocaleTag(session));
  }

  private String formatResponse(
      TextCommand command,
      TextCommandInterpretationResult interpretation,
      WebSocketSession session,
      java.util.List<PlayerOutput> outputs,
      PresentationProperties effectivePresentation) {
    return outputProjector.projectCommandResponse(
        session,
        command,
        interpretation,
        outputs,
        resolveLocaleTag(session, resolveTransportSessionId(session)),
        effectivePresentation);
  }

  private boolean shouldForcePromptEmission(
      TextCommand command, TextCommandInterpretationResult interpretation) {
    if (!interpretation.commandResult().accepted()) {
      return false;
    }
    if (command.type() == TextCommandType.LOOK || command.type() == TextCommandType.QUICKLOOK) {
      return true;
    }
    if (command.type() == TextCommandType.PLAY && !interpretation.reconnectRedrawRecommended()) {
      return true;
    }
    return interpretation.outputs().stream()
        .allMatch(output -> output.kind() == PlayerOutputKind.PROMPT);
  }

  private void sendProtocolMessage(WebSocketSession session, String text) throws IOException {
    if (!StringUtils.hasText(text)) {
      return;
    }
    session.sendMessage(new TextMessage(text));
  }

  private void maybeAppendToScreenBuffer(
      String sessionId,
      TextCommandInterpretationResult interpretation,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation) {
    java.util.List<ScreenBufferService.BufferedEntry> replayEntries =
        replayableEntries(outputs, localeTag, effectivePresentation);
    if (!shouldBuffer(interpretation, replayEntries)) {
      return;
    }
    sessionContextService
        .findBySessionId(Long.parseLong(sessionId))
        .filter(context -> context.gameInstanceId() > 0 && context.characterId() > 0)
        .ifPresent(
            context ->
                screenBufferService.append(
                    context.tenantId(),
                    context.gameInstanceId(),
                    context.characterId(),
                    replayEntries));
  }

  private void maybeReplayScreenBufferAndRefreshLook(
      WebSocketSession session,
      String sessionId,
      TextCommand command,
      TextCommandInterpretationResult interpretation)
      throws IOException {
    boolean reconnectRestoreRequested =
        interpretation.reconnectRedrawRecommended()
            || StringUtils.hasText(resolveConnectContext(session));
    if (command.type() != TextCommandType.PLAY
        || !interpretation.commandResult().accepted()
        || !reconnectRestoreRequested) {
      return;
    }
    sessionContextService
        .findBySessionId(Long.parseLong(sessionId))
        .filter(context -> context.gameInstanceId() > 0 && context.characterId() > 0)
        .ifPresent(
            context -> {
              try (GameplayLoggingContext ignored = GameplayLoggingContext.from(context)) {
                Optional<ScreenBufferService.BufferedScreen> maybeBuffer =
                    screenBufferService.get(
                        context.tenantId(), context.gameInstanceId(), context.characterId());
                if (maybeBuffer.isEmpty()) {
                  return;
                }
                maybeBuffer.ifPresent(
                    buffer -> sendReplayChunk(session, buffer.protocolText(), "screen buffer"));
                String localeTag = resolveLocaleTag(session, sessionId);
                PresentationProperties effectivePresentation =
                    settingsResolver.presentation(context);
                PlayerOutput look = renderReconnectLook(sessionId);
                if (look != null) {
                  sendProjectedOutput(
                      session, look, localeTag, effectivePresentation, "fresh LOOK");
                }
                if (effectivePresentation.prompt().emitAfterReconnectRestore()) {
                  composePrompt(context)
                      .ifPresent(
                          prompt -> {
                            sendProjectedOutput(
                                session, prompt, localeTag, effectivePresentation, "fresh prompt");
                            promptBurstCoordinator.recordPromptEmission(sessionId);
                          });
                }
              }
            });
  }

  private void sendReplayChunk(WebSocketSession session, String text, String label) {
    try (CombinedLoggingContext ignored = openLoggingContext(session)) {
      try {
        sendProtocolMessage(session, outputProjector.projectTranscriptChunk(session, label, text));
      } catch (IOException ex) {
        logger.warn("Failed to send reconnect {}", label, ex);
      }
    }
  }

  private void sendProjectedOutput(
      WebSocketSession session,
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentation,
      String label) {
    try (CombinedLoggingContext ignored = openLoggingContext(session)) {
      try {
        if (!outputProjector.isFirstPartyWeb(session) && output.kind() == PlayerOutputKind.VIEW) {
          sendProtocolMessage(
              session,
              outputRenderer.renderSuccessfulForCommandType(
                  TextCommandType.LOOK,
                  java.util.List.of(output),
                  localeTag,
                  effectivePresentation));
          return;
        }
        sendProtocolMessage(
            session,
            outputProjector.projectPlayerOutput(session, output, localeTag, effectivePresentation));
      } catch (IOException ex) {
        logger.warn("Failed to send reconnect {}", label, ex);
      }
    }
  }

  private java.util.List<ScreenBufferService.BufferedEntry> replayableEntries(
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation) {
    return outputs.stream()
        .filter(PlayerOutput::screenBufferEligible)
        .map(output -> renderReplayableOutput(output, localeTag, effectivePresentation))
        .filter(StringUtils::hasText)
        .map(text -> ScreenBufferService.BufferedEntry.fromText(text + "\n"))
        .toList();
  }

  private String renderReplayableOutput(
      PlayerOutput output, String localeTag, PresentationProperties effectivePresentation) {
    if (output.kind() == PlayerOutputKind.VIEW) {
      return outputRenderer.renderSuccessfulForCommandType(
          TextCommandType.LOOK, java.util.List.of(output), localeTag, effectivePresentation);
    }
    return outputRenderer.render(output, localeTag, effectivePresentation);
  }

  private boolean shouldBuffer(
      TextCommandInterpretationResult interpretation,
      java.util.List<ScreenBufferService.BufferedEntry> replayEntries) {
    if (!interpretation.commandResult().accepted() || replayEntries.isEmpty()) {
      return false;
    }
    return true;
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

  private CombinedLoggingContext openLoggingContext(WebSocketSession session) {
    String correlationId = resolveTransportSessionId(session);
    if (!StringUtils.hasText(correlationId)) {
      correlationId = session.getId();
    }
    RuntimeLoggingContext runtimeContext =
        RuntimeLoggingContext.open(runtimeIdentity, correlationId);
    GameplayLoggingContext gameplayContext =
        parseNumericSessionId(correlationId)
            .flatMap(sessionContextService::findBySessionId)
            .map(GameplayLoggingContext::from)
            .orElseGet(GameplayLoggingContext::empty);
    return new CombinedLoggingContext(runtimeContext, gameplayContext);
  }

  private record CombinedLoggingContext(
      RuntimeLoggingContext runtimeContext, GameplayLoggingContext gameplayContext)
      implements AutoCloseable {
    @Override
    public void close() {
      gameplayContext.close();
      runtimeContext.close();
    }
  }

  private Optional<PlayerOutput> composePrompt(SessionContext context) {
    return promptComposer.compose(context);
  }

  private void maybePersistLocaleTag(String sessionId, String localeTag) {
    if (!StringUtils.hasText(localeTag)) {
      return;
    }
    parseNumericSessionId(sessionId)
        .flatMap(sessionContextService::findBySessionId)
        .filter(context -> !StringUtils.hasText(context.localeTag()))
        .ifPresent(
            context ->
                sessionContextService.save(
                    new SessionContext(
                        context.sessionId(),
                        context.tenantId(),
                        context.accountId(),
                        context.loginName(),
                        context.characterId(),
                        context.characterName(),
                        context.gameInstanceId(),
                        context.roomInstanceId(),
                        context.jwt(),
                        localeTag,
                        context.bootstrapGameInstanceId())));
  }

  private PlayerOutput renderReconnectLook(String sessionId) {
    return lookHandler.describePlayerOutput(
        sessionId,
        true,
        net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
            .RECONNECT_REFRESH);
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
              sessionId,
              tenant,
              0L,
              null,
              0L,
              null,
              0L,
              null,
              null,
              resolveLocaleTag(session),
              bootstrapGameInstance));
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
            resolveLocaleTag(session),
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
