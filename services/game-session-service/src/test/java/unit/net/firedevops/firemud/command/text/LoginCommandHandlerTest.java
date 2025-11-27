package unit.net.firedevops.firemud.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.client.AccountClient;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.command.text.LoginCommandConstants;
import net.firedevops.firemud.command.text.LoginCommandHandler;
import net.firedevops.firemud.command.text.LoginCommandHandlingResult;
import net.firedevops.firemud.command.text.TextCommand;
import net.firedevops.firemud.command.text.TextCommandType;
import net.firedevops.firemud.config.GameSessionProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.SessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LoginCommandHandlerTest {
  private static final String AUTH_TOKEN = "mock-jwt";

  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final GameSessionProperties properties = new GameSessionProperties();
  private LoginCommandHandler handler;

  @BeforeEach
  void setUp() {
    when(accountClient.authenticate(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(AuthenticateResponse.newBuilder().setAuthToken(AUTH_TOKEN).build());
    handler =
        new LoginCommandHandler(
            commandService,
            gameInstanceRepository,
            sessionContextService,
            accountClient,
            properties);
  }

  @Test
  void parameterizedLoginEnqueuesCommand() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    TextCommand command =
        new TextCommand(TextCommandType.LOGIN, List.of("demo@example.com", "swordfish"), "LOGIN demo@example.com swordfish");
    when(commandService.enqueue(anyString(), anyString(), anyBoolean())).thenReturn(success);

    LoginCommandHandlingResult result = handler.handle("session-1", command, false);

    assertTrue(result.commandResult().accepted());
    assertNull(result.responseText());
    verify(commandService).enqueue("session-1", command.rawLine(), false);
  }

  @Test
  void missingCredentialsReturnsPromptError() {
    TextCommand command =
        new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");

    LoginCommandHandlingResult result = handler.handle("session-1", command, true);

    assertFalse(result.commandResult().accepted());
    assertEquals(
        LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE, result.commandResult().errorCode());
    assertEquals(
        LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_MESSAGE,
        result.commandResult().errorMessage());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void successfulLoginStoresSessionContext() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    TextCommand command =
        new TextCommand(TextCommandType.LOGIN, List.of("demo@example.com", "swordfish"), "LOGIN demo@example.com swordfish");
    when(commandService.enqueue(anyString(), anyString(), anyBoolean())).thenReturn(success);

    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    handler.handle("1", command, false);

    ArgumentCaptor<net.firedevops.firemud.service.SessionContext> captor =
        ArgumentCaptor.forClass(net.firedevops.firemud.service.SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    net.firedevops.firemud.service.SessionContext context = captor.getValue();
    assertEquals(1L, context.sessionId());
    assertEquals(22L, context.tenantId());
    assertEquals(77L, context.accountId());
    assertEquals(77L, context.playerId());
    assertEquals(1L, context.gameInstanceId());
    assertEquals(AUTH_TOKEN, context.jwt());
  }

  @Test
  void invalidCredentialsDoesNotSaveContext() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("UNAUTHENTICATED")
                    .setMessage("Invalid credentials")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(TextCommandType.LOGIN, List.of("demo@example.com", "swordfish"), "LOGIN demo@example.com swordfish");
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("session-1", command, false);

    assertFalse(result.commandResult().accepted());
    verify(sessionContextService, never()).save(any());
  }

  @Test
  void accountErrorMapsToErrorDetailCode() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("UNAUTHENTICATED")
                    .setMessage("Invalid 2FA code")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(TextCommandType.LOGIN, List.of("demo@example.com", "swordfish"), "LOGIN demo@example.com swordfish");
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("session-1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("OTP_REQUIRED", result.commandResult().errorCode());
  }

  @Test
  void repeatedLoginStillStoresContext() {
    CommandEnqueueResult success = CommandEnqueueResult.success();
    TextCommand command =
        new TextCommand(TextCommandType.LOGIN, List.of("demo@example.com", "swordfish"), "LOGIN demo@example.com swordfish");
    when(commandService.enqueue(anyString(), anyString(), anyBoolean())).thenReturn(success);

    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    handler.handle("1", command, false);
    handler.handle("1", command, false);

    verify(sessionContextService, times(2)).save(any());
    verify(accountClient, times(2))
        .authenticate(anyString(), anyString(), anyString(), anyString());
  }
}
