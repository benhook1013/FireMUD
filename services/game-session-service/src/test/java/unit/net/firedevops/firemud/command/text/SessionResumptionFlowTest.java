package unit.net.firedevops.firemud.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.client.AccountClient;
import net.firedevops.firemud.client.GameLogicClient;
import net.firedevops.firemud.command.text.LoginCommandHandler;
import net.firedevops.firemud.command.text.LookCommandHandler;
import net.firedevops.firemud.command.text.LookTextRenderer;
import net.firedevops.firemud.command.text.SayCommandHandler;
import net.firedevops.firemud.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.config.DevIsolatedProperties;
import net.firedevops.firemud.config.GameLogicProperties;
import net.firedevops.firemud.config.GameSessionProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.SessionAuthenticationService;
import net.firedevops.firemud.service.SessionContext;
import net.firedevops.firemud.service.SessionContextService;
import net.firedevops.firemud.service.devisolated.DevIsolatedGameInstanceRegistry;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
class SessionResumptionFlowTest {
  private static final String LOGIN_PAYLOAD = "LOGIN demo@example.com swordfish";
  private static final String LOOK_PAYLOAD = "LOOK";

  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final GameInstanceRepository instanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final GameSessionProperties properties = new GameSessionProperties();
  private final DevIsolatedProperties devIsolatedProperties = new DevIsolatedProperties(false);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final LookTextRenderer lookTextRenderer = Mockito.mock(LookTextRenderer.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private LookCommandHandler lookHandler;
  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);
  private final SessionContextService sessionContextService = new InMemorySessionContextService();
  private SessionAuthenticationService sessionAuthenticationService;
  private final ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedRegistryProvider =
      Mockito.mock(ObjectProvider.class);
  private final SayCommandHandler sayHandler = Mockito.mock(SayCommandHandler.class);
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
            devIsolatedProperties,
            devIsolatedRegistryProvider,
            meterRegistry);
    lookHandler =
        new LookCommandHandler(
            gameLogicClient,
            lookTextRenderer,
            sessionAuthenticationService,
            gameLogicProperties,
            meterRegistry,
            lookCacheService,
            devIsolatedProperties);
    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .build();
    when(gameLogicClient.resolveLook(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(lookResult);
    when(lookTextRenderer.render(lookResult)).thenReturn("OK LOOK text");
    interpreter =
        new TextCommandInterpreter(
            commandService, lookHandler, loginHandler, sessionAuthenticationService, sayHandler);
  }

  @Test
  void secondConnectionResumesAndContinuesLookFlow() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());

    TextCommandInterpretationResult firstLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());
    assertEquals("OK LOOK text", firstLook.responseText());

    TextCommandInterpretationResult secondLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(secondLogin.commandResult().accepted());

    TextCommandInterpretationResult secondLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(secondLook.commandResult().accepted());
    assertEquals("OK LOOK text", secondLook.responseText());

    assertEquals(1.0, meterRegistry.counter("gamesession.session.resume").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.takeover").count());
  }

  @Test
  void secondConnectionTakesOverAndFirstConnectionIsUnauthenticated() {
    TextCommandInterpretationResult firstLogin = interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());
    TextCommandInterpretationResult firstLook = interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());

    TextCommandInterpretationResult secondLogin = interpreter.interpret("2", LOGIN_PAYLOAD, false);
    assertTrue(secondLogin.commandResult().accepted());
    TextCommandInterpretationResult secondLook = interpreter.interpret("2", LOOK_PAYLOAD, false);
    assertTrue(secondLook.commandResult().accepted());
    assertEquals("OK LOOK text", secondLook.responseText());

    TextCommandInterpretationResult firstLookAfterTakeover =
        interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertFalse(firstLookAfterTakeover.commandResult().accepted());
    assertEquals("NOT_AUTHENTICATED", firstLookAfterTakeover.commandResult().errorCode());

    assertEquals(1.0, meterRegistry.counter("gamesession.session.takeover").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.resume").count());
  }

  private static final class InMemorySessionContextService implements SessionContextService {
    private final Map<Long, SessionContext> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> identityMap = new ConcurrentHashMap<>();

    @Override
    public void save(SessionContext context) {
      sessionMap.put(context.sessionId(), context);
      identityMap.put(identityKey(context), context);
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
    public Optional<SessionContext> findByAccountAndPlayer(
        long tenantId, long accountId, long playerId) {
      return Optional.ofNullable(identityMap.get(identityKey(tenantId, accountId, playerId)));
    }

    @Override
    public void deleteBySessionId(long tenantId, long sessionId) {
      SessionContext removed = sessionMap.remove(sessionId);
      if (removed != null) {
        identityMap.remove(identityKey(removed));
      }
    }

    private String identityKey(SessionContext context) {
      return identityKey(context.tenantId(), context.accountId(), context.playerId());
    }

    private String identityKey(long tenantId, long accountId, long playerId) {
      return tenantId + ":" + accountId + ":" + playerId;
    }
  }
}
