package unit.net.firedevops.firemud.gamesession.command.text;

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
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.command.text.LoginCommandConstants;
import net.firedevops.firemud.gamesession.command.text.LoginCommandHandler;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.LookTextRenderer;
import net.firedevops.firemud.gamesession.command.text.SayCommandHandler;
import net.firedevops.firemud.gamesession.command.text.SayCommandHandlingResult;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamelogic.v1.LookResult;
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
  private final DevIsolatedProperties devIsolatedProperties = new DevIsolatedProperties(false);
  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);
  private final LookCommandHandler lookHandler =
      new LookCommandHandler(
          gameLogicClient,
          lookTextRenderer,
          sessionAuthenticationService,
          gameLogicProperties,
          meterRegistry,
          lookCacheService,
          devIsolatedProperties);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final SayCommandHandler sayHandler = Mockito.mock(SayCommandHandler.class);
  private final ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedRegistryProvider =
      Mockito.mock(ObjectProvider.class);
  private LoginCommandHandler loginHandler;
  private TextCommandInterpreter interpreter;
  private final SessionContext sessionContext =
      new SessionContext(1L, 22L, 123L, 911L, 0L, "jwt-token");

  @BeforeEach
  void setUp() {
    meterRegistry.clear();
    when(accountClient.authenticate(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            net.firedevops.firemud.account.v1.AuthenticateResponse.newBuilder()
                .setAuthToken("auth")
                .setAccountId("123")
                .build());
    when(devIsolatedRegistryProvider.getIfAvailable()).thenReturn(null);
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    loginHandler =
        new LoginCommandHandler(
            gameInstanceRepository,
            sessionContextService,
            accountClient,
            commandService,
            devIsolatedProperties,
            devIsolatedRegistryProvider,
            meterRegistry);
    GameInstance demoInstance = new GameInstance();
    demoInstance.setId(1L);
    demoInstance.setTenantId(22L);
    demoInstance.setOwnerAccountId(123L);
    when(gameInstanceRepository.findById(Mockito.anyLong())).thenReturn(Optional.of(demoInstance));
    when(sessionAuthenticationService.isAuthenticated(Mockito.anyString())).thenReturn(true);
    when(sessionAuthenticationService.resolveSessionContext("123"))
        .thenReturn(Optional.of(sessionContext));
    interpreter =
        new TextCommandInterpreter(
            commandService, lookHandler, loginHandler, sessionAuthenticationService, sayHandler);
  }

  @Test
  void enqueuesKnownCommand() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    TextCommandInterpretationResult interpretation = interpreter.interpret("123", "LOOK", false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("123", "LOOK", false);
  }

  @Test
  void lookErrorReturnsFailure() {
    LookCommandHandler mockLookHandler = Mockito.mock(LookCommandHandler.class);
    TextCommandInterpreter interpreterWithMockLook =
        new TextCommandInterpreter(
            commandService,
            mockLookHandler,
            loginHandler,
            sessionAuthenticationService,
            sayHandler);
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(CommandEnqueueResult.success());
    when(mockLookHandler.describe("123")).thenReturn("ERROR ROOM_NOT_FOUND mysterious room");

    TextCommandInterpretationResult interpretation =
        interpreterWithMockLook.interpret("123", "LOOK", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("ROOM_NOT_FOUND", interpretation.commandResult().errorCode());
    assertEquals("mysterious room", interpretation.commandResult().errorMessage());
    assertNull(interpretation.responseText());
  }

  @Test
  void enqueuesKnownTextCommand() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", command, false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("123", "LOOK", false);
  }

  @Test
  void lookCommandReturnsDescription() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    TextCommand command = new TextCommand(TextCommandType.LOOK, List.of(), "LOOK");
    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .build();
    when(gameLogicClient.resolveLook("22", "1", "911", "1021")).thenReturn(lookResult);
    when(lookTextRenderer.render(lookResult)).thenReturn("OK LOOK constructed");
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", command, false);

    assertEquals("OK LOOK constructed", interpretation.responseText());
  }

  @Test
  void sayCommandDelegatesToHandler() {
    SayCommandHandlingResult sayResult =
        new SayCommandHandlingResult(CommandEnqueueResult.success(), "OK SAY text");
    when(sayHandler.handle(Mockito.anyString(), Mockito.any(TextCommand.class)))
        .thenReturn(sayResult);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("123", "SAY Hello", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals("OK SAY text", interpretation.responseText());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void unknownCommandReturnsFailureAndDoesNotEnqueue() {
    TextCommandInterpretationResult interpretation =
        interpreter.interpret("123", "dance wildly", false);
    CommandEnqueueResult result = interpretation.commandResult();

    assertFalse(result.accepted());
    assertEquals("UNKNOWN_COMMAND", result.errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void blankCommandIsIgnored() {
    TextCommand noOp = new TextCommand(TextCommandType.NOOP, List.of(), "   ");

    TextCommandInterpretationResult interpretation = interpreter.interpret("123", noOp, false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void loginWithCredentialsBypassesCommandQueue() {
    TextCommandInterpretationResult interpretation =
        interpreter.interpret("123", "LOGIN demo demo", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals("Logged in as demo", interpretation.responseText());
    verify(commandService).enqueue("123", "LOGIN demo demo", false);
  }

  @Test
  void loginWithoutCredentialsPromptsError() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", "LOGIN", false);
    CommandEnqueueResult result = interpretation.commandResult();

    assertFalse(result.accepted());
    assertEquals(LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE, result.errorCode());
    assertEquals(LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_MESSAGE, result.errorMessage());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void gameplayCommandRequiresAuthentication() {
    when(sessionAuthenticationService.isAuthenticated("321")).thenReturn(false);

    TextCommandInterpretationResult interpretation = interpreter.interpret("321", "LOOK", false);

    CommandEnqueueResult result = interpretation.commandResult();
    assertFalse(result.accepted());
    assertEquals("NOT_AUTHENTICATED", result.errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void gameplayCommandAllowedWhenAuthenticated() {
    when(sessionAuthenticationService.isAuthenticated("999")).thenReturn(true);
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("999", "LOOK", false)).thenReturn(success);

    TextCommandInterpretationResult interpretation = interpreter.interpret("999", "LOOK", false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("999", "LOOK", false);
  }
}
