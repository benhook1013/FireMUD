package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@SuppressWarnings("unchecked")
class LoginCommandHandlerTest {
  private static final String AUTH_TOKEN = "mock-jwt";

  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private SessionAuthenticationService sessionAuthenticationService;
  private LoginCommandHandler handler;

  @BeforeEach
  void setUp() {
    meterRegistry.clear();
    stubSessionContext(bootstrapShell(1L, 1L));
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken(AUTH_TOKEN).setAccountId("77").build());
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 1L)));
    sessionRoutingNormalizationService =
        new SessionRoutingNormalizationService(
            sessionContextService, gameplayAdmissionPointerAuthorityService);
    sessionAuthenticationService =
        new SessionAuthenticationService(
            sessionContextService,
            Mockito.mock(net.firedevops.firemud.gamesession.config.GameSessionProperties.class),
            sessionRoutingNormalizationService,
            gameplayPresenceLifecycleService);
    handler =
        new LoginCommandHandler(
            gameInstanceRepository,
            sessionContextService,
            sessionAuthenticationService,
            accountClient,
            commandService,
            firstPartyConnectContextRegistry,
            sessionRoutingNormalizationService,
            gameplayAdmissionPointerAuthorityService,
            gameplayPresenceLifecycleService,
            meterRegistry);
  }

  @Test
  void parameterizedLoginEnqueuesCommand() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertTrue(result.commandResult().accepted());
    assertEquals("Logged in as demo@example.com", joinedOutputText(result.outputs()));
    assertEquals(
        List.of(PlayerOutputKind.MESSAGE),
        result.outputs().stream().map(output -> output.kind()).toList());
    verify(accountClient).authenticate(eq("22"), eq("demo@example.com"), eq("swordfish"));
    verify(commandService).enqueue("1", command.rawLine(), false);
  }

  @Test
  void rejectsLegacyThreeArgumentLoginWithoutFallbackAuthentication() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish", "123456"),
            "LOGIN demo@example.com swordfish 123456");

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals(LoginCommandConstants.INVALID_ARGUMENTS_CODE, result.commandResult().errorCode());
    assertEquals(
        "ERROR LOGIN_ARGUMENTS_INVALID Use LOGIN <email> [secret].",
        joinedOutputText(result.outputs()));
    verify(accountClient, never()).authenticate(anyString(), anyString(), anyString());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
    verify(firstPartyConnectContextRegistry, never()).find(anyLong());
  }

  @Test
  void oneArgumentLoginRequestsNeutralEmailChallengeWithoutAuthenticating() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN, List.of("demo@example.com"), "LOGIN demo@example.com");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.requestEmailLoginOtp("22", "demo@example.com"))
        .thenReturn(RequestEmailLoginOtpResponse.newBuilder().setAccepted(true).build());

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertTrue(result.commandResult().accepted());
    assertEquals(
        LoginCommandConstants.EMAIL_LOGIN_CODE_MESSAGE, joinedOutputText(result.outputs()));
    verify(accountClient).requestEmailLoginOtp("22", "demo@example.com");
    verify(accountClient, never()).authenticate(anyString(), anyString(), anyString());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void oneArgumentLoginDoesNotExposeEmailChallengeFailures() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN, List.of("demo@example.com"), "LOGIN demo@example.com");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.requestEmailLoginOtp("22", "demo@example.com"))
        .thenReturn(
            RequestEmailLoginOtpResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode(AuthenticationErrorCodes.UNAVAILABLE)
                        .setMessage("challenge delivery failed"))
                .build());

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR UNAVAILABLE Authentication service unavailable", joinedOutputText(result.outputs()));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void invalidSessionIdReturnsInvalidArgument() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    LoginCommandHandlingResult result = handler.handle("session-1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_ARGUMENT", result.commandResult().errorCode());
    assertEquals(
        "ERROR INVALID_ARGUMENT sessionId must be numeric", joinedOutputText(result.outputs()));
    verify(gameInstanceRepository, never()).findById(anyLong());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void invalidSessionIdZeroReturnsInvalidArgument() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    LoginCommandHandlingResult result = handler.handle("0", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_ARGUMENT", result.commandResult().errorCode());
    assertEquals(
        "ERROR INVALID_ARGUMENT sessionId must be positive", joinedOutputText(result.outputs()));
    verify(gameInstanceRepository, never()).findById(anyLong());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void invalidSessionIdNegativeReturnsInvalidArgument() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    LoginCommandHandlingResult result = handler.handle("-1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_ARGUMENT", result.commandResult().errorCode());
    assertEquals(
        "ERROR INVALID_ARGUMENT sessionId must be positive", joinedOutputText(result.outputs()));
    verify(gameInstanceRepository, never()).findById(anyLong());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void bareLoginInvalidZeroSessionIdReturnsInvalidArgument() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");

    LoginCommandHandlingResult result = handler.handle("0", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_ARGUMENT", result.commandResult().errorCode());
    assertEquals(
        "ERROR INVALID_ARGUMENT sessionId must be positive", joinedOutputText(result.outputs()));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void invalidAccountIdZeroReturnsInvalidAccount() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken(AUTH_TOKEN).setAccountId("0").build());
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals(LoginCommandConstants.INVALID_ACCOUNT_CODE, result.commandResult().errorCode());
    assertEquals(
        "ERROR "
            + LoginCommandConstants.INVALID_ACCOUNT_CODE
            + " "
            + LoginCommandConstants.INVALID_ACCOUNT_MESSAGE,
        joinedOutputText(result.outputs()));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void invalidAccountIdNegativeReturnsInvalidAccount() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken(AUTH_TOKEN).setAccountId("-1").build());
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals(LoginCommandConstants.INVALID_ACCOUNT_CODE, result.commandResult().errorCode());
    assertEquals(
        "ERROR "
            + LoginCommandConstants.INVALID_ACCOUNT_CODE
            + " "
            + LoginCommandConstants.INVALID_ACCOUNT_MESSAGE,
        joinedOutputText(result.outputs()));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void invalidAccountIdMalformedReturnsInvalidAccount() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    when(accountClient.authenticate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAuthToken(AUTH_TOKEN)
                .setAccountId("not-a-number")
                .build());
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals(LoginCommandConstants.INVALID_ACCOUNT_CODE, result.commandResult().errorCode());
    assertEquals(
        "ERROR "
            + LoginCommandConstants.INVALID_ACCOUNT_CODE
            + " "
            + LoginCommandConstants.INVALID_ACCOUNT_MESSAGE,
        joinedOutputText(result.outputs()));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void missingGameInstanceReturnsSessionNotFound() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.empty());

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("SESSION_NOT_FOUND", result.commandResult().errorCode());
    assertEquals("ERROR SESSION_NOT_FOUND Session not found", joinedOutputText(result.outputs()));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void missingCredentialsReturnsPromptError() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    stubSessionContext(staleGameplayContextWithoutSelector(7L));

    LoginCommandHandlingResult result = handler.handle("1", command, true);

    assertFalse(result.commandResult().accepted());
    assertEquals(
        LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE, result.commandResult().errorCode());
    assertEquals(
        LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_MESSAGE,
        result.commandResult().errorMessage());
    assertEquals(
        "ERROR "
            + LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE
            + " "
            + LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_MESSAGE,
        joinedOutputText(result.outputs()));
    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService, times(2)).save(captor.capture());
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(staleGameplayContextWithoutSelector(7L), "STALE_ADMISSION_POINTER");
    assertClearedSessionContext(lastCaptured(captor), 0L, null, null);
  }

  @Test
  void bareLoginConsumesVerifiedFirstPartyContext() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    77L,
                    22L,
                    "demo",
                    "production",
                    1L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gateway-1")));
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertTrue(result.commandResult().accepted());
    assertEquals("Logged in as first-party account 77", joinedOutputText(result.outputs()));
    verify(accountClient, never()).authenticate(anyString(), anyString(), anyString());
    verify(commandService).enqueue("1", "LOGIN", false);
  }

  @Test
  void bareLoginFallsBackToPersistedFirstPartyContextWhenRegistryEntryIsMissing() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    SessionContext persisted =
        new SessionContext(
            1L,
            22L,
            77L,
            null,
            0L,
            null,
            0L,
            null,
            null,
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            null,
            "scope-persisted",
            "req-persisted");
    when(sessionContextService.findBySessionId(1L)).thenReturn(Optional.of(persisted));
    when(sessionContextService.findByTenantAndSessionId(22L, 1L))
        .thenReturn(Optional.of(persisted));
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertTrue(result.commandResult().accepted());
    assertEquals("Logged in as first-party account 77", joinedOutputText(result.outputs()));
    verify(commandService).enqueue("1", "LOGIN", false);
  }

  @Test
  void bareLoginRejectsIncompletePersistedFirstPartyContextWhenRegistryEntryIsMissing() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    SessionContext persisted =
        new SessionContext(
            1L,
            22L,
            77L,
            null,
            0L,
            null,
            0L,
            null,
            null,
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            null,
            "scope-persisted",
            null);
    when(sessionContextService.findBySessionId(1L)).thenReturn(Optional.of(persisted));
    when(sessionContextService.findByTenantAndSessionId(22L, 1L))
        .thenReturn(Optional.of(persisted));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("CONNECT_CONTEXT_INVALID", result.commandResult().errorCode());
    assertEquals(
        "ERROR CONNECT_CONTEXT_INVALID Connect context invalid",
        joinedOutputText(result.outputs()));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void bareLoginDoesNotFallBackToRawPersistedFirstPartyContextWhenTenantScopedSessionIsMissing() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    SessionContext rawOnly =
        new SessionContext(
            1L,
            22L,
            77L,
            null,
            0L,
            null,
            0L,
            null,
            null,
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            null,
            "scope-persisted",
            "req-persisted");
    when(sessionContextService.findBySessionId(1L)).thenReturn(Optional.of(rawOnly));
    when(sessionContextService.findByTenantAndSessionId(22L, 1L)).thenReturn(Optional.empty());
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals(
        LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE, result.commandResult().errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void bareLoginDoesNotProceedWhenCurrentAdmissionPointerTenantIsInvalid() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    77L,
                    0L,
                    "demo",
                    "production",
                    1L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gateway-1")));
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(buildInstance(1L, 0L, 77L)));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("CONNECT_CONTEXT_INVALID", result.commandResult().errorCode());
  }

  @Test
  void bareLoginDoesNotProceedWhenCurrentAdmissionPointerGameInstanceIdIsInvalid() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    77L,
                    22L,
                    "demo",
                    "production",
                    0L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gateway-1")));
    when(gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 0L, 1L)));
    when(gameInstanceRepository.findById(0L)).thenReturn(Optional.of(buildInstance(0L, 22L, 77L)));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("CONNECT_CONTEXT_INVALID", result.commandResult().errorCode());
  }

  @Test
  void bareLoginDoesNotProceedWhenCurrentAdmissionPointerWorldOrRealmIsMissing() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    77L,
                    22L,
                    " ",
                    "production",
                    1L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gateway-1")));
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(buildInstance(1L, 22L, 77L)));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("CONNECT_CONTEXT_INVALID", result.commandResult().errorCode());
  }

  @Test
  void bareLoginDoesNotProceedWhenCurrentAdmissionPointerRealmIsMissing() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    77L, 22L, "demo", " ", 1L, 1L, "scope-1", "jti-1", "req-1", "gateway-1")));
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(buildInstance(1L, 22L, 77L)));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("CONNECT_CONTEXT_INVALID", result.commandResult().errorCode());
  }

  @Test
  void bareLoginRejectsStalePointerVersion() {
    TextCommand command = new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    stubSessionContext(staleGameplayContext(1L));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    77L,
                    22L,
                    "demo",
                    "production",
                    1L,
                    0L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gateway-1")));
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("CONNECT_CONTEXT_INVALID", result.commandResult().errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    assertClearedSessionContext(captor.getValue(), 1L);
  }

  @Test
  void successfulLoginStoresSessionContext() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    handler.handle("1", command, false);

    ArgumentCaptor<net.firedevops.firemud.gamesession.service.SessionContext> captor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.service.SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    net.firedevops.firemud.gamesession.service.SessionContext context = captor.getValue();
    assertEquals(1L, context.sessionId());
    assertEquals(22L, context.tenantId());
    assertEquals(77L, context.accountId());
    assertEquals(0L, context.characterId());
    assertEquals(0L, context.gameInstanceId());
    assertNull(context.roomInstanceId());
    assertEquals(AUTH_TOKEN, context.jwt());
  }

  @Test
  void loginUsesBootstrappedGameInstanceInsteadOfTransportSessionId() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = buildInstance(99L, 22L, 77L);
    SessionContext bootstrapContext =
        new SessionContext(12345L, 22L, 0L, null, 0L, null, 99L, null, null);
    when(sessionContextService.findBySessionId(12345L)).thenReturn(Optional.of(bootstrapContext));
    when(sessionContextService.findByTenantAndSessionId(22L, 12345L))
        .thenReturn(Optional.of(bootstrapContext));
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("12345", command, false);

    assertTrue(result.commandResult().accepted());
    verify(gameInstanceRepository).findById(99L);
    verify(gameInstanceRepository, never()).findById(12345L);
    verify(commandService).enqueue("12345", command.rawLine(), false);
  }

  @Test
  void loginFailsClosedWhenNoBootstrapAuthorityExistsEvenIfTransportIdMatchesRuntime() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance collidingRuntime = buildInstance(12345L, 22L, 77L);
    when(gameInstanceRepository.findById(12345L)).thenReturn(Optional.of(collidingRuntime));

    LoginCommandHandlingResult result = handler.handle("12345", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("SESSION_NOT_FOUND", result.commandResult().errorCode());
    assertEquals("ERROR SESSION_NOT_FOUND Session not found", joinedOutputText(result.outputs()));
    verify(gameInstanceRepository, never()).findById(12345L);
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void reloginPreservesExistingGameplayBindingForSameSession() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(sessionContextService.findByTenantAndSessionId(22L, 1L))
        .thenReturn(
            Optional.of(
                new SessionContext(
                    1L,
                    22L,
                    77L,
                    "demo@example.com",
                    88L,
                    "Sora",
                    1L,
                    "R-2045",
                    "old-jwt",
                    "en-NZ",
                    1L,
                    "demo",
                    "production",
                    1L,
                    "SHARED",
                    "scope-live",
                    "req-live")));
    when(sessionContextService.findBySessionId(1L))
        .thenReturn(
            Optional.of(
                new SessionContext(
                    1L,
                    22L,
                    77L,
                    "demo@example.com",
                    88L,
                    "Sora",
                    1L,
                    "R-2045",
                    "old-jwt",
                    "en-NZ",
                    1L,
                    "demo",
                    "production",
                    1L,
                    "SHARED",
                    "scope-live",
                    "req-live")));

    handler.handle("1", command, false);

    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    SessionContext context = captor.getValue();
    assertEquals(1L, context.sessionId());
    assertEquals(22L, context.tenantId());
    assertEquals(77L, context.accountId());
    assertEquals(88L, context.characterId());
    assertEquals(1L, context.gameInstanceId());
    assertEquals("R-2045", context.roomInstanceId());
    assertEquals(AUTH_TOKEN, context.jwt());
  }

  @Test
  void reloginClearsStaleGameplayBindingBeforeRefreshingLoginContext() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(sessionContextService.findByTenantAndSessionId(22L, 1L))
        .thenReturn(Optional.of(staleGameplayContext(7L)));
    when(sessionContextService.findBySessionId(1L))
        .thenReturn(Optional.of(staleGameplayContext(7L)));

    handler.handle("1", command, false);

    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    SessionContext context = captor.getValue();
    assertEquals(1L, context.sessionId());
    assertEquals(22L, context.tenantId());
    assertEquals(77L, context.accountId());
    assertEquals(0L, context.characterId());
    assertNull(context.characterName());
    assertEquals(0L, context.gameInstanceId());
    assertNull(context.roomInstanceId());
    assertEquals(AUTH_TOKEN, context.jwt());
    assertEquals(1L, context.bootstrapGameInstanceId());
    assertNull(context.worldSlug());
    assertNull(context.realmSlug());
    assertEquals(0L, context.pointerVersion());
    assertNull(context.playableStateScope());
    assertEquals("scope-stale", context.connectScopeId());
    assertEquals("req-stale", context.connectRequestId());
  }

  @Test
  void invalidCredentialsClearsStaleAuthenticatedSessionState() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                    .setMessage("Invalid credentials")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    stubSessionContext(staleGameplayContext(3L));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_CREDENTIALS", result.commandResult().errorCode());
    assertEquals(
        "ERROR INVALID_CREDENTIALS Invalid credentials", joinedOutputText(result.outputs()));
    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService, times(2)).save(captor.capture());
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(staleGameplayContext(3L), "STALE_ADMISSION_POINTER");
    assertClearedSessionContext(lastCaptured(captor), 0L);
  }

  @Test
  void invalidCredentialsDropPartialProjectedRoutingBundleDuringFailedLoginCleanup() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                    .setMessage("Invalid credentials")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    SessionContext partialRoutingProjection =
        new SessionContext(
            1L,
            22L,
            77L,
            "demo@example.com",
            88L,
            "Sora",
            1L,
            "R-2045",
            AUTH_TOKEN,
            "en-NZ",
            1L,
            "demo",
            null,
            3L,
            "LIVE",
            "scope-stale",
            "req-stale");
    stubSessionContext(partialRoutingProjection);
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService, times(2)).save(captor.capture());
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(partialRoutingProjection, "STALE_ADMISSION_POINTER");
    assertClearedSessionContext(lastCaptured(captor), 0L);
  }

  @Test
  void accountMismatchReturnsFailure() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    stubSessionContext(staleGameplayContext(4L));
    when(accountClient.authenticate(anyString(), anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken(AUTH_TOKEN).setAccountId("99").build());

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("ACCOUNT_MISMATCH", result.commandResult().errorCode());
    assertEquals(
        "ERROR ACCOUNT_MISMATCH " + LoginCommandConstants.ACCOUNT_MISMATCH_MESSAGE,
        joinedOutputText(result.outputs()));
    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService, times(2)).save(captor.capture());
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(staleGameplayContext(4L), "STALE_ADMISSION_POINTER");
    assertClearedSessionContext(lastCaptured(captor), 0L);
  }

  @Test
  void accountErrorUsesCanonicalCode() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                    .setMessage("Invalid credentials")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("INVALID_CREDENTIALS", result.commandResult().errorCode());
    assertEquals(
        "ERROR INVALID_CREDENTIALS Invalid credentials", joinedOutputText(result.outputs()));
  }

  @Test
  void accountLockedUsesCanonicalCode() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode(AuthenticationErrorCodes.ACCOUNT_LOCKED)
                    .setMessage("Locked out")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("ACCOUNT_LOCKED", result.commandResult().errorCode());
    assertEquals("ERROR ACCOUNT_LOCKED Locked out", joinedOutputText(result.outputs()));
  }

  @Test
  void retryLaterUsesStableProtocolCodeAndLocalizedPresentation() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("AUTH_RETRY_LATER")
                    .setMessage("Account service retry detail")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("RETRY_LATER", result.commandResult().errorCode());
    assertEquals(
        "Too many failed attempts; try again later.", result.commandResult().errorMessage());
    assertEquals(
        "ERROR RETRY_LATER Too many failed attempts; try again later.",
        joinedOutputText(result.outputs()));
    assertEquals(
        "error.login.retry-later", ((ErrorOutput) result.outputs().get(0).payload()).messageKey());
  }

  @Test
  void abuseControlUnavailableUsesStableProtocolCodeAndLocalizedPresentation() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("AUTH_ABUSE_CONTROL_UNAVAILABLE")
                    .setMessage("Abuse control backend detail")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("ABUSE_CONTROL_UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "Login protection is temporarily unavailable. Try again later.",
        result.commandResult().errorMessage());
    assertEquals(
        "ERROR ABUSE_CONTROL_UNAVAILABLE Login protection is temporarily unavailable. "
            + "Try again later.",
        joinedOutputText(result.outputs()));
    assertEquals(
        "error.login.abuse-control-unavailable",
        ((ErrorOutput) result.outputs().get(0).payload()).messageKey());
  }

  @Test
  void unavailableReturnsUnavailableCode() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode(AuthenticationErrorCodes.UNAVAILABLE)
                    .setMessage("Backend unreachable")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UNAVAILABLE", result.commandResult().errorCode());
    assertEquals("Authentication service unavailable", result.commandResult().errorMessage());
    assertEquals(
        "ERROR UNAVAILABLE Authentication service unavailable", joinedOutputText(result.outputs()));
  }

  @Test
  void normalizedAuthenticationTransportFailureUsesUnavailable() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode(AuthenticationErrorCodes.UNAVAILABLE)
                    .setMessage("Authentication request failed")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR UNAVAILABLE Authentication service unavailable", joinedOutputText(result.outputs()));
  }

  @Test
  void unmappedAuthenticationCodeUsesUnavailablePublicVocabulary() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("AUTH_INTERNAL_ONLY")
                    .setMessage("Internal authentication detail")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR UNAVAILABLE Authentication service unavailable", joinedOutputText(result.outputs()));
  }

  @Test
  void unrecognizedAccountErrorUsesUnavailable() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder().setCode("").setMessage("Unrecognized failure").build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR UNAVAILABLE Authentication service unavailable", joinedOutputText(result.outputs()));
  }

  @Test
  void unrecognizedNonblankAuthenticationCodeUsesUnavailable() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("INTERNAL")
                    .setMessage("internal authentication detail")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR UNAVAILABLE Authentication service unavailable", joinedOutputText(result.outputs()));
  }

  @Test
  void unauthenticatedDoesNotInferCanonicalCodeFromMessage() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("UNAUTHENTICATED")
                    .setMessage("Invalid credentials")
                    .build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString())).thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UNAVAILABLE", result.commandResult().errorCode());
    assertEquals(
        "ERROR UNAVAILABLE Authentication service unavailable", joinedOutputText(result.outputs()));
  }

  @Test
  void repeatedLoginStillStoresContext() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    handler.handle("1", command, false);
    handler.handle("1", command, false);

    verify(sessionContextService, times(2))
        .save(any(net.firedevops.firemud.gamesession.service.SessionContext.class));
    verify(accountClient, times(2)).authenticate(anyString(), anyString(), anyString());
  }

  @Test
  void loginDoesNotTakeOverGameplayBindingBeforePlay() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(77L);
    when(sessionContextService.findBySessionId(2L)).thenReturn(Optional.of(bootstrapShell(2L, 2L)));
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    handler.handle("2", command, false);

    verify(sessionContextService, never()).deleteBySessionId(anyLong(), anyLong());
  }

  @Test
  void repeatedLoginRefreshesLoginContextWithoutGameplayResume() {
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");

    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    handler.handle("1", command, false);

    verify(sessionContextService, never()).deleteBySessionId(22L, 1L);
    verify(sessionContextService).save(any(SessionContext.class));
  }

  private GameInstance buildInstance(long id, long tenantId, long ownerAccountId) {
    GameInstance instance = new GameInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setOwnerAccountId(ownerAccountId);
    return instance;
  }

  private static String joinedOutputText(List<PlayerOutput> outputs) {
    return outputs.stream()
        .map(PlayerOutput::text)
        .filter(text -> text != null && !text.isBlank())
        .reduce((left, right) -> left + "\n" + right)
        .orElse(null);
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug, String realmSlug, long tenantId, long gameInstanceId, long pointerVersion) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldSlug,
        realmSlug,
        realmSlug,
        tenantId,
        gameInstanceId,
        pointerVersion,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW");
  }

  private static SessionContext bootstrapShell(long sessionId, long bootstrapGameInstanceId) {
    return new SessionContext(
        sessionId,
        22L,
        0L,
        null,
        0L,
        null,
        0L,
        null,
        null,
        "en-NZ",
        bootstrapGameInstanceId,
        "demo",
        "production",
        1L,
        null);
  }

  private static SessionContext staleGameplayContext(long pointerVersion) {
    return new SessionContext(
        1L,
        22L,
        77L,
        "demo@example.com",
        88L,
        "Sora",
        1L,
        "R-2045",
        AUTH_TOKEN,
        "en-NZ",
        1L,
        "demo",
        "production",
        pointerVersion,
        "LIVE",
        "scope-stale",
        "req-stale");
  }

  private static SessionContext staleGameplayContextWithoutSelector(long pointerVersion) {
    return new SessionContext(
        1L,
        22L,
        77L,
        "demo@example.com",
        88L,
        "Sora",
        1L,
        "R-2045",
        AUTH_TOKEN,
        "en-NZ",
        1L,
        "demo",
        "production",
        pointerVersion,
        "LIVE");
  }

  private static void assertClearedSessionContext(SessionContext context, long pointerVersion) {
    assertClearedSessionContext(context, pointerVersion, "scope-stale", "req-stale");
  }

  private static void assertClearedSessionContext(
      SessionContext context,
      long pointerVersion,
      String expectedConnectScopeId,
      String expectedConnectRequestId) {
    assertEquals(1L, context.sessionId());
    assertEquals(22L, context.tenantId());
    assertEquals(0L, context.accountId());
    assertNull(context.loginName());
    assertEquals(0L, context.characterId());
    assertNull(context.characterName());
    assertEquals(0L, context.gameInstanceId());
    assertNull(context.roomInstanceId());
    assertNull(context.jwt());
    assertEquals("en-NZ", context.localeTag());
    assertEquals(1L, context.bootstrapGameInstanceId());
    if (pointerVersion > 0) {
      assertEquals("demo", context.worldSlug());
      assertEquals("production", context.realmSlug());
      assertEquals(pointerVersion, context.pointerVersion());
    } else {
      assertNull(context.worldSlug());
      assertNull(context.realmSlug());
      assertEquals(0L, context.pointerVersion());
    }
    assertNull(context.playableStateScope());
    assertEquals(expectedConnectScopeId, context.connectScopeId());
    assertEquals(expectedConnectRequestId, context.connectRequestId());
  }

  private static SessionContext lastCaptured(ArgumentCaptor<SessionContext> captor) {
    return captor.getAllValues().get(captor.getAllValues().size() - 1);
  }

  private void stubSessionContext(SessionContext context) {
    when(sessionContextService.findBySessionId(context.sessionId()))
        .thenReturn(Optional.of(context));
    when(sessionContextService.findByTenantAndSessionId(context.tenantId(), context.sessionId()))
        .thenReturn(Optional.of(context));
  }
}
