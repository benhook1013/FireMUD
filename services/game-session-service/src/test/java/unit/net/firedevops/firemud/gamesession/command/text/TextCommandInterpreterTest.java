package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
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
class TextCommandInterpreterTest {
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final LookTextRenderer lookTextRenderer = Mockito.mock(LookTextRenderer.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final GameSessionProperties gameSessionProperties = new GameSessionProperties();
  private final DevIsolatedProperties devIsolatedProperties = new DevIsolatedProperties(false);
  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final SessionContextService sessionContextService = new InMemorySessionContextService();
  private SessionAuthenticationService sessionAuthenticationService;
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final MoveCommandHandler moveHandler = Mockito.mock(MoveCommandHandler.class);
  private final CommunicationCommandHandler communicationHandler =
      Mockito.mock(CommunicationCommandHandler.class);
  private final ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedRegistryProvider =
      Mockito.mock(ObjectProvider.class);
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    meterRegistry.clear();
    when(accountClient.authenticate(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAuthToken("auth-token")
                .setAccountId("123")
                .build());
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
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
    when(devIsolatedRegistryProvider.getIfAvailable()).thenReturn(null);
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    when(gameInstanceRepository.findById(Mockito.anyLong()))
        .thenAnswer(
            invocation -> {
              long sessionId = invocation.getArgument(0);
              GameInstance instance = new GameInstance();
              instance.setId(sessionId);
              instance.setTenantId(22L);
              instance.setOwnerAccountId(123L);
              return Optional.of(instance);
            });

    sessionAuthenticationService =
        new SessionAuthenticationService(
            sessionContextService,
            gameSessionProperties,
            gameInstanceRepository,
            devIsolatedProperties,
            devIsolatedRegistryProvider);

    LoginCommandHandler loginHandler =
        new LoginCommandHandler(
            gameInstanceRepository,
            sessionContextService,
            accountClient,
            commandService,
            devIsolatedProperties,
            devIsolatedRegistryProvider,
            meterRegistry);
    GameplayWorldCatalog worldCatalog = new GameplayWorldCatalog(gameSessionProperties);
    PlayCommandHandler playHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            worldCatalog,
            gameLogicProperties,
            accountClient,
            meterRegistry);
    LookCommandHandler lookHandler =
        new LookCommandHandler(
            gameLogicClient,
            lookTextRenderer,
            sessionAuthenticationService,
            gameLogicProperties,
            meterRegistry,
            lookCacheService,
            devIsolatedProperties);
    WorldsCommandHandler worldsHandler = new WorldsCommandHandler(worldCatalog);

    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .build();
    when(gameLogicClient.resolveLook("22", "1", "123", "1021")).thenReturn(lookResult);
    when(lookTextRenderer.render(lookResult)).thenReturn("OK LOOK constructed");

    interpreter =
        new TextCommandInterpreter(
            commandService,
            lookHandler,
            loginHandler,
            playHandler,
            moveHandler,
            sessionAuthenticationService,
            communicationHandler,
            worldsHandler);
  }

  @Test
  void worldsAreVisibleBeforeLogin() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", "WORLDS", false);

    assertTrue(interpretation.commandResult().accepted());
    assertTrue(interpretation.protocolResponse());
    assertTrue(interpretation.responseText().contains("OK WORLDS"));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void gameplayBeforeLoginReturnsLoginRequired() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("321", "LOOK", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    assertNull(interpretation.responseText());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void bootstrapContextWithoutAuthenticatedAccountStillRequiresLogin() {
    ((InMemorySessionContextService) sessionContextService)
        .save(new SessionContext(55L, 22L, 0L, null, 0L, null, 77L, null, null));

    TextCommandInterpretationResult interpretation = interpreter.interpret("55", "LOOK", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    verify(commandService, never()).enqueue("55", "LOOK", false);
  }

  @Test
  void gameplayAfterLoginBeforePlayReturnsPlayRequired() {
    TextCommandInterpretationResult login =
        interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);

    assertTrue(login.commandResult().accepted());
    assertEquals("Logged in as demo@example.com", login.responseText());
    SessionContext authenticated =
        sessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    assertNull(authenticated.roomInstanceId());

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "LOOK", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("PLAY_REQUIRED", interpretation.commandResult().errorCode());
    assertNull(interpretation.responseText());
    verify(commandService, never()).enqueue("1", "LOOK", false);
  }

  @Test
  void movementAfterLoginBeforePlayReturnsPlayRequired() {
    TextCommandInterpretationResult login =
        interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);

    assertTrue(login.commandResult().accepted());

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "MOVE north", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("PLAY_REQUIRED", interpretation.commandResult().errorCode());
    verify(moveHandler, never())
        .handle(
            Mockito.any(net.firedevops.firemud.gamesession.service.SessionContext.class),
            Mockito.any(TextCommand.class));
  }

  @Test
  void loginPlayAndLookFlowWorks() {
    TextCommandInterpretationResult login =
        interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    TextCommandInterpretationResult play = interpreter.interpret("1", "PLAY demo", false);
    TextCommandInterpretationResult look = interpreter.interpret("1", "LOOK", false);

    assertTrue(login.commandResult().accepted());
    assertTrue(play.commandResult().accepted());
    assertEquals("OK PLAY Entered world: demo", play.responseText());
    assertTrue(look.commandResult().accepted());
    assertEquals("OK LOOK constructed", look.responseText());
    verify(commandService).enqueue("1", "LOGIN demo@example.com swordfish", false);
    verify(commandService).enqueue("1", "LOOK", false);
  }

  @Test
  void sayAfterPlayDelegatesToHandler() {
    TextCommandInterpretationResult login =
        interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    TextCommandInterpretationResult play = interpreter.interpret("1", "PLAY demo", false);
    assertTrue(login.commandResult().accepted());
    assertTrue(play.commandResult().accepted());

    SessionContext played = sessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    when(communicationHandler.handle(Mockito.eq(played), Mockito.any(TextCommand.class)))
        .thenReturn(
            new CommunicationCommandHandlingResult(CommandEnqueueResult.success(), "OK SAY text"));

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "SAY Hello there", false);

    assertTrue(interpretation.commandResult().accepted());
    assertTrue(interpretation.protocolResponse());
    assertEquals("OK SAY text", interpretation.responseText());
    verify(communicationHandler).handle(Mockito.eq(played), Mockito.any(TextCommand.class));
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
