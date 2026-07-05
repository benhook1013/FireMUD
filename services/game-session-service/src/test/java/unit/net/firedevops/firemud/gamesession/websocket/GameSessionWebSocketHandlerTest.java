package net.firedevops.firemud.gamesession.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptBurstCoordinator;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class GameSessionWebSocketHandlerTest {
  private final TextCommandInterpreter interpreter = Mockito.mock(TextCommandInterpreter.class);
  private final LookCommandHandler lookHandler = Mockito.mock(LookCommandHandler.class);
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final ActiveTransportSessionRegistry activeTransportSessionRegistry =
      Mockito.mock(ActiveTransportSessionRegistry.class);
  private final FirstPartyConnectContextService firstPartyConnectContextService =
      Mockito.mock(FirstPartyConnectContextService.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final ScreenBufferService screenBufferService = Mockito.mock(ScreenBufferService.class);
  private final TextPlayerOutputRenderer outputRenderer =
      Mockito.mock(TextPlayerOutputRenderer.class);
  private final WebSocketOutputProjector outputProjector =
      Mockito.mock(WebSocketOutputProjector.class);
  private final PromptBurstCoordinator promptBurstCoordinator =
      Mockito.mock(PromptBurstCoordinator.class);
  private final PromptComposer promptComposer = Mockito.mock(PromptComposer.class);
  private final EffectiveSettingsResolver settingsResolver =
      Mockito.mock(EffectiveSettingsResolver.class);
  private final RuntimeIdentity runtimeIdentity = Mockito.mock(RuntimeIdentity.class);
  private final TextCommandParser parser = Mockito.mock(TextCommandParser.class);
  private final WebSocketSession session = Mockito.mock(WebSocketSession.class);

  private GameSessionWebSocketHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new GameSessionWebSocketHandler(
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
            outputRenderer,
            outputProjector,
            promptBurstCoordinator,
            promptComposer,
            settingsResolver,
            runtimeIdentity,
            parser);
    when(session.getAttributes())
        .thenReturn(Map.of(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41"));
    when(settingsResolver.presentation(any())).thenReturn(new PresentationProperties());
  }

  @Test
  void afterConnectionEstablishedRegistersValidFirstPartyConnectContext() {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR,
                "first_party_web",
                GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR,
                "token"));
    FirstPartyConnectContext connectContext =
        new FirstPartyConnectContext(
            123L, 22L, "demo", "production", 7L, 3L, "scope-1", "jti", "req-1", "gw-1");
    when(firstPartyConnectContextService.parse("token")).thenReturn(Optional.of(connectContext));

    handler.afterConnectionEstablished(session);

    verify(firstPartyConnectContextRegistry).register(41L, connectContext);
    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.bootstrapGameInstanceId() == 7L
                        && "demo".equals(context.worldSlug())
                        && "production".equals(context.realmSlug())
                        && context.pointerVersion() == 3L
                        && "scope-1".equals(context.connectScopeId())
                        && "req-1".equals(context.connectRequestId())));
  }

  @Test
  void afterConnectionEstablishedRejectsInvalidFirstPartyConnectContextAsExpected()
      throws Exception {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR,
                "first_party_web",
                GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR,
                "token"));
    when(firstPartyConnectContextService.parse("token")).thenReturn(Optional.empty());

    handler.afterConnectionEstablished(session);

    verify(session)
        .close(
            argThat(
                status ->
                    status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()
                        && "CONNECT_CONTEXT_INVALID".equals(status.getReason())));
    verify(firstPartyConnectContextRegistry, never()).register(Mockito.anyLong(), Mockito.any());
    verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void afterConnectionEstablishedRepairsGenericBootstrapShellFromSingularRuntimeAuthority() {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR, "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR, "7"));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));

    handler.afterConnectionEstablished(session);

    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.bootstrapGameInstanceId() == 7L
                        && "demo".equals(context.worldSlug())
                        && "production".equals(context.realmSlug())
                        && context.pointerVersion() == 3L));
    verify(activeTransportSessionRegistry).register(41L, session);
  }

  @Test
  void afterConnectionEstablishedDropsGenericBootstrapRoutingWhenRuntimeAuthorityIsAmbiguous() {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR, "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR, "7"));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW"),
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "preview",
                    "Preview",
                    22L,
                    7L,
                    4L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));

    handler.afterConnectionEstablished(session);

    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.bootstrapGameInstanceId() == 7L
                        && context.worldSlug() == null
                        && context.realmSlug() == null
                        && context.pointerVersion() == 0L));
  }

  @Test
  void
      afterConnectionEstablishedDropsGenericBootstrapRoutingWhenSingularRuntimeAuthorityIsIncomplete() {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR, "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR, "7"));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "",
                    "ALLOW_NEW")));

    handler.afterConnectionEstablished(session);

    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.bootstrapGameInstanceId() == 7L
                        && context.worldSlug() == null
                        && context.realmSlug() == null
                        && context.pointerVersion() == 0L));
  }

  @Test
  void afterConnectionEstablishedSkipsGenericBootstrapWhenTenantIdIsMalformed() {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR,
                "bogus",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR,
                "7"));

    handler.afterConnectionEstablished(session);

    verify(sessionContextService, never()).save(Mockito.any());
    verify(activeTransportSessionRegistry).register(41L, session);
  }

  @Test
  void afterConnectionEstablishedSkipsGenericBootstrapWhenBootstrapGameInstanceIsNonPositive() {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR,
                "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR,
                "0"));

    handler.afterConnectionEstablished(session);

    verify(sessionContextService, never()).save(Mockito.any());
    verify(activeTransportSessionRegistry).register(41L, session);
  }

  @Test
  void
      afterConnectionEstablishedDropsGenericBootstrapWhenRuntimeBundleIsInconsistentWithAuthority() {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR,
                "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR,
                "7",
                GameSessionWebSocketHandshakeInterceptor.WORLD_SLUG_ATTR,
                "wrong",
                GameSessionWebSocketHandshakeInterceptor.REALM_SLUG_ATTR,
                "production",
                GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR,
                "3"));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));

    handler.afterConnectionEstablished(session);

    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.bootstrapGameInstanceId() == 7L
                        && context.worldSlug() == null
                        && context.realmSlug() == null
                        && context.pointerVersion() == 0L));
  }

  @Test
  void
      afterConnectionEstablishedClearsExistingGameplayBindingWhenGenericBootstrapRouteRepairsToNewPointer() {
    SessionContext existing =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "Emberline",
            7L,
            "1021",
            "jwt",
            "en-NZ",
            7L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR, "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR, "7"));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.of(existing));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));

    handler.afterConnectionEstablished(session);

    verify(sessionAuthenticationService, atLeastOnce()).resolveUnverifiedSessionContext(22L, 41L);
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(existing, "BOOTSTRAP_ROUTE_CHANGED");
    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.accountId() == 0L
                        && context.bootstrapGameInstanceId() == 7L
                        && "demo".equals(context.worldSlug())
                        && "production".equals(context.realmSlug())
                        && context.pointerVersion() == 2L));
  }

  @Test
  void
      afterConnectionEstablishedClearsExistingGameplayBindingWhenGenericBootstrapAuthorityBecomesAmbiguous() {
    SessionContext existing =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "Emberline",
            7L,
            "1021",
            "jwt",
            "en-NZ",
            7L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR, "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR, "7"));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.of(existing));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    22L,
                    7L,
                    2L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW"),
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "preview",
                    "Preview",
                    22L,
                    7L,
                    3L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));

    handler.afterConnectionEstablished(session);

    verify(sessionAuthenticationService, atLeastOnce()).resolveUnverifiedSessionContext(22L, 41L);
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(existing, "BOOTSTRAP_ROUTE_CHANGED");
    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.accountId() == 0L
                        && context.bootstrapGameInstanceId() == 7L
                        && context.worldSlug() == null
                        && context.realmSlug() == null
                        && context.pointerVersion() == 0L));
  }

  @Test
  void handleMessageDoesNotAppendScreenBufferForNormalizedLoggedInShell() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    PlayerOutput output = PlayerOutput.message("Recent room line");
    SessionContext clearedShell =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, "jwt", "en-NZ", 1L);
    when(parser.parse("LOOK")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(output), false, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(clearedShell));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(clearedShell), eq(List.of(output)), eq(true)))
        .thenReturn(List.of(output));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class)))
        .thenReturn("OK LOOK");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("Recent room line\n"));

    handler.handleMessage(session, new TextMessage("LOOK"));

    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void handleMessageDoesNotReplayReconnectBufferForNormalizedLoggedInShell() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo");
    SessionContext clearedShell =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, "jwt", "en-NZ", 1L);
    when(parser.parse("PLAY demo")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(), true, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(clearedShell));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(clearedShell), eq(List.of()), eq(false)))
        .thenReturn(List.of());
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            eq("en-NZ"),
            any(PresentationProperties.class)))
        .thenReturn("OK PLAY");

    handler.handleMessage(session, new TextMessage("PLAY demo"));

    verify(screenBufferService, never()).get(any(Long.class), any(Long.class), any(Long.class));
  }

  @Test
  void handleMessageUsesTenantScopedSessionContextWhenTenantAttributePresent() throws Exception {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR,
                "22"));
    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    PlayerOutput output = PlayerOutput.message("Recent room line");
    SessionContext clearedShell =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, "jwt", "en-NZ", 1L);
    when(parser.parse("LOOK")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(output), false, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.of(clearedShell));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(clearedShell), eq(List.of(output)), eq(true)))
        .thenReturn(List.of(output));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class)))
        .thenReturn("OK LOOK");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("Recent room line\n"));

    handler.handleMessage(session, new TextMessage("LOOK"));

    verify(sessionAuthenticationService, atLeastOnce()).resolveUnverifiedSessionContext(22L, 41L);
    verify(sessionAuthenticationService, never()).resolveUnverifiedSessionContext("41");
  }
}
