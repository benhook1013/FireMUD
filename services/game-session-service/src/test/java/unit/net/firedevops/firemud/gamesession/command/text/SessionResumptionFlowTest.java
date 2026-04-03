package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.config.MovementProperties;
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
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.devisolated.DevIsolatedGameInstanceRegistry;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
class SessionResumptionFlowTest {
  private static final String LOGIN_PAYLOAD = "LOGIN demo@example.com swordfish";
  private static final String PLAY_PAYLOAD = "PLAY demo";
  private static final String LOOK_PAYLOAD = "LOOK";

  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final GameInstanceRepository instanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final GameSessionProperties properties = new GameSessionProperties();
  private final GameplayWorldCatalog worldCatalog = new GameplayWorldCatalog(properties);
  private final DevIsolatedProperties devIsolatedProperties = new DevIsolatedProperties(false);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final LookTextRenderer lookTextRenderer = Mockito.mock(LookTextRenderer.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private LookCommandHandler lookHandler;
  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);
  private final InMemorySessionContextService sessionContextService =
      new InMemorySessionContextService();
  private SessionAuthenticationService sessionAuthenticationService;
  private final ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedRegistryProvider =
      Mockito.mock(ObjectProvider.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private PlayCommandHandler playHandler;
  private final MoveCommandHandler moveHandler = Mockito.mock(MoveCommandHandler.class);
  private final CommunicationCommandHandler communicationHandler =
      Mockito.mock(CommunicationCommandHandler.class);
  private WorldsCommandHandler worldsHandler;
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    when(instanceRepository.findById(Mockito.anyLong()))
        .thenAnswer(
            invocation -> {
              long sessionId = invocation.getArgument(0);
              GameInstance perCall = new GameInstance();
              perCall.setId(sessionId);
              perCall.setTenantId(22L);
              perCall.setOwnerAccountId(77L);
              return Optional.of(perCall);
            });
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    when(accountClient.authenticate(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken("jwt").setAccountId("77").build());
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("77")
                .setTenantId("22")
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    sessionAuthenticationService =
        new SessionAuthenticationService(
            sessionContextService,
            properties,
            instanceRepository,
            devIsolatedProperties,
            devIsolatedRegistryProvider);
    when(devIsolatedRegistryProvider.getIfAvailable()).thenReturn(null);
    LoginCommandHandler loginHandler =
        new LoginCommandHandler(
            instanceRepository,
            sessionContextService,
            accountClient,
            commandService,
            firstPartyConnectContextRegistry,
            devIsolatedProperties,
            devIsolatedRegistryProvider,
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
            devIsolatedProperties,
            new TextPlayerOutputRenderer(new PresentationProperties()));
    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .build();
    when(gameLogicClient.resolveLook(
            anyString(), anyString(), anyString(), anyString(), anyString()))
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
                    "1021", "Resume Hall", "Short text", "Long text", true, List.of(), List.of())));
    playHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            worldCatalog,
            gameLogicProperties,
            accountClient,
            firstPartyConnectContextRegistry,
            meterRegistry);
    worldsHandler = new WorldsCommandHandler(worldCatalog);
    interpreter =
        new TextCommandInterpreter(
            commandService,
            lookHandler,
            loginHandler,
            playHandler,
            moveHandler,
            sessionAuthenticationService,
            communicationHandler,
            worldsHandler,
            new PromptComposer());
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

    assertEquals(1.0, meterRegistry.counter("gamesession.session.takeover").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.resume").count());
  }

  @Test
  void staleIdentityMappingFallsBackToFreshSession() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(77L);
    when(instanceRepository.findById(Mockito.anyLong()))
        .thenAnswer(
            invocation -> {
              long sessionId = invocation.getArgument(0);
              GameInstance perCall = new GameInstance();
              perCall.setId(sessionId);
              perCall.setTenantId(22L);
              perCall.setOwnerAccountId(77L);
              return Optional.of(perCall);
            });

    interpreter.interpret("1", command, false);
    interpreter.interpret(
        "1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"), false);
    sessionContextService.evictIdentity(22L, 1L, 77L);

    TextCommandInterpretationResult staleRetry = interpreter.interpret("2", command, false);

    assertTrue(staleRetry.commandResult().accepted());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.resume").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.takeover").count());
    assertTrue(sessionContextService.findByTenantAndSessionId(22L, 2L).isPresent());
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
}
