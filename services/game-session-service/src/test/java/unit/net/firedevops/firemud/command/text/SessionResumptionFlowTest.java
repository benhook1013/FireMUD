package unit.net.firedevops.firemud.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.client.AccountClient;
import net.firedevops.firemud.command.text.LookCommandHandler;
import net.firedevops.firemud.command.text.LoginCommandHandler;
import net.firedevops.firemud.command.text.TextCommand;
import net.firedevops.firemud.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.command.text.TextCommandType;
import net.firedevops.firemud.config.GameSessionProperties;
import net.firedevops.firemud.config.LogOnlyProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.SessionAuthenticationService;
import net.firedevops.firemud.service.SessionContext;
import net.firedevops.firemud.service.SessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionResumptionFlowTest {
  private static final String LOGIN_PAYLOAD = "LOGIN demo@example.com swordfish";
  private static final String LOOK_PAYLOAD = "LOOK";

  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final GameInstanceRepository instanceRepository = Mockito.mock(GameInstanceRepository.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final GameSessionProperties properties = new GameSessionProperties();
  private final LogOnlyProperties logOnlyProperties = new LogOnlyProperties(false);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LookCommandHandler lookHandler = new LookCommandHandler();
  private final SessionContextService sessionContextService = new InMemorySessionContextService();
  private SessionAuthenticationService sessionAuthenticationService;
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(77L);
    when(instanceRepository.findById(Mockito.anyLong())).thenReturn(Optional.of(instance));
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    when(accountClient.authenticate(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken("jwt").setAccountId("77").build());
    sessionAuthenticationService =
        new SessionAuthenticationService(sessionContextService, properties);
    LoginCommandHandler loginHandler =
        new LoginCommandHandler(
            commandService,
            instanceRepository,
            sessionContextService,
            accountClient,
            logOnlyProperties,
            meterRegistry);
    interpreter =
        new TextCommandInterpreter(
            commandService, lookHandler, loginHandler, sessionAuthenticationService);
  }

  @Test
  void secondConnectionResumesAndContinuesLookFlow() {
    TextCommandInterpretationResult firstLogin =
        interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(firstLogin.commandResult().accepted());

    TextCommandInterpretationResult firstLook =
        interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(firstLook.commandResult().accepted());
    assertEquals(LookCommandHandler.DEFAULT_ROOM_DESCRIPTION, firstLook.responseText());

    TextCommandInterpretationResult secondLogin =
        interpreter.interpret("1", LOGIN_PAYLOAD, false);
    assertTrue(secondLogin.commandResult().accepted());

    TextCommandInterpretationResult secondLook =
        interpreter.interpret("1", LOOK_PAYLOAD, false);
    assertTrue(secondLook.commandResult().accepted());
    assertEquals(LookCommandHandler.DEFAULT_ROOM_DESCRIPTION, secondLook.responseText());

    assertEquals(1.0, meterRegistry.counter("gamesession.session.resume").count());
    assertEquals(0.0, meterRegistry.counter("gamesession.session.takeover").count());
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
    public Optional<SessionContext> findBySessionId(long sessionId) {
      return Optional.ofNullable(sessionMap.get(sessionId));
    }

    @Override
    public Optional<SessionContext> findByAccountAndPlayer(long tenantId, long accountId, long playerId) {
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
