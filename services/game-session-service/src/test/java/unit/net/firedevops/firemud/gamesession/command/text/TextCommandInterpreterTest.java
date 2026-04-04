package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
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
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedRegistryProvider =
      Mockito.mock(ObjectProvider.class);
  private final TextPlayerOutputRenderer outputRenderer =
      new TextPlayerOutputRenderer(
          new PresentationProperties(
              "en-NZ",
              PresentationProperties.ColorMode.NONE,
              false,
              new PresentationProperties.Prompt(true, true, 150L)));
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
            firstPartyConnectContextRegistry,
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
            firstPartyConnectContextRegistry,
            meterRegistry);
    LookCommandHandler lookHandler =
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
    WorldsCommandHandler worldsHandler = new WorldsCommandHandler(worldCatalog);

    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .build();
    when(gameLogicClient.resolveLook(
            Mockito.eq("22"),
            Mockito.eq("1"),
            Mockito.anyString(),
            Mockito.eq("1021"),
            Mockito.anyString()))
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
                    "1021",
                    "Login Hall",
                    "Short text",
                    "Long text",
                    true,
                    java.util.List.of(),
                    java.util.List.of())));
    when(lookTextRenderer.toPlayerOutput(
            Mockito.eq(lookResult),
            Mockito.eq(false),
            Mockito.any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.class),
            Mockito.any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .class)))
        .thenReturn(
            PlayerOutput.view(
                new LookViewOutput(
                    "1021",
                    "Login Hall",
                    "Short text",
                    "Long text",
                    false,
                    java.util.List.of(),
                    java.util.List.of())));

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
  void worldsAreVisibleBeforeLogin() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("123", "WORLDS", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertTrue(renderedResponse("WORLDS", interpretation).startsWith("OK WORLDS\n1) Demo World"));
    assertTrue(renderedResponse("WORLDS", interpretation).contains("Demo World"));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void gameplayBeforeLoginReturnsLoginRequired() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("321", "LOOK", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    assertEquals(
        "ERROR LOGIN_REQUIRED You must LOGIN before gameplay commands.",
        renderedResponse("LOOK", interpretation));
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
    assertEquals(
        List.of(PlayerOutputKind.MESSAGE),
        login.outputs().stream().map(PlayerOutput::kind).toList());
    assertThat(renderedResponse("LOGIN demo@example.com swordfish", login))
        .isEqualTo("OK LOGIN\nLogged in as demo@example.com\n\n");
    SessionContext authenticated =
        sessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    assertNull(authenticated.roomInstanceId());

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "LOOK", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("PLAY_REQUIRED", interpretation.commandResult().errorCode());
    assertEquals(
        "ERROR PLAY_REQUIRED You must PLAY before in-world commands.",
        renderedResponse("LOOK", interpretation));
    verify(commandService, never()).enqueue("1", "LOOK", false);
  }

  @Test
  void unknownCommandReturnsStructuredErrorOutput() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "FROBULATE", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("UNKNOWN_COMMAND", interpretation.commandResult().errorCode());
    assertEquals(
        List.of(PlayerOutputKind.ERROR),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals(
        "ERROR UNKNOWN_COMMAND Unknown command", renderedResponse("FROBULATE", interpretation));
  }

  @Test
  void lookAfterPlayAppendsPromptOutput() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult look = interpreter.interpret("1", "LOOK", false);

    assertTrue(look.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        look.outputs().stream().map(PlayerOutput::kind).toList());
    LookViewOutput payload = (LookViewOutput) look.outputs().get(0).payload();
    assertEquals("Login Hall", payload.roomName());
    assertTrue(payload.includeLongDescription());
    assertEquals("demo> ", look.outputs().get(1).text());
  }

  @Test
  void quickLookAfterPlayUsesShortVariantAndAppendsPrompt() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult quickLook = interpreter.interpret("1", "QUICKLOOK", false);

    assertTrue(quickLook.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        quickLook.outputs().stream().map(PlayerOutput::kind).toList());
    LookViewOutput payload = (LookViewOutput) quickLook.outputs().get(0).payload();
    assertEquals("Login Hall", payload.roomName());
    assertFalse(payload.includeLongDescription());
    assertEquals("demo> ", quickLook.outputs().get(1).text());
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
  void directionalAliasBeforeLoginStillHitsInterpreterStageGate() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("321", "north", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    verify(moveHandler, never())
        .handle(
            Mockito.any(net.firedevops.firemud.gamesession.service.SessionContext.class),
            Mockito.any(TextCommand.class));
  }

  @Test
  void directionalAliasAfterPlayDelegatesToMoveHandler() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    SessionContext played = sessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    when(moveHandler.handle(Mockito.eq(played), Mockito.any(TextCommand.class)))
        .thenReturn(
            new MoveCommandHandlingResult(
                CommandEnqueueResult.success(),
                PlayerOutput.view(
                    new LookViewOutput(
                        "R-205",
                        "North Hall",
                        "North Hall text",
                        "Detailed north hall text",
                        true,
                        LookViewOutput.RefreshReason.MOVE_REFRESH,
                        java.util.List.of(),
                        java.util.List.of()))));

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "north", false);

    assertTrue(interpretation.commandResult().accepted());
    verify(moveHandler).handle(Mockito.eq(played), Mockito.any(TextCommand.class));
  }

  @Test
  void loginPlayAndLookFlowWorks() {
    TextCommandInterpretationResult login =
        interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    TextCommandInterpretationResult play = interpreter.interpret("1", "PLAY demo", false);
    TextCommandInterpretationResult look = interpreter.interpret("1", "LOOK", false);

    assertTrue(login.commandResult().accepted());
    assertTrue(play.commandResult().accepted());
    assertEquals("OK PLAY Entered world: demo\ndemo> ", renderedResponse("PLAY demo", play));
    assertTrue(look.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        look.outputs().stream().map(PlayerOutput::kind).toList());
    assertTrue(((LookViewOutput) look.outputs().get(0).payload()).includeLongDescription());
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
            new CommunicationCommandHandlingResult(
                CommandEnqueueResult.success(),
                List.of(PlayerOutput.message("You say, \"Hello there\""))));

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "SAY Hello there", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        "You say, \"Hello there\"\ndemo> ", renderedResponse("SAY Hello there", interpretation));
    verify(communicationHandler).handle(Mockito.eq(played), Mockito.any(TextCommand.class));
  }

  @Test
  void moveAfterPlayReturnsStructuredViewAndPrompt() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    SessionContext played = sessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    when(moveHandler.handle(Mockito.eq(played), Mockito.any(TextCommand.class)))
        .thenReturn(
            new MoveCommandHandlingResult(
                CommandEnqueueResult.success(),
                PlayerOutput.view(
                    new LookViewOutput(
                        "R-205",
                        "North Hall",
                        "North Hall text",
                        "Detailed north hall text",
                        true,
                        LookViewOutput.RefreshReason.MOVE_REFRESH,
                        java.util.List.of(),
                        java.util.List.of()))));

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "MOVE north", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(2, interpretation.outputs().size());
    assertEquals(
        net.firedevops.firemud.gamesession.presentation.PlayerOutputKind.VIEW,
        interpretation.outputs().get(0).kind());
    assertEquals(
        "North Hall text",
        ((LookViewOutput) interpretation.outputs().get(0).payload()).shortDescription());
    assertEquals(
        net.firedevops.firemud.gamesession.presentation.PlayerOutputKind.PROMPT,
        interpretation.outputs().get(1).kind());
    assertEquals("demo> ", interpretation.outputs().get(1).text());
    verify(moveHandler).handle(Mockito.eq(played), Mockito.any(TextCommand.class));
  }

  private String renderedResponse(
      String rawCommand, TextCommandInterpretationResult interpretation) {
    return outputRenderer.renderAll(
        new TextCommandParser().parse(rawCommand),
        interpretation.commandResult(),
        interpretation.outputs());
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
