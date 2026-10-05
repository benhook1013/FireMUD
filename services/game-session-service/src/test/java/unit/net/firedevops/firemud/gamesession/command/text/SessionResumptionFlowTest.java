package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.client.SocialGroupsClient;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.config.WorldTopologyProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.RetainedRuntimeTenantUuidResolver;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.gamesession.service.impl.DefaultGameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.impl.FakeGameplayPresenceService;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@SuppressWarnings("unchecked")
class SessionResumptionFlowTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174000";
  private static final String CANONICAL_TENANT_UUID = "7c958a3d-401e-47ee-8df8-351988b6ce26";
  private static final String LOGIN_PAYLOAD = "LOGIN demo@example.com swordfish";
  private static final String PLAY_PAYLOAD = "PLAY demo production";
  private static final String LOOK_PAYLOAD = "LOOK";

  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final GameInstanceRepository instanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final RetainedRuntimeTenantUuidResolver retainedRuntimeTenantUuidResolver =
      Mockito.mock(RetainedRuntimeTenantUuidResolver.class);
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final ModerationPolicyClient moderationPolicyClient =
      Mockito.mock(ModerationPolicyClient.class);
  private final GameSessionProperties properties = new GameSessionProperties();
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final GameplayWorldCatalog worldCatalog =
      TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final LookTextRenderer lookTextRenderer = Mockito.mock(LookTextRenderer.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private LookCommandHandler lookHandler;
  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final InMemorySessionContextService sessionContextService =
      new InMemorySessionContextService();
  private SessionAuthenticationService sessionAuthenticationService;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final AccountRecentPresenceService accountRecentPresenceService =
      Mockito.mock(AccountRecentPresenceService.class);
  private final GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
  private final ScreenBufferService screenBufferService = Mockito.mock(ScreenBufferService.class);
  private final GameplayAdmissionPointerAuthorityService pointerAuthorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
  private SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private PlayCommandHandler playHandler;
  private final MoveCommandHandler moveHandler = Mockito.mock(MoveCommandHandler.class);
  private final HistoryCommandHandler historyHandler =
      new HistoryCommandHandler(
          Mockito.mock(PlayerCommandHistoryStorageService.class),
          new EffectiveCommandHistorySettingsResolver(
              new FiremudCommandHistoryProperties(10),
              (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()),
          CommandCapabilitiesTestSupport.allEnabled());
  private final HelpCommandHandler helpHandler = new HelpCommandHandler();
  private final CommunicationCommandHandler communicationHandler =
      Mockito.mock(CommunicationCommandHandler.class);
  private final JwtUtil gameplayJwtUtil =
      new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L);
  private final GameplayPresenceService gameplayPresenceService = new FakeGameplayPresenceService();
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      new DefaultGameplayPresenceLifecycleService(
          gameplayPresenceService,
          accountRecentPresenceService,
          sessionRoutingNormalizationService(),
          scriptEventPublisher);
  private final AuthoredActionCommandHandler authoredActionHandler =
      new AuthoredActionCommandHandler();
  private final TextCommandRegistry registry =
      new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider()));
  private final TextCommandParser parser = new TextCommandParser();
  private WorldsCommandHandler worldsHandler;
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    when(retainedRuntimeTenantUuidResolver.resolveCanonicalTenantId(22L))
        .thenReturn(Optional.of(UUID.fromString(CANONICAL_TENANT_UUID)));
    sessionContextService.save(bootstrapShell(1L, 1L));
    sessionContextService.save(bootstrapShell(2L, 1L));
    gameplayCatalogProperties.setWorlds(
        List.of(world("demo", 22L, 1L, false, true), world("sandbox", 22L, 2L, true, false)));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 1L, true)));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 2L))
        .thenReturn(List.of(pointer("sandbox", "production", 22L, 2L, 1L, false)));
    when(instanceRepository.findById(Mockito.anyLong()))
        .thenAnswer(
            invocation -> {
              long sessionId = invocation.getArgument(0);
              GameInstance perCall = new GameInstance();
              perCall.setId(sessionId);
              perCall.setTenantId(22L);
              perCall.setOwnerAccountId(OWNER_ACCOUNT_UUID);
              return Optional.of(perCall);
            });
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAuthToken("jwt")
                .setAccountId("550e8400-e29b-41d4-a716-446655440000")
                .build());
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.active(
                          "550e8400-e29b-41d4-a716-446655440000", 22L, CANONICAL_TENANT_UUID, "1")
                      .toBuilder()
                      .setEvaluatedAt(Instant.now().toString())
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(response, invocation.getArgument(0));
            });
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenAnswer(
            invocation ->
                GetRealmAccessGrantForRuntimeResponse.newBuilder()
                    .setGranted(true)
                    .setAccountId(invocation.getArgument(0))
                    .setTenantId(invocation.getArgument(1))
                    .setWorldSlug(invocation.getArgument(2))
                    .setRealmSlug(invocation.getArgument(3))
                    .build());
    when(moderationPolicyClient.evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyString()))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(true)
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(CANONICAL_TENANT_UUID)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    when(entityManagementClient.listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.class)))
        .thenReturn(
            net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("7001")
                        .setTenantId("22")
                        .setAccountId("550e8400-e29b-41d4-a716-446655440000")
                        .setName("Emberline")
                        .setLevel(12)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .build())
                .build());
    sessionAuthenticationService =
        new SessionAuthenticationService(
            sessionContextService,
            properties,
            sessionRoutingNormalizationService(),
            gameplayPresenceLifecycleService);
    LoginCommandHandler loginHandler =
        new LoginCommandHandler(
            instanceRepository,
            sessionContextService,
            sessionAuthenticationService,
            accountClient,
            firstPartyConnectContextRegistry,
            sessionRoutingNormalizationService(),
            pointerAuthorityService,
            gameplayPresenceLifecycleService,
            meterRegistry);
    lookHandler =
        new LookCommandHandler(
            gameLogicClient,
            lookTextRenderer,
            sessionAuthenticationService,
            gameLogicProperties,
            new EffectiveSettingsResolver(
                new PresentationProperties(),
                new MovementProperties(),
                new WorldTopologyProperties(),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()),
            meterRegistry,
            lookCacheService,
            new TextPlayerOutputRenderer(new PresentationProperties()));
    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-1021").build())
            .build();
    when(gameLogicClient.resolveLook(
            org.mockito.ArgumentMatchers.any(SessionContext.class), anyString(), anyString()))
        .thenReturn(lookResult);
    when(lookTextRenderer.toPlayerOutput(
            Mockito.eq(lookResult),
            Mockito.eq(true),
            Mockito.any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.class),
            Mockito.any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .class)))
        .thenReturn(
            PlayerOutput.view(
                new LookViewOutput(
                    "R-1021",
                    "Resume Hall",
                    "Short text",
                    "Long text",
                    true,
                    List.of(),
                    List.of())));
    DirectTextConnectScopeSessionStore connectScopeSessionStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    playHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService(),
            worldCatalog,
            gameLogicProperties,
            accountClient,
            retainedRuntimeTenantUuidResolver,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry,
            connectScopeSessionStore);
    worldsHandler =
        new WorldsCommandHandler(
            worldCatalog,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest(),
            retainedRuntimeTenantUuidResolver);
    AfkCommandHandler afkHandler =
        new AfkCommandHandler(sessionAuthenticationService, gameplayPresenceService);
    interpreter =
        new TextCommandInterpreter(
            commandService,
            lookHandler,
            loginHandler,
            new LogoutCommandHandler(sessionContextService),
            playHandler,
            moveHandler,
            afkHandler,
            helpHandler,
            historyHandler,
            new WhoCommandHandler(
                gameplayPresenceService,
                new GameplayPresenceActivityResolver(new PresenceProperties()),
                scriptEventPublisher),
            new StatusCommandHandler(gameLogicClient, scriptEventPublisher),
            new FriendsCommandHandler(
                Mockito.mock(SocialGroupsClient.class),
                entityManagementClient,
                scriptEventPublisher),
            authoredActionHandler,
            new InventoryCommandHandler(gameLogicClient),
            new EquipmentCommandHandler(gameLogicClient),
            new ContainerCommandHandler(gameLogicClient),
            sessionAuthenticationService,
            scriptEventPublisher,
            communicationHandler,
            worldsHandler,
            new PromptComposer(),
            registry,
            parser,
            meterRegistry,
            AcceptedCommandHistoryRecorder.NOOP);
  }

  private SessionRoutingNormalizationService sessionRoutingNormalizationService() {
    if (sessionRoutingNormalizationService == null) {
      sessionRoutingNormalizationService =
          new SessionRoutingNormalizationService(sessionContextService, pointerAuthorityService);
    }
    return sessionRoutingNormalizationService;
  }

  @Test
  void secondConnectionResumesAndContinuesLookFlow() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());

    TextCommandInterpretationResult firstPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertTrue(firstPlay.commandResult().accepted());

    TextCommandInterpretationResult firstLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        firstLook.outputs().stream().map(PlayerOutput::kind).toList());
    assertTrue(((LookViewOutput) firstLook.outputs().get(0).payload()).includeLongDescription());

    TextCommandInterpretationResult secondLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(secondLogin.commandResult().accepted());

    TextCommandInterpretationResult secondPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertTrue(secondPlay.commandResult().accepted());

    TextCommandInterpretationResult secondLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(secondLook.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        secondLook.outputs().stream().map(PlayerOutput::kind).toList());
    assertTrue(((LookViewOutput) secondLook.outputs().get(0).payload()).includeLongDescription());

    assertEquals(1.0, meterRegistry.counter("gamesession.session.resume").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.takeover").count());
  }

  @Test
  void secondConnectionTakesOverAndFirstConnectionIsUnauthenticated() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());
    TextCommandInterpretationResult firstPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertTrue(firstPlay.commandResult().accepted());
    TextCommandInterpretationResult firstLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());

    TextCommandInterpretationResult secondLogin = interpreter.interpret("2", LOGIN_PAYLOAD, false);
    assertTrue(secondLogin.commandResult().accepted());
    TextCommandInterpretationResult secondPlay = interpreter.interpret("2", PLAY_PAYLOAD, false);
    assertTrue(secondPlay.commandResult().accepted());
    TextCommandInterpretationResult secondLook = interpreter.interpret("2", LOOK_PAYLOAD, false);
    assertTrue(secondLook.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        secondLook.outputs().stream().map(PlayerOutput::kind).toList());
    assertTrue(((LookViewOutput) secondLook.outputs().get(0).payload()).includeLongDescription());

    TextCommandInterpretationResult firstLookAfterTakeover =
        interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertFalse(firstLookAfterTakeover.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", firstLookAfterTakeover.commandResult().errorCode());

    verify(accountRecentPresenceService)
        .recordDisconnect(1L, AccountRecentPresenceDisposition.TAKEOVER);
    verify(scriptEventPublisher, Mockito.never())
        .publishRegionExitEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    assertEquals(1.0, meterRegistry.counter("gamesession.session.takeover").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.resume").count());
  }

  @Test
  void logoutFailsClosedWithoutChangingSessionOrPresence() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());
    TextCommandInterpretationResult firstPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertTrue(firstPlay.commandResult().accepted());
    SessionContext sessionBeforeLogout =
        sessionContextService.findByTenantAndSessionId(22L, 1L).orElseThrow();
    Mockito.clearInvocations(scriptEventPublisher);

    TextCommandInterpretationResult logout = interpreter.interpret("1", "LOGOUT", false);
    assertFalse(logout.commandResult().accepted());
    assertEquals("LOGOUT_UNAVAILABLE", logout.commandResult().errorCode());
    assertEquals(
        "Logout is temporarily unavailable. Please try again.",
        logout.commandResult().errorMessage());
    assertEquals(
        sessionBeforeLogout, sessionContextService.findByTenantAndSessionId(22L, 1L).orElseThrow());
    assertTrue(
        gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
            .anyMatch(presence -> presence.sessionId() == 1L));
    Mockito.verify(accountRecentPresenceService, Mockito.never())
        .recordDisconnect(1L, AccountRecentPresenceDisposition.LOGOUT);
    Mockito.verify(scriptEventPublisher, Mockito.never())
        .publishCommandEvent(Mockito.any(), Mockito.any());
    Mockito.verify(scriptEventPublisher, Mockito.never())
        .publishRegionExitEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(firstPartyConnectContextRegistry, Mockito.never()).unregister(1L);
    Mockito.verify(gameInstanceService, Mockito.never()).stopSession(Mockito.anyLong());

    TextCommandInterpretationResult secondLogin = interpreter.interpret("2", LOGIN_PAYLOAD, false);
    assertTrue(secondLogin.commandResult().accepted());
    assertEquals(
        sessionBeforeLogout, sessionContextService.findByTenantAndSessionId(22L, 1L).orElseThrow());
    assertTrue(sessionContextService.findByTenantAndSessionId(22L, 2L).isPresent());
    assertTrue(
        gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
            .anyMatch(presence -> presence.sessionId() == 1L));
    assertEquals(0.0, meterRegistry.counter("gamesession.session.takeover").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.resume").count());
  }

  @Test
  void logoutAliasesDoNotNormalizeStaleBindingBeforeFailingClosed() {
    assertTrue(interpreter.interpret("1", LOGIN_PAYLOAD, false).commandResult().accepted());
    assertTrue(interpreter.interpret("1", PLAY_PAYLOAD, false).commandResult().accepted());
    SessionContext before = sessionContextService.findByTenantAndSessionId(22L, 1L).orElseThrow();
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 2L, true)));

    for (String alias : List.of("LOGOUT", "LOGOFF", "QUIT")) {
      TextCommandInterpretationResult result = interpreter.interpret("1", alias, false);
      assertFalse(result.commandResult().accepted());
      assertEquals("LOGOUT_UNAVAILABLE", result.commandResult().errorCode());
      assertEquals(before, sessionContextService.findByTenantAndSessionId(22L, 1L).orElseThrow());
      assertTrue(
          gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
              .anyMatch(presence -> presence.sessionId() == 1L));
    }
    Mockito.verify(accountRecentPresenceService, Mockito.never())
        .recordDisconnect(1L, AccountRecentPresenceDisposition.LOGOUT);
    Mockito.verify(scriptEventPublisher, Mockito.never())
        .publishRegionExitEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void deniedReconnectPlayClearsStaleGameplayBinding() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());
    TextCommandInterpretationResult firstPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertTrue(firstPlay.commandResult().accepted());
    TextCommandInterpretationResult firstLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());

    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                      "550e8400-e29b-41d4-a716-446655440000", 22L, List.of("player"));
              var canonicalResponse = freshMembership(response);
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(canonicalResponse, invocation.getArgument(0));
            });

    TextCommandInterpretationResult secondLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(secondLogin.commandResult().accepted());
    SessionContext authenticatedContextBeforeDeniedPlay =
        sessionContextService.findByTenantAndSessionId(22L, 1L).orElseThrow();
    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000", authenticatedContextBeforeDeniedPlay.accountId());
    assertTrue(authenticatedContextBeforeDeniedPlay.hasAccountIdentity());
    assertEquals("demo@example.com", authenticatedContextBeforeDeniedPlay.loginName());
    assertEquals("jwt", authenticatedContextBeforeDeniedPlay.jwt());
    assertTrue(authenticatedContextBeforeDeniedPlay.hasGameplayRegionBinding());
    assertEquals(1L, authenticatedContextBeforeDeniedPlay.bootstrapGameInstanceId());
    assertEquals("demo", authenticatedContextBeforeDeniedPlay.worldSlug());
    assertEquals("production", authenticatedContextBeforeDeniedPlay.realmSlug());
    assertEquals(1L, authenticatedContextBeforeDeniedPlay.pointerVersion());

    TextCommandInterpretationResult deniedPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertFalse(deniedPlay.commandResult().accepted());
    assertEquals("JOIN_REQUIRED", deniedPlay.commandResult().errorCode());

    SessionContext authenticatedLobbyContext =
        sessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    assertEquals(
        authenticatedContextBeforeDeniedPlay.accountId(), authenticatedLobbyContext.accountId());
    assertEquals(
        authenticatedContextBeforeDeniedPlay.loginName(), authenticatedLobbyContext.loginName());
    assertEquals(authenticatedContextBeforeDeniedPlay.jwt(), authenticatedLobbyContext.jwt());
    assertTrue(authenticatedLobbyContext.hasAccountIdentity());
    assertEquals(1L, authenticatedLobbyContext.bootstrapGameInstanceId());
    assertEquals("demo", authenticatedLobbyContext.worldSlug());
    assertEquals("production", authenticatedLobbyContext.realmSlug());
    assertEquals(1L, authenticatedLobbyContext.pointerVersion());
    assertEquals(0L, authenticatedLobbyContext.gameInstanceId());
    assertEquals(0L, authenticatedLobbyContext.characterId());
    assertNull(authenticatedLobbyContext.characterName());
    assertNull(authenticatedLobbyContext.roomInstanceId());
    assertNull(authenticatedLobbyContext.playableStateScope());
    assertFalse(
        gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
            .anyMatch(presence -> presence.sessionId() == 1L));
    Mockito.verify(accountClient, Mockito.never())
        .joinPublicProductionMembership(
            Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(Instant.class));

    TextCommandInterpretationResult lookAfterDeniedReconnect =
        interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertFalse(lookAfterDeniedReconnect.commandResult().accepted());
    assertEquals("PLAY_REQUIRED", lookAfterDeniedReconnect.commandResult().errorCode());
    assertTrue(sessionAuthenticationService.isAuthenticated("1"));
    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000",
        sessionContextService.findByTenantAndSessionId(22L, 1L).orElseThrow().accountId());
    SessionContext lobbyContextBeforeDeniedJoin =
        sessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    Mockito.clearInvocations(accountClient, moderationPolicyClient, entityManagementClient);
    TextCommandInterpretationResult joinAfterDeniedPlay =
        interpreter.interpret("1", "JOIN demo", false);
    assertFalse(joinAfterDeniedPlay.commandResult().accepted());
    assertEquals("CONNECT_SCOPE_MISMATCH", joinAfterDeniedPlay.commandResult().errorCode());
    assertEquals(
        lobbyContextBeforeDeniedJoin,
        sessionAuthenticationService.resolveSessionContext("1").orElseThrow());
    assertFalse(
        gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
            .anyMatch(presence -> presence.sessionId() == 1L));
    Mockito.verify(accountClient, Mockito.never())
        .joinPublicProductionMembership(
            Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(Instant.class));
    Mockito.verify(moderationPolicyClient, Mockito.never())
        .evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyString());
    Mockito.verify(entityManagementClient, Mockito.never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.class));
  }

  @Test
  void failedReloginClearsStaleAuthenticatedSessionState() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());
    TextCommandInterpretationResult firstPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertTrue(firstPlay.commandResult().accepted());
    TextCommandInterpretationResult firstLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());

    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                        .setMessage("Invalid credentials")
                        .build())
                .build());

    TextCommandInterpretationResult failedLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertFalse(failedLogin.commandResult().accepted());
    assertEquals("INVALID_CREDENTIALS", failedLogin.commandResult().errorCode());

    TextCommandInterpretationResult lookAfterFailedRelogin =
        interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertFalse(lookAfterFailedRelogin.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", lookAfterFailedRelogin.commandResult().errorCode());
  }

  @Test
  void activeGameplaySessionFallsBackToPlayRequiredWhenPointerAdvances() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());
    TextCommandInterpretationResult firstPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);
    assertTrue(firstPlay.commandResult().accepted());
    TextCommandInterpretationResult firstLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());

    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 2L, true)));

    TextCommandInterpretationResult lookAfterCutover =
        interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertFalse(lookAfterCutover.commandResult().accepted());
    assertEquals("PLAY_REQUIRED", lookAfterCutover.commandResult().errorCode());

    Mockito.clearInvocations(entityManagementClient);
    TextCommandInterpretationResult charsAfterCutover =
        interpreter.interpret("1", "CHARS demo", false);
    assertFalse(charsAfterCutover.commandResult().accepted());
    verify(entityManagementClient, Mockito.never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.class));
  }

  @Test
  void staleIdentityMappingFallsBackToFreshSession() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    when(instanceRepository.findById(Mockito.anyLong()))
        .thenAnswer(
            invocation -> {
              long sessionId = invocation.getArgument(0);
              GameInstance perCall = new GameInstance();
              perCall.setId(sessionId);
              perCall.setTenantId(22L);
              perCall.setOwnerAccountId(OWNER_ACCOUNT_UUID);
              return Optional.of(perCall);
            });

    interpreter.interpret("1", command, false);
    interpreter.interpret(
        "1",
        new TextCommand(
            TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"),
        false);
    sessionContextService.evictIdentity(22L, 1L, 7001L);

    TextCommandInterpretationResult staleRetry = interpreter.interpret("2", command, false);

    assertTrue(staleRetry.commandResult().accepted());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.resume").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.takeover").count());
    assertTrue(sessionContextService.findByTenantAndSessionId(22L, 2L).isPresent());
  }

  private static GameplayCatalogProperties.World world(
      String slug,
      long tenantId,
      long gameInstanceId,
      boolean requiresCharacterSelection,
      boolean publicProductionRealm) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(slug);
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug("production");
    realm.setDisplayName("Live Realm");
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setPointerVersion(1L);
    realm.setVisible(true);
    realm.setPublicProductionRealm(publicProductionRealm);
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    world.setRealms(List.of(realm));
    return world;
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug,
      String realmSlug,
      long tenantId,
      long gameInstanceId,
      long pointerVersion,
      boolean publicProductionRealm) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldSlug,
        realmSlug,
        realmSlug,
        tenantId,
        gameInstanceId,
        pointerVersion,
        true,
        publicProductionRealm,
        false,
        "SHARED",
        "ALLOW_NEW",
        1L,
        stableUuid("realm:" + tenantId + ":" + worldSlug + ":" + realmSlug),
        stableUuid("shared-namespace:" + tenantId));
  }

  private static UUID stableUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static SessionContext bootstrapShell(long sessionId, long bootstrapGameInstanceId) {
    return new SessionContext(
        sessionId,
        22L,
        null,
        null,
        0L,
        null,
        0L,
        null,
        null,
        null,
        bootstrapGameInstanceId,
        "demo",
        "production",
        1L,
        null);
  }

  private static final class InMemorySessionContextService implements SessionContextService {
    private final Map<Long, SessionContext> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> identityMap = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> nameMap = new ConcurrentHashMap<>();

    @Override
    public void save(SessionContext context) {
      SessionContext existing =
          hasGameplayIdentity(context)
              ? identityMap.get(
                  identityKey(context.tenantId(), context.gameInstanceId(), context.characterId()))
              : null;
      if (existing != null && existing.sessionId() != context.sessionId()) {
        sessionMap.remove(existing.sessionId());
      }
      sessionMap.put(context.sessionId(), context);
      if (hasGameplayIdentity(context)) {
        identityMap.put(identityKey(context), context);
        if (context.characterName() != null && !context.characterName().isBlank()) {
          nameMap.put(
              nameKey(context.tenantId(), context.gameInstanceId(), context.characterName()),
              context);
        }
      }
    }

    @Override
    public Optional<SessionContext> findBySessionId(long sessionId) {
      return Optional.ofNullable(sessionMap.get(sessionId));
    }

    @Override
    public Optional<SessionContext> findByTenantAndSessionId(long tenantId, long sessionId) {
      SessionContext context = sessionMap.get(sessionId);
      if (context == null || context.tenantId() != tenantId) {
        return Optional.empty();
      }
      return Optional.of(context);
    }

    @Override
    public Optional<SessionContext> findByGameplayIdentity(
        long tenantId, long gameInstanceId, long characterId) {
      return Optional.ofNullable(
          identityMap.get(identityKey(tenantId, gameInstanceId, characterId)));
    }

    @Override
    public Optional<SessionContext> findByGameplayName(
        long tenantId, long gameInstanceId, String characterName) {
      return Optional.ofNullable(nameMap.get(nameKey(tenantId, gameInstanceId, characterName)));
    }

    @Override
    public void deleteBySessionId(long tenantId, long sessionId) {
      SessionContext removed = sessionMap.remove(sessionId);
      if (removed != null && hasGameplayIdentity(removed)) {
        identityMap.remove(identityKey(removed));
        if (removed.characterName() != null && !removed.characterName().isBlank()) {
          nameMap.remove(
              nameKey(removed.tenantId(), removed.gameInstanceId(), removed.characterName()));
        }
      }
    }

    public void evictIdentity(long tenantId, long gameInstanceId, long characterId) {
      identityMap.remove(identityKey(tenantId, gameInstanceId, characterId));
    }

    private String identityKey(SessionContext context) {
      return identityKey(context.tenantId(), context.gameInstanceId(), context.characterId());
    }

    private String identityKey(long tenantId, long gameInstanceId, long characterId) {
      return tenantId + ":" + gameInstanceId + ":" + characterId;
    }

    private String nameKey(long tenantId, long gameInstanceId, String characterName) {
      return tenantId + ":" + gameInstanceId + ":" + characterName.trim().toLowerCase();
    }

    private boolean hasGameplayIdentity(SessionContext context) {
      return context.gameInstanceId() > 0 && context.characterId() > 0;
    }
  }

  @Test
  void reconnectDoesNotRestoreElevationFromPriorTenantRoleClaims() {
    String tenantAdminJwt =
        gameplayJwtUtil.generateToken(
            "550e8400-e29b-41d4-a716-446655440000",
            Map.of(
                "accountId",
                "550e8400-e29b-41d4-a716-446655440000",
                "scopedRoles",
                Map.of("22", List.of("tenantAdmin", "moderator"))));
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAuthToken(tenantAdminJwt)
                .setAccountId("550e8400-e29b-41d4-a716-446655440000")
                .build());

    assertTrue(interpreter.interpret("1", LOGIN_PAYLOAD, false).commandResult().accepted());
    assertTrue(interpreter.interpret("1", PLAY_PAYLOAD, false).commandResult().accepted());
    assertTrue(interpreter.interpret("1", LOOK_PAYLOAD, false).commandResult().accepted());
    assertEquals(
        GameplayPresenceRole.PLAYER,
        gameplayPresenceService.findConnectedBySessionId(1L).orElseThrow().role());

    assertTrue(interpreter.interpret("1", LOGIN_PAYLOAD, false).commandResult().accepted());
    assertTrue(interpreter.interpret("1", PLAY_PAYLOAD, false).commandResult().accepted());

    assertEquals(
        GameplayPresenceRole.PLAYER,
        gameplayPresenceService.findConnectedBySessionId(1L).orElseThrow().role());
    assertEquals(1.0, meterRegistry.counter("gamesession.session.resume").count());
  }

  @Test
  void unavailableAccountAdmissionEvidenceDoesNotElevateReconnectPresence() {
    String tenantAdminJwt =
        gameplayJwtUtil.generateToken(
            "550e8400-e29b-41d4-a716-446655440000",
            Map.of(
                "accountId",
                "550e8400-e29b-41d4-a716-446655440000",
                "scopedRoles",
                Map.of("22", List.of("tenantAdmin"))));
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAuthToken(tenantAdminJwt)
                .setAccountId("550e8400-e29b-41d4-a716-446655440000")
                .build());

    assertTrue(interpreter.interpret("1", LOGIN_PAYLOAD, false).commandResult().accepted());
    assertTrue(interpreter.interpret("1", PLAY_PAYLOAD, false).commandResult().accepted());
    assertEquals(
        GameplayPresenceRole.PLAYER,
        gameplayPresenceService.findConnectedBySessionId(1L).orElseThrow().role());

    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("AUTH_UNAVAILABLE")
                        .setMessage("runtime authority unavailable")
                        .build())
                .build());
    assertTrue(interpreter.interpret("1", LOGIN_PAYLOAD, false).commandResult().accepted());

    TextCommandInterpretationResult deniedPlay = interpreter.interpret("1", PLAY_PAYLOAD, false);

    assertFalse(deniedPlay.commandResult().accepted());
    assertEquals("AUTH_UNAVAILABLE", deniedPlay.commandResult().errorCode());
    assertTrue(
        gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
            .allMatch(presence -> presence.role() == GameplayPresenceRole.PLAYER));
  }

  private static GetTenantMembershipForRuntimeResponse freshMembership(
      GetTenantMembershipForRuntimeResponse response) {
    String originalTenantId = response.getTenantId();
    var builder =
        response.toBuilder()
            .setTenantId(CANONICAL_TENANT_UUID)
            .setRequestTenantId(CANONICAL_TENANT_UUID)
            .setEvaluatedAt(Instant.now().toString());
    if (response.getMembershipVersionCount() > 0) {
      builder
          .clearMembershipVersion()
          .putAllMembershipVersion(rekeyMap(response.getMembershipVersionMap(), originalTenantId));
    }
    if (response.hasMembershipBaseline()) {
      builder.setMembershipBaseline(
          response.getMembershipBaseline().toBuilder()
              .clearMembershipVersion()
              .putAllMembershipVersion(
                  rekeyMap(
                      response.getMembershipBaseline().getMembershipVersionMap(), originalTenantId))
              .build());
    }
    if (response.hasAuthorityTuple()) {
      var tuple = response.getAuthorityTuple().toBuilder();
      tuple
          .clearTenantAuthorityGeneration()
          .putAllTenantAuthorityGeneration(
              rekeyMap(
                  response.getAuthorityTuple().getTenantAuthorityGenerationMap(),
                  originalTenantId));
      tuple
          .clearMembershipAuthorityGeneration()
          .putAllMembershipAuthorityGeneration(
              rekeyMap(
                  response.getAuthorityTuple().getMembershipAuthorityGenerationMap(),
                  originalTenantId));
      for (int index = 0;
          index < response.getAuthorityTuple().getPrivateRealmGrantVersionsCount();
          index++) {
        var grant = response.getAuthorityTuple().getPrivateRealmGrantVersions(index);
        if (originalTenantId.equals(grant.getTenantId())) {
          tuple.setPrivateRealmGrantVersions(
              index, grant.toBuilder().setTenantId(CANONICAL_TENANT_UUID).build());
        }
      }
      builder.setAuthorityTuple(tuple.build());
    }
    builder.clearOutboxCheckpoints();
    response
        .getOutboxCheckpointsList()
        .forEach(
            checkpoint ->
                builder.addOutboxCheckpoints(
                    checkpoint.toBuilder()
                        .setOutboxStreamKey(
                            rekeyPathTenant(checkpoint.getOutboxStreamKey(), originalTenantId))
                        .build()));
    builder.clearOutboxSourceEvidence();
    response
        .getOutboxSourceEvidenceList()
        .forEach(
            source -> {
              String canonicalEventJson =
                  rekeyMembershipEvent(
                      source.getCanonicalEventJson(), response.getAccountId(), originalTenantId);
              var event = MembershipAuthorityEventV1Codec.verify(canonicalEventJson);
              builder.addOutboxSourceEvidence(
                  source.toBuilder()
                      .setOutboxStreamKey(event.outboxStreamKey())
                      .setEventDigest(event.eventDigest())
                      .setCanonicalEventJson(event.canonicalJson())
                      .build());
            });
    return builder.build();
  }

  private static Map<String, String> rekeyMap(Map<String, String> values, String originalTenantId) {
    Map<String, String> result = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          String rekeyed = originalTenantId.equals(key) ? CANONICAL_TENANT_UUID : key;
          if (result.putIfAbsent(rekeyed, value) != null) {
            throw new IllegalArgumentException("Fixture tenant UUID rekey collides");
          }
        });
    return result;
  }

  private static String rekeyPathTenant(String value, String originalTenantId) {
    return value.replace("/" + originalTenantId, "/" + CANONICAL_TENANT_UUID);
  }

  private static String rekeyMembershipEvent(
      String canonicalJson, String accountId, String originalTenantId) {
    try {
      ObjectNode event = (ObjectNode) JSON.readTree(canonicalJson);
      event.put("tenantId", CANONICAL_TENANT_UUID);
      event.put(
          "outboxStreamKey",
          rekeyPathTenant(event.path("outboxStreamKey").asText(), originalTenantId));
      event.put("sourceScope", "membership/" + accountId + "/" + CANONICAL_TENANT_UUID);
      rekeyJsonMap((ObjectNode) event.get("membershipVersion"), originalTenantId);
      ObjectNode tuple = (ObjectNode) event.get("authorityTuple");
      rekeyJsonMap((ObjectNode) tuple.get("tenantAuthorityGeneration"), originalTenantId);
      rekeyJsonMap((ObjectNode) tuple.get("membershipAuthorityGeneration"), originalTenantId);
      if (tuple.has("privateRealmGrantVersions")) {
        for (var grant : tuple.withArray("privateRealmGrantVersions")) {
          if (grant instanceof ObjectNode grantObject
              && originalTenantId.equals(grantObject.path("tenantId").asText())) {
            grantObject.put("tenantId", CANONICAL_TENANT_UUID);
          }
        }
      }
      event.remove("eventDigest");
      return MembershipAuthorityEventV1Codec.seal(
              JSON.convertValue(event, new TypeReference<Map<String, Object>>() {}))
          .canonicalJson();
    } catch (IOException ex) {
      throw new AssertionError("test event should be valid JSON", ex);
    }
  }

  private static void rekeyJsonMap(ObjectNode values, String originalTenantId) {
    if (values != null && values.has(originalTenantId)) {
      var value = values.remove(originalTenantId);
      values.set(CANONICAL_TENANT_UUID, value);
    }
  }
}
