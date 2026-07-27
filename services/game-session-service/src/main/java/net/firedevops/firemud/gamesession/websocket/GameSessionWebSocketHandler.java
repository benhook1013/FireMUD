package net.firedevops.firemud.gamesession.websocket;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import net.firedevops.firemud.gamesession.command.text.BuiltInTextCommandMetadataResolvers;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionTag;
import net.firedevops.firemud.gamesession.command.text.TextCommandDispatchGroup;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandPromptPolicy;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.presentation.PromptBurstCoordinator;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.PositiveLongParsing;
import net.firedevops.firemud.gamesession.service.ReplayableScreenBufferEntries;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionIdParsing;
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
  private final SessionAuthenticationService sessionAuthenticationService;
  private final SessionContextService sessionContextService;
  private final ActiveTransportSessionRegistry activeTransportSessionRegistry;
  private final FirstPartyConnectContextService firstPartyConnectContextService;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService;
  private final ScreenBufferService screenBufferService;
  private final WebSocketOutputProjector outputProjector;
  private final PromptBurstCoordinator promptBurstCoordinator;
  private final PromptComposer promptComposer;
  private final EffectiveSettingsResolver settingsResolver;
  private final RuntimeIdentity runtimeIdentity;
  private final TextCommandParser parser;
  private final TextCommandMetadataResolver textCommandMetadataResolver;

  public GameSessionWebSocketHandler(
      TextCommandInterpreter interpreter,
      LookCommandHandler lookHandler,
      SessionAuthenticationService sessionAuthenticationService,
      SessionContextService sessionContextService,
      ActiveTransportSessionRegistry activeTransportSessionRegistry,
      FirstPartyConnectContextService firstPartyConnectContextService,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameplayPresenceLifecycleService gameplayPresenceLifecycleService,
      ScreenBufferService screenBufferService,
      WebSocketOutputProjector outputProjector,
      PromptBurstCoordinator promptBurstCoordinator,
      PromptComposer promptComposer,
      EffectiveSettingsResolver settingsResolver,
      RuntimeIdentity runtimeIdentity,
      TextCommandParser parser) {
    this(
        interpreter,
        lookHandler,
        sessionAuthenticationService,
        sessionContextService,
        activeTransportSessionRegistry,
        firstPartyConnectContextService,
        firstPartyConnectContextRegistry,
        gameplayAdmissionPointerAuthorityService,
        gameplayPresenceLifecycleService,
        screenBufferService,
        outputProjector,
        promptBurstCoordinator,
        promptComposer,
        settingsResolver,
        runtimeIdentity,
        parser,
        BuiltInTextCommandMetadataResolvers.builtInOnly());
  }

  @Autowired
  public GameSessionWebSocketHandler(
      TextCommandInterpreter interpreter,
      LookCommandHandler lookHandler,
      SessionAuthenticationService sessionAuthenticationService,
      SessionContextService sessionContextService,
      ActiveTransportSessionRegistry activeTransportSessionRegistry,
      FirstPartyConnectContextService firstPartyConnectContextService,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameplayPresenceLifecycleService gameplayPresenceLifecycleService,
      ScreenBufferService screenBufferService,
      WebSocketOutputProjector outputProjector,
      PromptBurstCoordinator promptBurstCoordinator,
      PromptComposer promptComposer,
      EffectiveSettingsResolver settingsResolver,
      RuntimeIdentity runtimeIdentity,
      TextCommandParser parser,
      TextCommandMetadataResolver textCommandMetadataResolver) {
    this.interpreter = interpreter;
    this.lookHandler = lookHandler;
    this.sessionAuthenticationService = sessionAuthenticationService;
    this.sessionContextService = sessionContextService;
    this.activeTransportSessionRegistry = activeTransportSessionRegistry;
    this.firstPartyConnectContextService = firstPartyConnectContextService;
    this.firstPartyConnectContextRegistry = firstPartyConnectContextRegistry;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.gameplayPresenceLifecycleService = gameplayPresenceLifecycleService;
    this.screenBufferService = screenBufferService;
    this.outputProjector = outputProjector;
    this.promptBurstCoordinator = promptBurstCoordinator;
    this.promptComposer = promptComposer;
    this.settingsResolver = settingsResolver;
    this.runtimeIdentity = runtimeIdentity;
    this.parser = parser;
    this.textCommandMetadataResolver = textCommandMetadataResolver;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    try (CombinedLoggingContext ignored = openLoggingContext(session)) {
      logger.debug(
          "WebSocket session {} established with sessionId={} tenantId={}",
          session.getId(),
          resolveTransportSessionId(session),
          resolveTenantIdText(session));
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
              gameplayPresenceLifecycleService.recordDisconnected(
                  sessionId, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
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
      ensureBootstrapSessionContextIfMissing(session, sessionId);
      TextCommandInterpretationResult interpretation =
          interpreter.interpret(sessionId, command, requiresSoloTick);
      TextCommand resolvedCommand =
          interpretation.resolvedCommand() == null ? command : interpretation.resolvedCommand();
      recordGameplayActivity(sessionId, interpretation);
      Optional<SessionContext> maybeContext = resolveNormalizedSessionContext(session, sessionId);
      PresentationProperties effectivePresentation =
          settingsResolver.presentation(maybeContext.orElse(null));
      java.util.List<PlayerOutput> outputs =
          promptBurstCoordinator.applyPromptWindow(
              sessionId,
              maybeContext.orElse(null),
              interpretation.outputs(),
              shouldForcePromptEmission(resolvedCommand, interpretation));
      maybePersistLocaleTag(maybeContext, resolveLocaleTag(session));
      String response =
          formatResponse(
              resolvedCommand,
              interpretation,
              session,
              outputs,
              effectivePresentation,
              maybeContext.orElse(null));
      sendProtocolMessage(session, response);
      if (shouldCloseAfterFirstPartyScopeRejection(session, resolvedCommand, interpretation)) {
        closeFirstPartyPolicyViolation(session);
        return;
      }
      promptBurstCoordinator.recordPromptEmission(sessionId, outputs);
      maybeAppendToScreenBuffer(
          sessionId,
          maybeContext,
          interpretation,
          outputs,
          resolveLocaleTag(session, sessionId),
          effectivePresentation);
      maybeReplayScreenBufferAndRefreshLook(
          session, sessionId, command, interpretation, maybeContext);
      maybeCloseAfterSuccessfulLogout(session, command, interpretation);
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

  private String resolveBootstrapGameInstanceIdText(WebSocketSession session) {
    Object cached =
        session
            .getAttributes()
            .get(GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String resolveTenantIdText(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR);
    return cached instanceof String text ? text : null;
  }

  private Optional<Long> resolveBootstrapGameInstanceId(WebSocketSession session) {
    return parsePositiveLong(
        resolveBootstrapGameInstanceIdText(session), "bootstrapGameInstanceId");
  }

  private Optional<Long> resolveTenantId(WebSocketSession session) {
    return parsePositiveLong(resolveTenantIdText(session), "tenantId");
  }

  private String resolveWorldSlug(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.WORLD_SLUG_ATTR);
    return cached instanceof String text ? text : null;
  }

  private String resolveRealmSlug(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.REALM_SLUG_ATTR);
    return cached instanceof String text ? text : null;
  }

  private long resolvePointerVersion(WebSocketSession session) {
    Object cached =
        session.getAttributes().get(GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR);
    if (!(cached instanceof String text) || !StringUtils.hasText(text)) {
      return 0L;
    }
    return parsePositiveLong(text, "pointerVersion").orElse(0L);
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
    return resolveNormalizedSessionContext(session, sessionId)
        .map(SessionContext::localeTag)
        .filter(StringUtils::hasText)
        .orElse(resolveLocaleTag(session));
  }

  private Optional<SessionContext> resolveNormalizedSessionContext(String sessionId) {
    return sessionAuthenticationService.resolveUnverifiedSessionContext(sessionId);
  }

  private Optional<SessionContext> resolveNormalizedSessionContext(
      WebSocketSession session, String sessionId) {
    String tenantIdText = resolveTenantIdText(session);
    if (!StringUtils.hasText(tenantIdText)) {
      return resolveNormalizedSessionContext(sessionId);
    }
    Optional<Long> maybeTenantId = resolveTenantId(session);
    if (maybeTenantId.isEmpty()) {
      return Optional.empty();
    }
    Optional<Long> maybeSessionId = parseNumericSessionId(sessionId);
    if (maybeSessionId.isEmpty()) {
      return Optional.empty();
    }
    return sessionAuthenticationService.resolveUnverifiedSessionContext(
        maybeTenantId.get(), maybeSessionId.get());
  }

  private String formatResponse(
      TextCommand command,
      TextCommandInterpretationResult interpretation,
      WebSocketSession session,
      java.util.List<PlayerOutput> outputs,
      PresentationProperties effectivePresentation,
      SessionContext context) {
    return outputProjector.projectCommandResponse(
        session,
        command,
        interpretation,
        outputs,
        resolveLocaleTag(session, resolveTransportSessionId(session)),
        effectivePresentation,
        context);
  }

  private boolean shouldForcePromptEmission(
      TextCommand command, TextCommandInterpretationResult interpretation) {
    if (!interpretation.commandResult().accepted()) {
      return false;
    }
    TextCommandMetadataResolver.ResolvedTextCommandMetadata metadata =
        interpretation.resolvedMetadata() != null
            ? interpretation.resolvedMetadata()
            : textCommandMetadataResolver.resolve(command.commandId()).orElse(null);
    if (metadata == null) {
      return interpretation.outputs().stream()
          .allMatch(output -> output.kind() == PlayerOutputKind.PROMPT);
    }
    if (metadata.actionTags().contains(TextCommandActionTag.UI)) {
      return true;
    }
    if (metadata.dispatchGroup() == TextCommandDispatchGroup.SESSION
        && metadata.promptPolicy() == TextCommandPromptPolicy.WHEN_GAMEPLAY
        && !interpretation.reconnectRedrawRecommended()) {
      return true;
    }
    return interpretation.outputs().stream()
        .allMatch(output -> output.kind() == PlayerOutputKind.PROMPT);
  }

  private void sendProtocolMessage(WebSocketSession session, String text) throws IOException {
    if (!StringUtils.hasText(text)) {
      return;
    }
    WebSocketSession deliverySession =
        parseNumericSessionId(resolveTransportSessionId(session))
            .flatMap(activeTransportSessionRegistry::find)
            // A superseded connection must not send its response through the replacement session.
            .filter(registered -> registered.getId().equals(session.getId()))
            .orElse(session);
    deliverySession.sendMessage(new TextMessage(text));
  }

  private void maybeCloseAfterSuccessfulLogout(
      WebSocketSession session, TextCommand command, TextCommandInterpretationResult interpretation)
      throws IOException {
    if (command.type() != TextCommandType.LOGOUT || !interpretation.commandResult().accepted()) {
      return;
    }
    session.close(new CloseStatus(CloseStatus.NORMAL.getCode(), "logout"));
  }

  private boolean shouldCloseAfterFirstPartyScopeRejection(
      WebSocketSession session,
      TextCommand command,
      TextCommandInterpretationResult interpretation) {
    return "first_party_web".equals(resolveConnectionMode(session))
        && (command.type() == TextCommandType.LOGIN || command.type() == TextCommandType.PLAY)
        && !interpretation.commandResult().accepted()
        && "CONNECT_SCOPE_MISMATCH".equals(interpretation.commandResult().errorCode());
  }

  private void closeFirstPartyPolicyViolation(WebSocketSession session) {
    try {
      session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "policy_violation"));
    } catch (IOException ex) {
      logger.warn("Failed to close session after first-party scope mismatch", ex);
    }
  }

  private void recordGameplayActivity(
      String sessionId, TextCommandInterpretationResult interpretation) {
    if (!interpretation.commandResult().accepted()) {
      return;
    }
    parseNumericSessionId(sessionId)
        .ifPresent(
            numericSessionId -> {
              gameplayPresenceLifecycleService.recordActivity(
                  numericSessionId, interpretation.meaningfulGameplayActivity());
            });
  }

  private void maybeAppendToScreenBuffer(
      String sessionId,
      Optional<SessionContext> maybeContext,
      TextCommandInterpretationResult interpretation,
      java.util.List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation) {
    java.util.List<ScreenBufferService.BufferedEntry> replayEntries =
        replayableEntries(outputs, localeTag, effectivePresentation);
    if (!shouldBuffer(interpretation, replayEntries)) {
      return;
    }
    maybeContext
        .filter(SessionContext::hasGameplayIdentity)
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
      TextCommandInterpretationResult interpretation,
      Optional<SessionContext> maybeContext)
      throws IOException {
    boolean reconnectRestoreRequested =
        interpretation.reconnectRedrawRecommended()
            || StringUtils.hasText(resolveConnectContext(session));
    if (command.type() != TextCommandType.PLAY
        || !interpretation.commandResult().accepted()
        || !reconnectRestoreRequested) {
      return;
    }
    maybeContext
        .filter(SessionContext::hasGameplayIdentity)
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
                    buffer -> sendReplayEntries(session, buffer, "screen buffer"));
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

  private void sendReplayEntries(
      WebSocketSession session, ScreenBufferService.BufferedScreen buffer, String label) {
    if (!outputProjector.isFirstPartyWeb(session)
        || buffer.entries().stream()
            .noneMatch(ScreenBufferService.BufferedEntry::hasStructuredOutput)) {
      sendReplayChunk(session, buffer.protocolText(), label);
      return;
    }
    for (ScreenBufferService.BufferedEntry entry : buffer.entries()) {
      try (CombinedLoggingContext ignored = openLoggingContext(session)) {
        try {
          sendProtocolMessage(
              session, outputProjector.projectTranscriptEntry(session, label, entry));
        } catch (IOException ex) {
          logger.warn("Failed to send reconnect {}", label, ex);
          return;
        }
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
    return ReplayableScreenBufferEntries.fromOutputs(
        outputs, outputProjector, localeTag, effectivePresentation);
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
    parseNumericSessionId(transportSessionId)
        .ifPresent(
            sessionId -> {
              if ("first_party_web".equals(resolveConnectionMode(session))) {
                bootstrapFirstPartySessionContext(session, sessionId);
                return;
              }
              bootstrapGenericSessionContext(session, sessionId);
            });
  }

  private void ensureBootstrapSessionContextIfMissing(
      WebSocketSession session, String transportSessionId) {
    if (!StringUtils.hasText(transportSessionId)) {
      return;
    }
    Optional<SessionContext> tenantScopedContext =
        resolveNormalizedSessionContext(session, transportSessionId);
    if (tenantScopedContext.isPresent()
        && resolveNormalizedSessionContext(transportSessionId).isPresent()) {
      return;
    }
    if (tenantScopedContext.isPresent()) {
      sessionContextService.save(tenantScopedContext.orElseThrow());
      return;
    }
    bootstrapSessionContext(session);
  }

  private CombinedLoggingContext openLoggingContext(WebSocketSession session) {
    String correlationId = resolveTransportSessionId(session);
    if (!StringUtils.hasText(correlationId)) {
      correlationId = session.getId();
    }
    RuntimeLoggingContext runtimeContext =
        RuntimeLoggingContext.open(runtimeIdentity, correlationId);
    GameplayLoggingContext gameplayContext =
        resolveNormalizedSessionContext(session, correlationId)
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

  private void maybePersistLocaleTag(Optional<SessionContext> maybeContext, String localeTag) {
    if (!StringUtils.hasText(localeTag)) {
      return;
    }
    maybeContext
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
                        context.bootstrapGameInstanceId(),
                        context.worldSlug(),
                        context.realmSlug(),
                        context.pointerVersion(),
                        context.playableStateScope(),
                        context.connectScopeId(),
                        context.connectRequestId())));
  }

  private PlayerOutput renderReconnectLook(String sessionId) {
    return lookHandler.describePlayerOutput(
        sessionId,
        true,
        net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
            .RECONNECT_REFRESH);
  }

  private void bootstrapGenericSessionContext(WebSocketSession session, long sessionId) {
    Optional<Long> tenantId = resolveTenantId(session);
    Optional<Long> bootstrapGameInstanceId = resolveBootstrapGameInstanceId(session);
    if (tenantId.isEmpty() || bootstrapGameInstanceId.isEmpty()) {
      return;
    }
    Optional<SessionContext> existing =
        sessionAuthenticationService.resolveUnverifiedSessionContext(tenantId.get(), sessionId);
    SessionContext incomingShell =
        GameplayAdmissionPointerSnapshots.repairGenericBootstrapShell(
            bootstrapShell(
                sessionId,
                tenantId.get(),
                bootstrapGameInstanceId.get(),
                resolveWorldSlug(session),
                resolveRealmSlug(session),
                resolvePointerVersion(session),
                null,
                null,
                resolveLocaleTag(session)),
            gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
                tenantId.get(), bootstrapGameInstanceId.get()));
    if (existing.isPresent()) {
      maybeRefreshBootstrapShell(existing.orElseThrow(), incomingShell);
      return;
    }
    sessionContextService.save(incomingShell);
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
    Optional<SessionContext> existing =
        sessionAuthenticationService.resolveUnverifiedSessionContext(
            connectContext.tenantId(), sessionId);
    if (existing.isPresent()) {
      maybeRefreshBootstrapShell(
          existing.orElseThrow(),
          bootstrapShell(
              sessionId,
              connectContext.tenantId(),
              connectContext.gameInstanceId(),
              connectContext.worldSlug(),
              connectContext.realmSlug(),
              connectContext.pointerVersion(),
              connectContext.connectScopeId(),
              connectContext.connectRequestId(),
              resolveLocaleTag(session)));
      return;
    }
    sessionContextService.save(
        bootstrapShell(
            sessionId,
            connectContext.tenantId(),
            connectContext.gameInstanceId(),
            connectContext.worldSlug(),
            connectContext.realmSlug(),
            connectContext.pointerVersion(),
            connectContext.connectScopeId(),
            connectContext.connectRequestId(),
            resolveLocaleTag(session)));
  }

  private void maybeRefreshBootstrapShell(SessionContext existing, SessionContext incomingShell) {
    if (GameplayAdmissionPointerSnapshots.sameBootstrapRoute(existing, incomingShell)) {
      boolean localeChanged =
          StringUtils.hasText(incomingShell.localeTag())
              && !incomingShell.localeTag().equals(existing.localeTag());
      boolean selectorChanged =
          !java.util.Objects.equals(incomingShell.connectScopeId(), existing.connectScopeId())
              || !java.util.Objects.equals(
                  incomingShell.connectRequestId(), existing.connectRequestId());
      if (selectorChanged && hasAuthenticatedOrGameplayBinding(existing)) {
        gameplayPresenceLifecycleService.clearGameplayBinding(
            existing, "FIRST_PARTY_SELECTOR_CHANGED");
        sessionContextService.save(
            new SessionContext(
                existing.sessionId(),
                existing.tenantId(),
                0L,
                null,
                0L,
                null,
                0L,
                null,
                null,
                StringUtils.hasText(incomingShell.localeTag())
                    ? incomingShell.localeTag()
                    : existing.localeTag(),
                existing.bootstrapGameInstanceId(),
                existing.worldSlug(),
                existing.realmSlug(),
                existing.pointerVersion(),
                null,
                incomingShell.connectScopeId(),
                incomingShell.connectRequestId()));
        return;
      }
      if (localeChanged || selectorChanged) {
        sessionContextService.save(
            new SessionContext(
                existing.sessionId(),
                existing.tenantId(),
                existing.accountId(),
                existing.loginName(),
                existing.characterId(),
                existing.characterName(),
                existing.gameInstanceId(),
                existing.roomInstanceId(),
                existing.jwt(),
                incomingShell.localeTag(),
                existing.bootstrapGameInstanceId(),
                existing.worldSlug(),
                existing.realmSlug(),
                existing.pointerVersion(),
                existing.playableStateScope(),
                incomingShell.connectScopeId(),
                incomingShell.connectRequestId()));
      }
      return;
    }
    gameplayPresenceLifecycleService.clearGameplayBinding(existing, "BOOTSTRAP_ROUTE_CHANGED");
    sessionContextService.save(
        new SessionContext(
            incomingShell.sessionId(),
            incomingShell.tenantId(),
            0L,
            null,
            0L,
            null,
            0L,
            null,
            null,
            incomingShell.localeTag(),
            incomingShell.bootstrapGameInstanceId(),
            incomingShell.worldSlug(),
            incomingShell.realmSlug(),
            incomingShell.pointerVersion(),
            null,
            incomingShell.connectScopeId(),
            incomingShell.connectRequestId()));
  }

  private boolean hasAuthenticatedOrGameplayBinding(SessionContext context) {
    return context.accountId() > 0
        || context.characterId() > 0
        || context.gameInstanceId() > 0
        || StringUtils.hasText(context.roomInstanceId())
        || StringUtils.hasText(context.jwt());
  }

  private SessionContext bootstrapShell(
      long sessionId,
      long tenantId,
      long bootstrapGameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      String connectScopeId,
      String connectRequestId,
      String localeTag) {
    return new SessionContext(
        sessionId,
        tenantId,
        0L,
        null,
        0L,
        null,
        0L,
        null,
        null,
        localeTag,
        bootstrapGameInstanceId,
        worldSlug,
        realmSlug,
        pointerVersion,
        null,
        connectScopeId,
        connectRequestId);
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
    return SessionIdParsing.parse(text).optionalValue();
  }

  private Optional<Long> parsePositiveLong(String text, String fieldName) {
    PositiveLongParsing.ParsedPositiveLong parsed =
        PositiveLongParsing.parseOptionalText(text, fieldName);
    if (parsed.invalid()) {
      logger.debug("Ignoring malformed {} header {}", fieldName, text);
    }
    return parsed.optionalValue();
  }
}
