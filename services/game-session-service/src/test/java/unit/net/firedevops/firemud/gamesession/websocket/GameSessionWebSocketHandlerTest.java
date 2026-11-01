package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandParser;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptBurstCoordinator;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionContextUnavailableException;
import net.firedevops.firemud.gamesession.test.GameplayCommandTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
  void carriesObservedTrustedProxyTransportThroughTextInterpretation() throws Exception {
    var observed =
        new ObservedDirectTextTransport(
            "41", UUID.fromString("9fb38c88-2f1d-42aa-8e0b-7eb69caf8814"));
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.OBSERVED_DIRECT_TEXT_TRANSPORT_ATTR,
                observed));
    TextCommand command = new TextCommand(TextCommandType.HELP, List.of(), "HELP");
    when(parser.parse("HELP")).thenReturn(command);
    when(interpreter.interpret("41", command, false, observed))
        .thenReturn(new TextCommandInterpretationResult(CommandEnqueueResult.success()));
    SessionContext context = GameplayCommandTestFixtures.bootstrapDemo();
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(context));
    when(promptBurstCoordinator.applyPromptWindow(any(), any(), any(), anyBoolean()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            Mockito.nullable(String.class),
            any(PresentationProperties.class),
            any()))
        .thenReturn("OK HELP");

    handler.handleMessage(session, new TextMessage("HELP"));

    verify(interpreter).interpret("41", command, false, observed);
    verify(interpreter, never()).interpret("41", command, false);
  }

  @Test
  void nullObservedDispatchResultNeverFallsBackToASecondDispatch() throws Exception {
    var observed =
        new ObservedDirectTextTransport(
            "41", UUID.fromString("9fb38c88-2f1d-42aa-8e0b-7eb69caf8814"));
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.OBSERVED_DIRECT_TEXT_TRANSPORT_ATTR,
                observed));
    TextCommand command = new TextCommand(TextCommandType.HELP, List.of(), "HELP");
    when(parser.parse("HELP")).thenReturn(command);
    when(interpreter.interpret("41", command, false, observed)).thenReturn(null);
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.of(GameplayCommandTestFixtures.bootstrapDemo()));

    assertThatThrownBy(() -> handler.handleMessage(session, new TextMessage("HELP")))
        .isInstanceOf(NullPointerException.class);

    verify(interpreter).interpret("41", command, false, observed);
    verify(interpreter, never()).interpret("41", command, false);
  }

  @Test
  void messageStopsWhenFirstPartyBootstrapContextCannotBeRestored() throws Exception {
    var fixture = new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture();
    String token = fixture.token();
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR,
                "first_party_web",
                GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR,
                token));
    when(firstPartyConnectContextService.parse(token))
        .thenAnswer(invocation -> fixture.service.parse(invocation.getArgument(0)))
        .thenReturn(Optional.empty());
    when(firstPartyConnectContextService.requireRetained(any()))
        .thenAnswer(invocation -> fixture.service.requireRetained(invocation.getArgument(0)));
    when(activeTransportSessionRegistry.find(41L)).thenReturn(Optional.of(session));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.empty());
    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    when(parser.parse("LOOK")).thenReturn(command);

    handler.afterConnectionEstablished(session);
    handler.handleMessage(session, new TextMessage("LOOK"));

    verify(session)
        .close(
            argThat(
                status ->
                    status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()
                        && "CONNECT_CONTEXT_INVALID".equals(status.getReason())));
    verify(interpreter, never())
        .interpret(Mockito.anyString(), Mockito.any(TextCommand.class), Mockito.anyBoolean());
    verify(session, never()).sendMessage(any());
  }

  @Test
  void establishmentClosesGenericTransportWithoutReplacingIncumbentWhenPointerReadFails()
      throws Exception {
    WebSocketSession incumbent = Mockito.mock(WebSocketSession.class);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR,
                "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR,
                "7"));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.empty());
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenThrow(new IllegalStateException("pointer store unavailable"));
    when(activeTransportSessionRegistry.find(41L)).thenReturn(Optional.of(incumbent));

    handler.afterConnectionEstablished(session);

    verify(session)
        .close(
            argThat(
                status ->
                    status.getCode() == 1013
                        && "ADMISSION_POINTER_AUTHORITY_UNAVAILABLE".equals(status.getReason())));
    verify(activeTransportSessionRegistry, never()).register(41L, session);
    verify(activeTransportSessionRegistry, never()).unregister(Mockito.anyLong(), Mockito.any());
  }

  @Test
  void messageStopsWhenGenericBootstrapPointerReadFails() throws Exception {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR,
                "41",
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR,
                "22",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR,
                "7"));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext(22L, 41L))
        .thenReturn(Optional.empty());
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 7L))
        .thenThrow(new IllegalStateException("pointer store unavailable"));
    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    when(parser.parse("LOOK")).thenReturn(command);

    handler.handleMessage(session, new TextMessage("LOOK"));

    verify(session)
        .close(
            argThat(
                status ->
                    status.getCode() == 1013
                        && "ADMISSION_POINTER_AUTHORITY_UNAVAILABLE".equals(status.getReason())));
    verify(interpreter, never())
        .interpret(Mockito.anyString(), Mockito.any(TextCommand.class), Mockito.anyBoolean());
    verify(session, never()).sendMessage(any());
  }

  @Test
  void establishmentClosesUnavailableTransportAndCleansMdcBeforeLaterHealthyConnection()
      throws Exception {
    when(runtimeIdentity.service()).thenReturn("game-session-service");
    when(runtimeIdentity.serviceInstanceId()).thenReturn("instance-1");
    Map<String, String> originalMdc = org.slf4j.MDC.getCopyOfContextMap();
    org.slf4j.MDC.put("requestMarker", "prior-value");
    Map<String, String> priorMdc = org.slf4j.MDC.getCopyOfContextMap();
    when(session.getAttributes())
        .thenReturn(Map.of(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41"));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenThrow(new SessionContextUnavailableException("retained context unreadable", null))
        .thenReturn(Optional.empty());

    try {
      handler.afterConnectionEstablished(session);

      verify(session)
          .close(
              argThat(
                  status ->
                      status.getCode() == 1013
                          && "backend_unavailable".equals(status.getReason())));
      verify(activeTransportSessionRegistry, never()).register(41L, session);
      verify(activeTransportSessionRegistry, never()).unregister(Mockito.anyLong(), Mockito.any());
      verify(sessionContextService, never()).save(Mockito.any());
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("service")).isNull();
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("serviceInstanceId")).isNull();
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("correlationId")).isNull();
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.getCopyOfContextMap())
          .isEqualTo(priorMdc);

      WebSocketSession healthySession = Mockito.mock(WebSocketSession.class);
      when(healthySession.getAttributes())
          .thenReturn(Map.of(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "42"));
      when(sessionAuthenticationService.resolveUnverifiedSessionContext("42"))
          .thenReturn(Optional.empty());
      handler.afterConnectionEstablished(healthySession);

      verify(activeTransportSessionRegistry).register(42L, healthySession);
      verify(healthySession, never()).close(Mockito.any(CloseStatus.class));
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.getCopyOfContextMap())
          .isEqualTo(priorMdc);
    } finally {
      restoreMdc(originalMdc);
    }
  }

  @Test
  void messageClosesTransportWhenRetainedContextCannotBeRead() throws Exception {
    when(runtimeIdentity.service()).thenReturn("game-session-service");
    when(runtimeIdentity.serviceInstanceId()).thenReturn("instance-1");
    Map<String, String> originalMdc = org.slf4j.MDC.getCopyOfContextMap();
    org.slf4j.MDC.put("requestMarker", "prior-value");
    Map<String, String> priorMdc = org.slf4j.MDC.getCopyOfContextMap();
    when(session.getAttributes())
        .thenReturn(Map.of(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41"));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenThrow(new SessionContextUnavailableException("retained context unreadable", null));

    try {
      handler.handleMessage(session, new TextMessage("LOOK"));

      verify(session)
          .close(
              argThat(
                  status ->
                      status.getCode() == 1013
                          && "backend_unavailable".equals(status.getReason())));
      verify(parser, never()).parse(Mockito.anyString());
      verify(interpreter, never())
          .interpret(Mockito.anyString(), Mockito.any(TextCommand.class), Mockito.anyBoolean());
      verify(sessionContextService, never()).save(Mockito.any());
      verify(activeTransportSessionRegistry, never()).register(Mockito.anyLong(), Mockito.any());
      verify(activeTransportSessionRegistry, never()).unregister(Mockito.anyLong(), Mockito.any());
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("service")).isNull();
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("serviceInstanceId")).isNull();
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("correlationId")).isNull();
      org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.getCopyOfContextMap())
          .isEqualTo(priorMdc);
    } finally {
      restoreMdc(originalMdc);
    }
  }

  private static void restoreMdc(Map<String, String> contextMap) {
    if (contextMap == null) {
      org.slf4j.MDC.clear();
    } else {
      org.slf4j.MDC.setContextMap(contextMap);
    }
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
                pointer(
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
                pointer(
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
                pointer(
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
                pointer(
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
                pointer(
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
                pointer(
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
                pointer(
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
                    41L, 22L, null, null, 0L, null, 0L, null, null, null, 7L, null, null, 0L, null,
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
                pointer(
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
                        && context.pointerVersion() == 0L
                        && context.playableStateScope() == null));
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
            "123",
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
                pointer(
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
                        && context.accountId() == null
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
            "123",
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
                pointer(
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
                pointer(
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
                        && context.accountId() == null
                        && context.bootstrapGameInstanceId() == 7L
                        && context.worldSlug() == null
                        && context.realmSlug() == null
                        && context.pointerVersion() == 0L));
  }

  @Test
  void handleMessageDoesNotAppendScreenBufferForNormalizedLoggedInShell() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    PlayerOutput output = PlayerOutput.message("Recent room line");
    SessionContext clearedShell = GameplayCommandTestFixtures.bootstrapDemo();
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
            41L, 22L, "123", "demo@example.com", 7001L, "Emberline", 0L, null, "jwt", "en-NZ", 1L);
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
    SessionContext context = GameplayCommandTestFixtures.webSocketEmberline();
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
    ScreenBufferService.BufferedEntry bufferedEntry =
        ScreenBufferService.BufferedEntry.fromText("Entering Demo World\n");
    SessionContext context = GameplayCommandTestFixtures.webSocketEmberline();
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
    when(outputProjector.renderClassicPlayerOutput(
            eq(output), eq("en-NZ"), any(PresentationProperties.class)))
        .thenReturn("Entering Demo World");
    when(outputProjector.toBufferedEntry(any(PlayerOutput.class), any(String.class)))
        .thenReturn(bufferedEntry);

    handler.handleMessage(session, new TextMessage("PLAY demo"));

    verify(promptBurstCoordinator)
        .applyPromptWindow(eq("41"), eq(context), eq(List.of(output)), eq(true));
    verify(screenBufferService)
        .append(
            context.tenantId(),
            context.gameInstanceId(),
            context.characterId(),
            List.of(bufferedEntry));
    Mockito.verifyNoInteractions(lookHandler);
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
    SessionContext context = GameplayCommandTestFixtures.webSocketEmberline();
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
    SessionContext context = GameplayCommandTestFixtures.webSocketEmberline();
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

  @ParameterizedTest
  @CsvSource({
    "true, true, true", "true, false, true", "false, true, true", "false, false, true",
    "true, true, false", "true, false, false", "false, true, false", "false, false, false"
  })
  void genericReconnectSkipsPrivateBufferAndAlwaysRedrawsWithEffectivePromptPolicy(
      boolean enabled, boolean emitAfterRestore, boolean bufferAvailable) throws Exception {
    SessionContext context = reconnectGameplayContext();
    PresentationProperties presentation =
        new PresentationProperties(
            PresentationProperties.ColorMode.NONE,
            false,
            new PresentationProperties.Prompt(enabled, emitAfterRestore, 150L));
    when(settingsResolver.presentation(context)).thenReturn(presentation);
    when(screenBufferService.get(22L, 1L, 123L))
        .thenReturn(
            bufferAvailable
                ? Optional.of(
                    new ScreenBufferService.BufferedScreen(
                        List.of(ScreenBufferService.BufferedEntry.fromText("PRIVATE CONTEXT")),
                        1,
                        1,
                        1L))
                : Optional.empty());
    prepareReconnectInterpretation(TextCommandType.PLAY, true, true, context);
    PlayerOutput look = PlayerOutput.message("Fresh room\n");
    PlayerOutput prompt = PlayerOutput.prompt("HP: 10> ");
    when(lookHandler.describePlayerOutput(
            "41", true, LookViewOutput.RefreshReason.RECONNECT_REFRESH))
        .thenReturn(look);
    when(outputProjector.projectPlayerOutput(session, look, "en-NZ", presentation))
        .thenReturn("Fresh room\n");
    when(promptComposer.compose(context)).thenReturn(Optional.of(prompt));
    when(outputProjector.projectPlayerOutput(session, prompt, "en-NZ", presentation))
        .thenReturn("HP: 10> ");

    handler.handleMessage(session, new TextMessage("PLAY"));

    verify(screenBufferService, never())
        .get(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    verify(lookHandler)
        .describePlayerOutput("41", true, LookViewOutput.RefreshReason.RECONNECT_REFRESH);
    int promptCount = enabled && emitAfterRestore ? 1 : 0;
    verify(promptComposer, Mockito.times(promptCount)).compose(context);
    verify(promptBurstCoordinator, Mockito.times(promptCount)).recordPromptEmission("41");
    ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
    verify(session, Mockito.times(2 + promptCount)).sendMessage(messages.capture());
    assertThat(messages.getAllValues().stream().map(TextMessage::getPayload).toList())
        .containsExactlyElementsOf(
            promptCount == 1
                ? List.of("OK PLAY\n", "Fresh room\n", "HP: 10> ")
                : List.of("OK PLAY\n", "Fresh room\n"));
  }

  @Test
  void controlledAcceptedFirstPartyInterpretationRedrawsWithoutConnectContextOrRecommendation()
      throws Exception {
    // This isolates rendering only: no first-party LOGIN, admission, or bootstrap success is
    // proved.
    when(session.getId()).thenReturn("controlled-renderer-session");
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));
    when(activeTransportSessionRegistry.find(41L)).thenReturn(Optional.of(session));
    SessionContext context = reconnectGameplayContext();
    prepareReconnectInterpretation(TextCommandType.PLAY, true, false, context);
    PlayerOutput look = PlayerOutput.message("Fresh room\n");
    when(lookHandler.describePlayerOutput(
            "41", true, LookViewOutput.RefreshReason.RECONNECT_REFRESH))
        .thenReturn(look);
    when(outputProjector.projectPlayerOutput(
            eq(session), eq(look), eq("en-NZ"), any(PresentationProperties.class)))
        .thenReturn("Fresh room\n");

    handler.handleMessage(session, new TextMessage("PLAY"));

    verify(lookHandler)
        .describePlayerOutput("41", true, LookViewOutput.RefreshReason.RECONNECT_REFRESH);
    verify(screenBufferService, never())
        .get(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    InOrder order = Mockito.inOrder(session);
    order.verify(session).sendMessage(argThat(message -> "OK PLAY\n".equals(message.getPayload())));
    order
        .verify(session)
        .sendMessage(argThat(message -> "Fresh room\n".equals(message.getPayload())));
    order.verifyNoMoreInteractions();
  }

  @ParameterizedTest
  @CsvSource({"PLAY, false, true", "LOGIN, true, true", "PLAY, true, false"})
  void genericConnectContextAloneAndUnacceptedOrNonPlayCommandsDoNotRestore(
      TextCommandType type, boolean accepted, boolean redrawRecommended) throws Exception {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR,
                    "unqualified-context"));
    prepareReconnectInterpretation(type, accepted, redrawRecommended, reconnectGameplayContext());

    handler.handleMessage(session, new TextMessage(type.name()));

    verify(screenBufferService, never())
        .get(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.verifyNoInteractions(lookHandler, promptComposer);
  }

  @Test
  void reconnectWithMissingGameplayContextDoesNotRestore() throws Exception {
    prepareReconnectInterpretation(TextCommandType.PLAY, true, true, null);

    handler.handleMessage(session, new TextMessage("PLAY"));

    verify(screenBufferService, never())
        .get(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.verifyNoInteractions(lookHandler, promptComposer);
  }

  private SessionContext reconnectGameplayContext() {
    return new SessionContext(
        41L,
        22L,
        GameplayCommandTestFixtures.communicationSenderEmberline().accountId(),
        "demo@example.com",
        123L,
        "Emberline",
        1L,
        "R-1",
        "jwt",
        "en-NZ",
        1L);
  }

  private void prepareReconnectInterpretation(
      TextCommandType type, boolean accepted, boolean redrawRecommended, SessionContext context) {
    TextCommand command = new TextCommand(type, List.of(), type.name());
    when(parser.parse(type.name())).thenReturn(command);
    when(interpreter.interpret("41", command, false))
        .thenReturn(
            new TextCommandInterpretationResult(
                accepted
                    ? CommandEnqueueResult.success()
                    : CommandEnqueueResult.failure("PLAY_REQUIRED", "PLAY required"),
                List.of(),
                redrawRecommended,
                false));
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(Optional.ofNullable(context));
    when(promptBurstCoordinator.applyPromptWindow(any(), any(), any(), anyBoolean()))
        .thenReturn(List.of());
    when(outputProjector.projectCommandResponse(
            eq(session),
            eq(command),
            any(TextCommandInterpretationResult.class),
            eq(List.of()),
            Mockito.nullable(String.class),
            any(PresentationProperties.class),
            Mockito.nullable(SessionContext.class)))
        .thenReturn(accepted ? "OK " + type.name() + "\n" : "ERROR PLAY_REQUIRED PLAY required\n");
  }

  @Test
  void handleMessageDoesNotReplayReconnectBufferForNormalizedLoggedInShell() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo");
    SessionContext clearedShell = GameplayCommandTestFixtures.bootstrapDemo();
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
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.verifyNoInteractions(lookHandler, promptComposer);
  }

  @Test
  void handleMessageDoesNotReplayReconnectBufferForPartialGameplayIdentityShell() throws Exception {
    TextCommand command = new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo");
    SessionContext partialShell =
        new SessionContext(
            41L, 22L, "123", "demo@example.com", 0L, null, 1L, null, "jwt", "en-NZ", 1L);
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
    verify(screenBufferService, never())
        .clear(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.verifyNoInteractions(lookHandler, promptComposer);
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
    SessionContext clearedShell = GameplayCommandTestFixtures.bootstrapDemo();
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
                pointer(
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
            41L,
            22L,
            null,
            null,
            0L,
            null,
            0L,
            null,
            null,
            null,
            7L,
            "demo",
            "production",
            3L,
            null);
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
                pointer(
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
                pointer(
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

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean visible,
      boolean publicProductionRealm,
      boolean requiresCharacterSelection,
      String stateScope,
      String characterCreationPolicy) {
    String namespaceIdentity =
        "SHARED".equals(stateScope)
            ? "shared-namespace:" + tenantId
            : "isolated-namespace:" + tenantId + ":" + worldSlug + ":" + realmSlug;
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        visible,
        publicProductionRealm,
        requiresCharacterSelection,
        stateScope,
        characterCreationPolicy,
        1L,
        stableUuid("realm:" + tenantId + ":" + worldSlug + ":" + realmSlug),
        stableUuid(namespaceIdentity));
  }

  private static UUID stableUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void firstPartyBootstrapRetainsFullSignedContextAndOnlyCanonicalOwnerHandles() throws Exception {
    var fixture = new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture();
    var context = fixture.context();
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web",
                GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR, context.compactJws(),
                GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR, "999",
                GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR, "888"));
    when(firstPartyConnectContextService.parse(context.compactJws()))
        .thenAnswer(invocation -> fixture.service.parse(invocation.getArgument(0)));
    when(firstPartyConnectContextService.requireRetained(any()))
        .thenAnswer(invocation -> fixture.service.requireRetained(invocation.getArgument(0)));
    handler.afterConnectionEstablished(session);
    var captor = org.mockito.ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().tenantId()).isEqualTo(22L);
    org.assertj.core.api.Assertions.assertThat(captor.getValue().bootstrapGameInstanceId())
        .isEqualTo(41L);
    org.assertj.core.api.Assertions.assertThat(captor.getValue().firstPartyConnectContext())
        .isEqualTo(context);
    org.assertj.core.api.Assertions.assertThat(captor.getValue().hasAccountIdentity()).isFalse();
    verify(gameplayAdmissionPointerAuthorityService, never())
        .listByRuntimeTarget(Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void failedFirstPartyBootstrapSaveUnregistersContextBeforeClosingUnavailableTransport()
      throws Exception {
    configureFirstPartyBootstrap();
    var saveFailure =
        new SessionContextUnavailableException(
            "session save unavailable", new IllegalStateException());
    Mockito.doThrow(saveFailure).when(sessionContextService).save(any(SessionContext.class));

    handler.afterConnectionEstablished(session);

    InOrder order = Mockito.inOrder(firstPartyConnectContextRegistry, session);
    order.verify(firstPartyConnectContextRegistry).register(Mockito.eq(41L), any());
    order.verify(firstPartyConnectContextRegistry).unregister(41L);
    order
        .verify(session)
        .close(
            Mockito.argThat(
                (CloseStatus status) ->
                    status.getCode() == 1013 && "backend_unavailable".equals(status.getReason())));
    verify(activeTransportSessionRegistry, never()).register(Mockito.anyLong(), any());
    assertThat(saveFailure.getSuppressed()).isEmpty();
  }

  @Test
  void failedFirstPartyBootstrapSaveUnregistersContextBeforeClosingInvalidTransport()
      throws Exception {
    configureFirstPartyBootstrap();
    var saveFailure = new IllegalStateException("session save rejected");
    Mockito.doThrow(saveFailure).when(sessionContextService).save(any(SessionContext.class));

    handler.afterConnectionEstablished(session);

    InOrder order = Mockito.inOrder(firstPartyConnectContextRegistry, session);
    order.verify(firstPartyConnectContextRegistry).register(Mockito.eq(41L), any());
    order.verify(firstPartyConnectContextRegistry).unregister(41L);
    order
        .verify(session)
        .close(
            Mockito.argThat(
                (CloseStatus status) ->
                    status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()
                        && "CONNECT_CONTEXT_INVALID".equals(status.getReason())));
    verify(activeTransportSessionRegistry, never()).register(Mockito.anyLong(), any());
    assertThat(saveFailure.getSuppressed()).isEmpty();
  }

  @Test
  void failedFirstPartyBootstrapSavePreservesPrimaryFailureWhenUnregisterAlsoFails()
      throws Exception {
    configureFirstPartyBootstrap();
    var saveFailure =
        new SessionContextUnavailableException(
            "session save unavailable", new IllegalStateException());
    var cleanupFailure = new IllegalStateException("context unregister unavailable");
    Mockito.doThrow(saveFailure).when(sessionContextService).save(any(SessionContext.class));
    Mockito.doThrow(cleanupFailure).when(firstPartyConnectContextRegistry).unregister(41L);

    handler.afterConnectionEstablished(session);

    assertThat(saveFailure.getSuppressed()).containsExactly(cleanupFailure);
    verify(activeTransportSessionRegistry, never()).register(Mockito.anyLong(), any());
    verify(session).close(Mockito.argThat(status -> status.getCode() == 1013));
  }

  @Test
  void failedFirstPartyBootstrapSaveDoesNotSelfSuppressWhenUnregisterThrowsSameFailure()
      throws Exception {
    configureFirstPartyBootstrap();
    var saveFailure =
        new SessionContextUnavailableException(
            "session save unavailable", new IllegalStateException());
    Mockito.doThrow(saveFailure).when(sessionContextService).save(any(SessionContext.class));
    Mockito.doThrow(saveFailure).when(firstPartyConnectContextRegistry).unregister(41L);

    handler.afterConnectionEstablished(session);

    assertThat(saveFailure.getSuppressed()).isEmpty();
    verify(firstPartyConnectContextRegistry).unregister(41L);
    verify(session).close(Mockito.argThat(status -> status.getCode() == 1013));
  }

  @Test
  void failedFirstPartyContextRegistrationDoesNotAttemptUnregisterOrSave() throws Exception {
    configureFirstPartyBootstrap();
    Mockito.doThrow(new IllegalStateException("context registration rejected"))
        .when(firstPartyConnectContextRegistry)
        .register(Mockito.eq(41L), any());

    handler.afterConnectionEstablished(session);

    verify(firstPartyConnectContextRegistry, never()).unregister(Mockito.anyLong());
    verify(sessionContextService, never()).save(any(SessionContext.class));
    verify(activeTransportSessionRegistry, never()).register(Mockito.anyLong(), any());
    verify(session)
        .close(
            Mockito.argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
  }

  private void configureFirstPartyBootstrap() {
    var fixture = new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture();
    var context = fixture.context();
    String token = context.compactJws();
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web",
                GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR, token));
    when(firstPartyConnectContextService.parse(token))
        .thenAnswer(invocation -> fixture.service.parse(invocation.getArgument(0)));
  }

  @Test
  void missingCanonicalTargetRejectsBeforeRegistryBootstrapAndTransportWrites() throws Exception {
    var fixture = new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture();
    String token = fixture.token();
    when(fixture.routes.readCurrentTarget(
            net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture.TENANT))
        .thenReturn(null);
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web",
                GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_ATTR, token));
    when(firstPartyConnectContextService.parse(token))
        .thenAnswer(invocation -> fixture.service.parse(invocation.getArgument(0)));
    handler.afterConnectionEstablished(session);
    verify(sessionContextService, never()).save(any());
    verify(firstPartyConnectContextRegistry, never()).register(Mockito.anyLong(), any());
    verify(activeTransportSessionRegistry, never()).register(Mockito.anyLong(), any());
    verify(session).close(any(CloseStatus.class));
  }

  @Test
  void ownedRegisteredClosePerformsContextPromptAndPresenceCleanup() {
    when(activeTransportSessionRegistry.unregister(41L, session)).thenReturn(true);
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    verify(firstPartyConnectContextRegistry).unregister(41L);
    verify(promptBurstCoordinator).evict("41");
    verify(gameplayPresenceLifecycleService)
        .recordDisconnected(
            41L,
            net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition
                .TRANSPORT_LOSS);
  }

  @Test
  void rejectedOrReplacedCloseDoesNotCleanUpIncumbentState() {
    when(activeTransportSessionRegistry.unregister(41L, session)).thenReturn(false);
    handler.afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);
    verify(firstPartyConnectContextRegistry, never()).unregister(Mockito.anyLong());
    verify(promptBurstCoordinator, never()).evict(any());
    verify(gameplayPresenceLifecycleService, never()).recordDisconnected(Mockito.anyLong(), any());
  }

  @Test
  void queuedFirstPartyFrameFromRejectedBootstrapCannotUseIncumbentOrdinarySession()
      throws Exception {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));
    WebSocketSession incumbent = Mockito.mock(WebSocketSession.class);
    when(activeTransportSessionRegistry.find(41L))
        .thenReturn(
            Optional.of(
                new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(
                    incumbent, 10_000, 64 * 1024)));
    handler.handleMessage(session, new TextMessage("LOGIN demo@example.com swordfish"));
    handler.afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);
    verify(session).close(any(CloseStatus.class));
    Mockito.verifyNoInteractions(
        parser,
        interpreter,
        sessionAuthenticationService,
        sessionContextService,
        gameplayPresenceLifecycleService,
        screenBufferService,
        promptBurstCoordinator,
        firstPartyConnectContextRegistry);
  }

  @Test
  void queuedFirstPartyFrameWithoutRegistrationCannotBootstrapOrInterpret() throws Exception {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));
    when(activeTransportSessionRegistry.find(41L)).thenReturn(Optional.empty());
    handler.handleMessage(session, new TextMessage("PLAY demo hero"));
    verify(session).close(any(CloseStatus.class));
    Mockito.verifyNoInteractions(
        parser,
        interpreter,
        sessionAuthenticationService,
        sessionContextService,
        gameplayPresenceLifecycleService,
        screenBufferService,
        promptBurstCoordinator,
        firstPartyConnectContextRegistry);
  }

  @Test
  void sendFailureCloseCallbackOwnsCleanup() throws Exception {
    SessionContext context = GameplayCommandTestFixtures.unnamedOutputActor();
    PlayerOutput output = PlayerOutput.notice("hello");
    PresentationProperties presentation = new PresentationProperties();
    when(settingsResolver.presentation(context)).thenReturn(presentation);
    when(activeTransportSessionRegistry.find(41L)).thenReturn(Optional.of(session));
    when(activeTransportSessionRegistry.unregister(41L, session)).thenReturn(true);
    when(session.isOpen()).thenReturn(true);
    when(promptBurstCoordinator.applyPromptWindow(any(), any(), any(), Mockito.eq(false)))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(outputProjector.projectPlayerOutput(session, output, null, presentation))
        .thenReturn("hello");
    Mockito.doThrow(new java.io.IOException("send failed")).when(session).sendMessage(any());
    Mockito.doAnswer(
            invocation -> {
              handler.afterConnectionClosed(session, invocation.getArgument(0));
              return null;
            })
        .when(session)
        .close(any(CloseStatus.class));
    var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    try {
      var delivery =
          new net.firedevops.firemud.gamesession.service.PlayerOutputDeliveryService(
              activeTransportSessionRegistry,
              screenBufferService,
              settingsResolver,
              outputProjector,
              promptComposer,
              promptBurstCoordinator,
              metrics);
      delivery.deliver(context, List.of(output), false);
    } finally {
      metrics.close();
    }
    verify(activeTransportSessionRegistry).unregister(41L, session);
    verify(firstPartyConnectContextRegistry).unregister(41L);
    verify(promptBurstCoordinator).evict("41");
    verify(gameplayPresenceLifecycleService)
        .recordDisconnected(
            41L,
            net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition
                .TRANSPORT_LOSS);
  }

  @Test
  void exactRegisteredDecoratorDelegateAllowsFirstPartyFrameParsing() throws Exception {
    when(session.getAttributes())
        .thenReturn(
            Map.of(
                GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR, "41",
                GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web"));
    when(activeTransportSessionRegistry.find(41L))
        .thenReturn(
            Optional.of(
                new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(
                    session, 10_000, 64 * 1024)));
    when(parser.parse(" ")).thenReturn(new TextCommand(TextCommandType.NOOP, List.of(), " "));
    handler.handleMessage(session, new TextMessage(" "));
    verify(parser).parse(" ");
    verify(session, never()).close(any(CloseStatus.class));
    Mockito.verifyNoInteractions(interpreter);
  }
}
