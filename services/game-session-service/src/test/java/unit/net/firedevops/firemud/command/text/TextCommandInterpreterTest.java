package unit.net.firedevops.firemud.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.client.AccountClient;
import net.firedevops.firemud.command.text.LookCommandHandler;
import net.firedevops.firemud.command.text.LoginCommandConstants;
import net.firedevops.firemud.command.text.LoginCommandHandler;
import net.firedevops.firemud.command.text.TextCommand;
import net.firedevops.firemud.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.command.text.TextCommandInterpreter;
import net.firedevops.firemud.command.text.TextCommandType;
import net.firedevops.firemud.config.GameSessionProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.SessionAuthenticationService;
import net.firedevops.firemud.service.SessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TextCommandInterpreterTest {
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final LookCommandHandler lookHandler = new LookCommandHandler();
  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final GameSessionProperties properties = new GameSessionProperties();
  private LoginCommandHandler loginHandler;
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(net.firedevops.firemud.account.v1.AuthenticateResponse.newBuilder().build());
    loginHandler =
        new LoginCommandHandler(
            commandService,
            gameInstanceRepository,
            sessionContextService,
            accountClient,
            properties);
    when(gameInstanceRepository.findById(Mockito.anyLong())).thenReturn(Optional.empty());
    when(sessionAuthenticationService.isAuthenticated(Mockito.anyString())).thenReturn(true);
    interpreter =
        new TextCommandInterpreter(
            commandService, lookHandler, loginHandler, sessionAuthenticationService);
  }

  @Test
  void enqueuesKnownCommand() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOOK", false)).thenReturn(success);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("123", "LOOK", false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("123", "LOOK", false);
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
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", command, false);

    assertEquals(LookCommandHandler.DEFAULT_ROOM_DESCRIPTION, interpretation.responseText());
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
  void loginWithCredentialsIsRoutedToCommandService() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    when(commandService.enqueue("123", "LOGIN demo demo", false)).thenReturn(success);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("123", "LOGIN demo demo", false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("123", "LOGIN demo demo", false);
  }

  @Test
  void loginWithoutCredentialsPromptsError() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", "LOGIN", false);
    CommandEnqueueResult result = interpretation.commandResult();

    assertFalse(result.accepted());
    assertEquals(LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE, result.errorCode());
    assertEquals(
        LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_MESSAGE, result.errorMessage());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void gameplayCommandRequiresAuthentication() {
    when(sessionAuthenticationService.isAuthenticated("321")).thenReturn(false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("321", "LOOK", false);

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

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("999", "LOOK", false);

    assertTrue(interpretation.commandResult().accepted());
    verify(commandService).enqueue("999", "LOOK", false);
  }
}
