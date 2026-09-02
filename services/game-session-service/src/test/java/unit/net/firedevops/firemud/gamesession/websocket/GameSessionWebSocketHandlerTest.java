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
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptBurstCoordinator;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
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

  private GameSessionWebSocketHandler handlerWithMetadata(
      TextCommandMetadataResolver textCommandMetadataResolver) {
    return new GameSessionWebSocketHandler(
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
        textCommandMetadataResolver);
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
                        && "SHARED".equals(context.playableStateScope())
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
  void afterConnectionEstablishedClosesWithServiceUnavailableWhenPointerAuthorityFails()
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
    FirstPartyConnectContext connectContext =
        new FirstPartyConnectContext(
            123L, 22L, "demo", "production", 7L, 3L, "scope-1", "jti", "req-1", "gw-1");
    when(firstPartyConnectContextService.parse("token")).thenReturn(Optional.of(connectContext));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenThrow(new IllegalStateException("database unavailable"));

    handler.afterConnectionEstablished(session);

    verify(session)
        .close(
            argThat(
                status ->
                    status.getCode() == 1013
                        && "ADMISSION_POINTER_AUTHORITY_UNAVAILABLE".equals(status.getReason())));
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
                        && context.pointerVersion() == 3L
                        && "SHARED".equals(context.playableStateScope())));
    verify(activeTransportSessionRegistry).register(41L, session);
  }

  @Test
  void afterConnectionEstablishedRepairsPartialRoutingTupleFromSingularRuntimeAuthority() {
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
                "demo",
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
                    8L,
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
                        && context.pointerVersion() == 8L));
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
                        && context.characterId() == 0L
                        && context.characterName() == null
                        && context.gameInstanceId() == 0L
                        && context.roomInstanceId() == null
                        && context.bootstrapGameInstanceId() == 7L
                        && context.worldSlug() == null
                        && context.realmSlug() == null
                        && context.pointerVersion() == 0L));
  }

  @Test
  void afterConnectionEstablishedDropsPartialRoutingTupleWhenRuntimeAuthorityIsAmbiguous() {
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
                "demo",
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
                        && context.characterId() == 0L
                        && context.characterName() == null
                        && context.gameInstanceId() == 0L
                        && context.roomInstanceId() == null
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
            eq(
                new SessionContext(
                    41L, 22L, 0L, null, 0L, null, 0L, null, null, null, 7L, null, null, 0L, null,
                    null, null)));
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
                        && "demo".equals(context.worldSlug())
                        && "production".equals(context.realmSlug())
                        && context.pointerVersion() == 3L
                        && "SHARED".equals(context.playableStateScope())));
  }

  @Test
  void afterConnectionEstablishedClearsGenericBootstrapRoutingWhenRuntimeAuthorityIsAbsent() {
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
                "demo",
                GameSessionWebSocketHandshakeInterceptor.REALM_SLUG_ATTR,
                "production",
                GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR,
                "3"));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(List.of());

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
            "R-1021",
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
                        && context.pointerVersion() == 2L
                        && "SHARED".equals(context.playableStateScope())));
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
            "R-1021",
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
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK LOOK");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("Recent room line\n"));

    handler.handleMessage(session, new TextMessage("LOOK"));

    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void handleMessageDoesNotAppendScreenBufferForPartialGameplayIdentityShell() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    PlayerOutput output = PlayerOutput.message("Recent room line");
    SessionContext partialShell =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 0L, null, "jwt", "en-NZ", 1L);
    when(parser.parse("LOOK")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(output), false, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(partialShell));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(partialShell), eq(List.of(output)), eq(true)))
        .thenReturn(List.of(output));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK LOOK");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("Recent room line\n"));

    handler.handleMessage(session, new TextMessage("LOOK"));

    verify(screenBufferService, never())
        .append(any(Long.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void handleMessageForcesPromptBurstByUiActionMetadata() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.WHO, List.of(), "WHO");
    PlayerOutput output = PlayerOutput.message("Presence: online in Demo World / Live Realm");
    SessionContext context =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt", "en-NZ", 1L);
    when(parser.parse("WHO")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(output), false, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(context));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(context), eq(List.of(output)), eq(true)))
        .thenReturn(List.of(output));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK WHO");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(
            ScreenBufferService.BufferedEntry.fromText(
                "Presence: online in Demo World / Live Realm\n"));

    handler.handleMessage(session, new TextMessage("WHO"));

    verify(promptBurstCoordinator)
        .applyPromptWindow(eq("41"), eq(context), eq(List.of(output)), eq(true));
  }

  @Test
  void handleMessageForcesPromptBurstForGameplayEntryMetadata() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo");
    PlayerOutput output = PlayerOutput.message("Entering Demo World");
    SessionContext context =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt", "en-NZ", 1L);
    when(parser.parse("PLAY demo")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(output), false, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(context));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(context), eq(List.of(output)), eq(true)))
        .thenReturn(List.of(output));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK PLAY");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("Entering Demo World\n"));

    handler.handleMessage(session, new TextMessage("PLAY demo"));

    verify(promptBurstCoordinator)
        .applyPromptWindow(eq("41"), eq(context), eq(List.of(output)), eq(true));
  }

  @Test
  void handleMessageDoesNotForcePromptBurstForPlayTypeWithoutGameplayEntryMetadata()
      throws Exception {
    handler = handlerWithMetadata(commandId -> Optional.empty());
    TextCommand command =
        new TextCommand(
            "custom-play",
            TextCommandType.PLAY,
            List.of("demo"),
            "CUSTOM-PLAY demo",
            "custom-play",
            null);
    PlayerOutput output = PlayerOutput.message("Custom session response");
    SessionContext context =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt", "en-NZ", 1L);
    when(parser.parse("CUSTOM-PLAY demo")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(output), false, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(context));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(context), eq(List.of(output)), eq(false)))
        .thenReturn(List.of(output));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK CUSTOM-PLAY");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("Custom session response\n"));

    handler.handleMessage(session, new TextMessage("CUSTOM-PLAY demo"));

    verify(promptBurstCoordinator)
        .applyPromptWindow(eq("41"), eq(context), eq(List.of(output)), eq(false));
  }

  @Test
  void handleMessageKeepsNonUiCommunicationUnderNormalPromptBurstPolicy() throws Exception {
    TextCommand command =
        new TextCommand(TextCommandType.SAY, List.of("hello travelers"), "SAY hello travelers");
    PlayerOutput output = PlayerOutput.message("You say, \"hello travelers\"");
    WebSocketSession decoratedSession = Mockito.mock(WebSocketSession.class);
    SessionContext context =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt", "en-NZ", 1L);
    when(session.getId()).thenReturn("session-41");
    when(decoratedSession.getId()).thenReturn("session-41");
    when(activeTransportSessionRegistry.find(41L)).thenReturn(Optional.of(decoratedSession));
    when(parser.parse("SAY hello travelers")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(output), false, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(context));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(context), eq(List.of(output)), eq(false)))
        .thenReturn(List.of(output));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK SAY");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("You say, \"hello travelers\"\n"));

    handler.handleMessage(session, new TextMessage("SAY hello travelers"));

    verify(promptBurstCoordinator)
        .applyPromptWindow(eq("41"), eq(context), eq(List.of(output)), eq(false));
    verify(outputProjector)
        .projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of(output)),
            eq("en-NZ"),
            any(PresentationProperties.class),
            eq(context));
    verify(decoratedSession).sendMessage(argThat(message -> "OK SAY".equals(message.getPayload())));
    verify(session, never()).sendMessage(any(TextMessage.class));
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
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK PLAY");

    handler.handleMessage(session, new TextMessage("PLAY demo"));

    verify(screenBufferService, never()).get(any(Long.class), any(Long.class), any(Long.class));
  }

  @Test
  void handleMessageDoesNotReplayReconnectBufferForPartialGameplayIdentityShell() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo");
    SessionContext partialShell =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 0L, null, 1L, null, "jwt", "en-NZ", 1L);
    when(parser.parse("PLAY demo")).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(), true, false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(partialShell));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(partialShell), eq(List.of()), eq(false)))
        .thenReturn(List.of());
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            eq("en-NZ"),
            any(PresentationProperties.class),
            any()))
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
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK LOOK");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(ScreenBufferService.BufferedEntry.fromText("Recent room line\n"));

    handler.handleMessage(session, new TextMessage("LOOK"));

    verify(sessionAuthenticationService, atLeastOnce()).resolveUnverifiedSessionContext(22L, 41L);
    verify(sessionAuthenticationService, atLeastOnce()).resolveUnverifiedSessionContext("41");
  }

  @Test
  void handleMessageBootstrapsGenericSessionContextBeforeInterpretingWhenMissing()
      throws Exception {
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
                "demo",
                GameSessionWebSocketHandshakeInterceptor.REALM_SLUG_ATTR,
                "production",
                GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR,
                "3"));
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    when(parser.parse("LOGIN demo@example.com swordfish")).thenReturn(command);
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.empty());
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(List.of());
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(), false, false));
    when(promptBurstCoordinator.applyPromptWindow(eq("41"), eq(null), eq(List.of()), eq(true)))
        .thenReturn(List.of());
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            eq(null),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK LOGIN");

    handler.handleMessage(session, new TextMessage("LOGIN demo@example.com swordfish"));

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
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sessionContextService, interpreter);
    inOrder.verify(sessionContextService).save(any(SessionContext.class));
    inOrder.verify(interpreter).interpret("41", command, false);
  }

  @Test
  void handleMessageRepairsPartialGenericBootstrapRoutingFromSingularRuntimeAuthority()
      throws Exception {
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
                "demo",
                GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR,
                "3"));
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    when(parser.parse("LOGIN demo@example.com swordfish")).thenReturn(command);
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.empty());
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
                    8L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(), false, false));
    when(promptBurstCoordinator.applyPromptWindow(eq("41"), eq(null), eq(List.of()), eq(true)))
        .thenReturn(List.of());
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            eq(null),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK LOGIN");

    handler.handleMessage(session, new TextMessage("LOGIN demo@example.com swordfish"));

    verify(sessionContextService)
        .save(
            argThat(
                context ->
                    context.sessionId() == 41L
                        && context.tenantId() == 22L
                        && context.bootstrapGameInstanceId() == 7L
                        && "demo".equals(context.worldSlug())
                        && "production".equals(context.realmSlug())
                        && context.pointerVersion() == 8L
                        && "SHARED".equals(context.playableStateScope())));
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sessionContextService, interpreter);
    inOrder.verify(sessionContextService).save(any(SessionContext.class));
    inOrder.verify(interpreter).interpret("41", command, false);
  }

  @Test
  void handleMessageRebootsGenericSessionContextWhenSessionIndexLookupIsMissing() throws Exception {
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
                "demo",
                GameSessionWebSocketHandshakeInterceptor.REALM_SLUG_ATTR,
                "production",
                GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR,
                "3"));
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    SessionContext tenantScoped =
        new SessionContext(
            41L, 22L, 0L, null, 0L, null, 0L, null, null, null, 7L, "demo", "production", 3L, null);
    when(parser.parse("LOGIN demo@example.com swordfish")).thenReturn(command);
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.of(tenantScoped));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.empty());
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenReturn(List.of());
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(), false, false));
    when(promptBurstCoordinator.applyPromptWindow(
            eq("41"), eq(tenantScoped), eq(List.of()), eq(true)))
        .thenReturn(List.of());
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            eq(null),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK LOGIN");

    handler.handleMessage(session, new TextMessage("LOGIN demo@example.com swordfish"));

    verify(sessionContextService).save(tenantScoped);
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sessionContextService, interpreter);
    inOrder.verify(sessionContextService).save(tenantScoped);
    inOrder.verify(interpreter).interpret("41", command, false);
  }

  @Test
  void handleMessageClearsPartialGenericBootstrapRoutingWhenAuthorityIsAmbiguous()
      throws Exception {
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
                "demo",
                GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR,
                "3"));
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    when(parser.parse("LOGIN demo@example.com swordfish")).thenReturn(command);
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.empty());
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
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(), false, false));
    when(promptBurstCoordinator.applyPromptWindow(eq("41"), eq(null), eq(List.of()), eq(true)))
        .thenReturn(List.of());
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            eq(null),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK LOGIN");

    handler.handleMessage(session, new TextMessage("LOGIN demo@example.com swordfish"));

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
}
