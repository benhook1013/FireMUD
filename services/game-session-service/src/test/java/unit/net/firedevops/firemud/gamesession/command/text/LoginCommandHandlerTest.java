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
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.devisolated.DevIsolatedGameInstanceRegistry;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

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
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final GameplayWorldCatalog gameplayWorldCatalog =
      new GameplayWorldCatalog(gameplayCatalogProperties);
  private final DevIsolatedProperties devIsolatedProperties = new DevIsolatedProperties(false);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedRegistryProvider =
      Mockito.mock(ObjectProvider.class);
  private LoginCommandHandler handler;

  @BeforeEach
  void setUp() {
    meterRegistry.clear();
    gameplayCatalogProperties.setWorlds(
        List.of(world("demo", "Demo World", List.of(realm("production", "Live Realm", 22L, 1L)))));
    when(accountClient.authenticate(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken(AUTH_TOKEN).setAccountId("77").build());
    when(devIsolatedRegistryProvider.getIfAvailable()).thenReturn(null);
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    handler =
        new LoginCommandHandler(
            gameInstanceRepository,
            sessionContextService,
            accountClient,
            commandService,
            firstPartyConnectContextRegistry,
            gameplayWorldCatalog,
            devIsolatedProperties,
            devIsolatedRegistryProvider,
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
    verify(accountClient).authenticate(eq("22"), eq("demo@example.com"), eq("swordfish"), eq(""));
    verify(commandService).enqueue("1", command.rawLine(), false);
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
    verify(accountClient, never()).authenticate(anyString(), anyString(), anyString(), anyString());
    verify(commandService).enqueue("1", "LOGIN", false);
  }

  @Test
  void bareLoginRejectsStalePointerVersion() {
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
                    0L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gateway-1")));
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("CONNECT_SCOPE_MISMATCH", result.commandResult().errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
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
    when(sessionContextService.findBySessionId(12345L))
        .thenReturn(
            Optional.of(new SessionContext(12345L, 22L, 0L, null, 0L, null, 99L, null, null)));
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));

    LoginCommandHandlingResult result = handler.handle("12345", command, false);

    assertTrue(result.commandResult().accepted());
    verify(gameInstanceRepository).findById(99L);
    verify(gameInstanceRepository, never()).findById(12345L);
    verify(commandService).enqueue("12345", command.rawLine(), false);
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
            Optional.of(new SessionContext(1L, 22L, 77L, 88L, 99L, "room-2045", "old-jwt")));

    handler.handle("1", command, false);

    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    SessionContext context = captor.getValue();
    assertEquals(1L, context.sessionId());
    assertEquals(22L, context.tenantId());
    assertEquals(77L, context.accountId());
    assertEquals(88L, context.characterId());
    assertEquals(99L, context.gameInstanceId());
    assertEquals("room-2045", context.roomInstanceId());
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
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    verify(sessionContextService, never())
        .save(any(net.firedevops.firemud.gamesession.service.SessionContext.class));
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
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken(AUTH_TOKEN).setAccountId("99").build());

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("ACCOUNT_MISMATCH", result.commandResult().errorCode());
    assertEquals(
        "ERROR ACCOUNT_MISMATCH " + LoginCommandConstants.ACCOUNT_MISMATCH_MESSAGE,
        joinedOutputText(result.outputs()));
    verify(sessionContextService, never())
        .save(any(net.firedevops.firemud.gamesession.service.SessionContext.class));
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
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(authError);

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
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("ACCOUNT_LOCKED", result.commandResult().errorCode());
    assertEquals("ERROR ACCOUNT_LOCKED Locked out", joinedOutputText(result.outputs()));
  }

  @Test
  void upstreamFailureReturnsUpstreamFailureCode() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode(AuthenticationErrorCodes.UPSTREAM_FAILURE)
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
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("UPSTREAM_FAILURE", result.commandResult().errorCode());
    assertEquals("Backend unreachable", result.commandResult().errorMessage());
    assertEquals("ERROR UPSTREAM_FAILURE Backend unreachable", joinedOutputText(result.outputs()));
  }

  @Test
  void accountErrorFallbacksToMessageHeuristic() {
    AuthenticateResponse authError =
        AuthenticateResponse.newBuilder()
            .setError(ErrorDetail.newBuilder().setCode("").setMessage("Invalid 2FA code").build())
            .build();
    TextCommand command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("demo@example.com", "swordfish"),
            "LOGIN demo@example.com swordfish");
    GameInstance instance = buildInstance(1L, 22L, 77L);
    when(gameInstanceRepository.findById(1L)).thenReturn(Optional.of(instance));
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(authError);

    LoginCommandHandlingResult result = handler.handle("1", command, false);

    assertFalse(result.commandResult().accepted());
    assertEquals("OTP_REQUIRED", result.commandResult().errorCode());
    assertEquals("ERROR OTP_REQUIRED Invalid 2FA code", joinedOutputText(result.outputs()));
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
    verify(accountClient, times(2))
        .authenticate(anyString(), anyString(), anyString(), anyString());
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

  private static GameplayCatalogProperties.World world(
      String slug, String displayName, List<GameplayCatalogProperties.Realm> realms) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(displayName);
    world.setRealms(realms);
    return world;
  }

  private static GameplayCatalogProperties.Realm realm(
      String slug, String displayName, long tenantId, long gameInstanceId) {
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug(slug);
    realm.setDisplayName(displayName);
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setVisible(true);
    realm.setRequiresCharacterSelection(false);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    return realm;
  }
}
