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
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.client.SocialGroupsClient;
import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
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
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.gamesession.service.impl.DefaultGameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.impl.InMemoryGameplayPresenceService;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@SuppressWarnings("unchecked")
class TextCommandInterpreterTest {
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final LookTextRenderer lookTextRenderer = Mockito.mock(LookTextRenderer.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final GameSessionProperties gameSessionProperties = new GameSessionProperties();
  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final SessionContextService sessionContextService = new InMemorySessionContextService();
  private SessionAuthenticationService sessionAuthenticationService;
  private SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LookCacheService lookCacheService = Mockito.mock(LookCacheService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final ModerationPolicyClient moderationPolicyClient =
      Mockito.mock(ModerationPolicyClient.class);
  private final MoveCommandHandler moveHandler = Mockito.mock(MoveCommandHandler.class);
  private final HelpCommandHandler helpHandler = new HelpCommandHandler();
  private final InventoryCommandHandler inventoryHandler =
      new InventoryCommandHandler(gameLogicClient);
  private final EquipmentCommandHandler equipmentHandler =
      new EquipmentCommandHandler(gameLogicClient);
  private final ContainerCommandHandler containerHandler =
      new ContainerCommandHandler(gameLogicClient);
  private final CommunicationCommandHandler communicationHandler =
      Mockito.mock(CommunicationCommandHandler.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
  private final ScreenBufferService screenBufferService = Mockito.mock(ScreenBufferService.class);
  private final GameplayAdmissionPointerAuthorityService pointerAuthorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
  private final AccountRecentPresenceService accountRecentPresenceService =
      Mockito.mock(AccountRecentPresenceService.class);
  private final TextPlayerOutputRenderer outputRenderer =
      new TextPlayerOutputRenderer(
          new PresentationProperties(
              "en-NZ",
              PresentationProperties.ColorMode.NONE,
              false,
              new PresentationProperties.Prompt(true, true, 150L)));
  private final GameplayPresenceService gameplayPresenceService =
      new InMemoryGameplayPresenceService(
          new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L));
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      new DefaultGameplayPresenceLifecycleService(
          gameplayPresenceService,
          accountRecentPresenceService,
          sessionRoutingNormalizationService(),
          scriptEventPublisher);
  private final AuthoredActionCommandHandler authoredActionHandler =
      new AuthoredActionCommandHandler(
          new ConfiguredAuthoredActionCatalog(new AuthoredActionProperties()));
  private final TextCommandRegistry registry =
      new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider()));
  private final TextCommandParser parser = new TextCommandParser();
  private TextCommandInterpreter interpreter;

  @BeforeEach
  void setUp() {
    sessionContextService.save(bootstrapShell(1L, 1L));
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
    when(moderationPolicyClient.evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyLong()))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(true)
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
    when(gameLogicClient.queryInventory(Mockito.any(SessionContext.class)))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("ITEM-009")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(2)
                        .build())
                .build());
    when(gameLogicClient.listRoomGroundInventory(
            Mockito.any(SessionContext.class), Mockito.anyString()))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("ITEM-009")
                        .setItemInstanceId("ITEM-009")
                        .setItemName("Torch")
                        .build())
                .build());
    when(gameLogicClient.pickupItemFromRoom(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.eq(1)))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("ITEM-009")
                        .setItemName("Torch")
                        .setItemDescription("A battered key")
                        .setQuantity(1)
                        .build())
                .build());
    when(gameLogicClient.dropItemToRoom(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.eq(1)))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("ITEM-009")
                        .setItemName("Torch")
                        .setItemDescription("A battered key")
                        .setQuantity(1)
                        .build())
                .build());
    when(gameLogicClient.listEquipment(Mockito.any(SessionContext.class)))
        .thenReturn(
            ListEquipmentResponse.newBuilder()
                .addItems(
                    EquipmentItem.newBuilder()
                        .setSlot("HEAD")
                        .setItemId("ITEM-009")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .build())
                .build());
    when(gameLogicClient.wearEquipment(
            Mockito.any(SessionContext.class), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            WearEquipmentItemResponse.newBuilder()
                .setEquipmentItem(
                    EquipmentItem.newBuilder()
                        .setSlot("HEAD")
                        .setItemId("ITEM-009")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .build())
                .build());
    when(gameLogicClient.removeEquipment(Mockito.any(SessionContext.class), Mockito.anyString()))
        .thenReturn(
            RemoveEquipmentResponse.newBuilder()
                .setEquipmentItem(
                    EquipmentItem.newBuilder()
                        .setSlot("HEAD")
                        .setItemId("ITEM-009")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(
            Mockito.any(SessionContext.class), Mockito.anyString()))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    net.firedevops.firemud.entitymanagement.v1.ContainerItem.newBuilder()
                        .setContainerInstanceId("ITEM-009")
                        .setItemId("ITEM-010")
                        .setItemName("Ration")
                        .setItemDescription("A travel ration")
                        .setQuantity(1)
                        .build())
                .build());
    when(gameLogicClient.putItemIntoContainer(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.any(),
            Mockito.anyInt()))
        .thenReturn(PutItemIntoContainerResponse.newBuilder().build());
    when(gameLogicClient.takeItemFromContainer(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.any(),
            Mockito.anyInt()))
        .thenReturn(
            TakeItemFromContainerResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("ITEM-010")
                        .setItemName("Ration")
                        .setItemDescription("A travel ration")
                        .setQuantity(1)
                        .build())
                .build());
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    when(pointerAuthorityService.findPointer("demo", "production"))
        .thenReturn(Optional.of(pointer("demo", "production", 22L, 1L, 1L)));
    when(pointerAuthorityService.findPointer("sandbox", "production"))
        .thenReturn(Optional.of(pointer("sandbox", "production", 22L, 2L, 1L)));
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
            sessionRoutingNormalizationService(),
            gameplayPresenceLifecycleService);
    GameplayCatalogProperties gameplayCatalogProperties = new GameplayCatalogProperties();
    gameplayCatalogProperties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("sandbox", 22L, 2L, true)));
    GameplayWorldCatalog worldCatalog =
        TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties);
    LoginCommandHandler loginHandler =
        new LoginCommandHandler(
            gameInstanceRepository,
            sessionContextService,
            accountClient,
            commandService,
            firstPartyConnectContextRegistry,
            sessionRoutingNormalizationService(),
            pointerAuthorityService,
            gameplayPresenceLifecycleService,
            meterRegistry);
    PlayCommandHandler playHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService(),
            worldCatalog,
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry);
    AfkCommandHandler afkHandler =
        new AfkCommandHandler(sessionAuthenticationService, gameplayPresenceService);
    WhoCommandHandler whoHandler =
        new WhoCommandHandler(
            gameplayPresenceService,
            new GameplayPresenceActivityResolver(new PresenceProperties()),
            scriptEventPublisher);
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
            new TextPlayerOutputRenderer(new PresentationProperties()));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("7001")
                        .setName("Emberline")
                        .setLevel(12)
                        .build())
                .build());
    WorldsCommandHandler worldsHandler =
        new WorldsCommandHandler(worldCatalog, entityManagementClient);

    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .build();
    when(gameLogicClient.resolveLook(
            Mockito.any(SessionContext.class), Mockito.eq("1021"), Mockito.anyString()))
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
            new LogoutCommandHandler(
                sessionAuthenticationService,
                sessionContextService,
                gameInstanceService,
                pointerAuthorityService,
                gameplayPresenceLifecycleService,
                firstPartyConnectContextRegistry,
                screenBufferService,
                scriptEventPublisher),
            playHandler,
            moveHandler,
            afkHandler,
            helpHandler,
            whoHandler,
            new FriendsCommandHandler(
                Mockito.mock(SocialGroupsClient.class),
                entityManagementClient,
                scriptEventPublisher),
            authoredActionHandler,
            inventoryHandler,
            equipmentHandler,
            containerHandler,
            sessionAuthenticationService,
            scriptEventPublisher,
            communicationHandler,
            worldsHandler,
            new PromptComposer(),
            registry,
            parser,
            meterRegistry);
  }

  private SessionRoutingNormalizationService sessionRoutingNormalizationService() {
    if (sessionRoutingNormalizationService == null) {
      sessionRoutingNormalizationService =
          new SessionRoutingNormalizationService(sessionContextService, pointerAuthorityService);
    }
    return sessionRoutingNormalizationService;
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
  void realmsAreVisibleAfterLogin() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "REALMS demo", false);

    assertTrue(interpretation.commandResult().accepted());
    assertTrue(renderedResponse("REALMS demo", interpretation).contains("Live Realm"));
    assertTrue(renderedResponse("REALMS demo", interpretation).contains("[shared, allow_new]"));
  }

  @Test
  void charsAreVisibleAfterLogin() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "CHARS demo", false);

    assertTrue(interpretation.commandResult().accepted());
    assertTrue(renderedResponse("CHARS demo", interpretation).contains("Emberline"));
    assertTrue(
        renderedResponse("CHARS demo", interpretation)
            .contains("Realm state: shared, creation: allow_new"));
  }

  @Test
  void gameplayBeforeLoginReturnsLoginRequired() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("321", "LOOK", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    assertEquals(
        "ERROR LOGIN_REQUIRED You must LOGIN first. Use LOGIN <email> <password>.",
        renderedResponse("LOOK", interpretation));
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void helpIsVisibleBeforeLogin() {
    TextCommandInterpretationResult interpretation =
        interpreter.interpret("321", "HELP MOVE", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        "OK HELP\n"
            + "Movement commands: NORTH, SOUTH, EAST, WEST, UP, DOWN\n"
            + "Shorthand aliases: N, S, E, W, U, D\n"
            + "You can also type GO <direction>.\n\n",
        renderedResponse("HELP MOVE", interpretation));
  }

  @Test
  void whoAfterPlayShowsCurrentPlayerList() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "WHO", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        "OK WHO\nGods [0]: \nPlayers [1]: demo\n\n" + "demo> ",
        renderedResponse("WHO", interpretation));
    assertFalse(interpretation.meaningfulGameplayActivity());
  }

  @Test
  void whoBeforePlayReturnsPlayRequired() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "WHO", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("PLAY_REQUIRED", interpretation.commandResult().errorCode());
  }

  @Test
  void inventoryIsVisibleAfterPlay() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "INVENTORY", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals(
        "OK INVENTORY\n" + "Inventory:\n" + "- Torch x2 (A small torch)\n\n" + "demo> ",
        renderedResponse("INVENTORY", interpretation));
    assertTrue(interpretation.meaningfulGameplayActivity());
  }

  @Test
  void inventoryBeforeLoginReturnsLoginRequired() {
    TextCommandInterpretationResult interpretation =
        interpreter.interpret("321", "INVENTORY", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    verify(commandService, never()).enqueue("321", "INVENTORY", false);
  }

  @Test
  void equipmentIsVisibleAfterPlay() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "EQ", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals(
        "OK EQUIPMENT\n" + "Equipment:\n" + "- HEAD: Torch (A small torch)\n\n" + "demo> ",
        renderedResponse("EQ", interpretation));
  }

  @Test
  void containerIsVisibleAfterPlay() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "CONTAINER Torch", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.VIEW, PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertThat(renderedResponse("CONTAINER Torch", interpretation))
        .contains("Container: Torch")
        .contains("Ration");
  }

  @Test
  void equipmentBeforeLoginReturnsLoginRequired() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("321", "EQ", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    verify(commandService, never()).enqueue("321", "EQ", false);
  }

  @Test
  void wearBeforeLoginReturnsLoginRequired() {
    TextCommandInterpretationResult interpretation =
        interpreter.interpret("321", "WEAR Torch", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    verify(commandService, never()).enqueue("321", "WEAR Torch", false);
  }

  @Test
  void wearAfterPlayEnqueuesDurableMutation() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "WEAR Torch", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals("demo> ", renderedResponse("WEAR Torch", interpretation));
    verify(commandService).enqueue("1", "WEAR Torch", false);
    verify(gameLogicClient, never())
        .wearEquipment(Mockito.any(SessionContext.class), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void removeAfterPlayEnqueuesDurableMutation() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "REMOVE Torch", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals("demo> ", renderedResponse("REMOVE Torch", interpretation));
    verify(commandService).enqueue("1", "REMOVE Torch", false);
    verify(gameLogicClient, never())
        .removeEquipment(Mockito.any(SessionContext.class), Mockito.anyString());
  }

  @Test
  void getAfterPlayEnqueuesDurableMutation() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "GET Torch", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals("demo> ", renderedResponse("GET Torch", interpretation));
    verify(commandService).enqueue("1", "GET Torch", false);
    verify(gameLogicClient, never())
        .pickupItemFromRoom(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.anyInt());
  }

  @Test
  void dropAfterPlayEnqueuesDurableMutation() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "DROP Torch", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals("demo> ", renderedResponse("DROP Torch", interpretation));
    verify(commandService).enqueue("1", "DROP Torch", false);
    verify(gameLogicClient, never())
        .dropItemToRoom(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.anyInt());
  }

  @Test
  void putAfterPlayEnqueuesDurableMutation() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "PUT Ration INTO Torch", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals("demo> ", renderedResponse("PUT Ration INTO Torch", interpretation));
    verify(commandService).enqueue("1", "PUT Ration INTO Torch", false);
    verify(gameLogicClient, never())
        .putItemIntoContainer(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.anyInt());
  }

  @Test
  void takeAfterPlayEnqueuesDurableMutation() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "TAKE Ration FROM Torch", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    assertEquals("demo> ", renderedResponse("TAKE Ration FROM Torch", interpretation));
    verify(commandService).enqueue("1", "TAKE Ration FROM Torch", false);
    verify(gameLogicClient, never())
        .takeItemFromContainer(
            Mockito.any(SessionContext.class),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.nullable(String.class),
            Mockito.nullable(String.class),
            Mockito.anyInt());
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
        "ERROR PLAY_REQUIRED You must PLAY first. Use PLAY <world> [realm] [character].",
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
    Mockito.clearInvocations(commandService);

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "MOVE north", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("PLAY_REQUIRED", interpretation.commandResult().errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void directionalAliasBeforeLoginStillHitsInterpreterStageGate() {
    TextCommandInterpretationResult interpretation = interpreter.interpret("321", "s", false);

    assertFalse(interpretation.commandResult().accepted());
    assertEquals("LOGIN_REQUIRED", interpretation.commandResult().errorCode());
    verify(commandService, never()).enqueue(anyString(), anyString(), anyBoolean());
  }

  @Test
  void directionalAliasAfterPlayDelegatesToMoveHandler() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    when(commandService.enqueue("1", "s", false))
        .thenReturn(CommandEnqueueResult.success("cmd-77"));

    TextCommandInterpretationResult interpretation = interpreter.interpret("1", "s", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    verify(commandService).enqueue("1", "s", false);
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

    when(commandService.enqueue("1", "SAY Hello there", false))
        .thenReturn(CommandEnqueueResult.success("cmd-say-1"));

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "SAY Hello there", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(
        List.of(PlayerOutputKind.PROMPT),
        interpretation.outputs().stream().map(PlayerOutput::kind).toList());
    verify(commandService).enqueue("1", "SAY Hello there", false);
  }

  @Test
  void moveAfterPlayReturnsStructuredViewAndPrompt() {
    interpreter.interpret("1", "LOGIN demo@example.com swordfish", false);
    interpreter.interpret("1", "PLAY demo", false);

    when(commandService.enqueue("1", "MOVE north", false))
        .thenReturn(CommandEnqueueResult.success("cmd-move-1"));

    TextCommandInterpretationResult interpretation =
        interpreter.interpret("1", "MOVE north", false);

    assertTrue(interpretation.commandResult().accepted());
    assertEquals(1, interpretation.outputs().size());
    assertEquals(PlayerOutputKind.PROMPT, interpretation.outputs().get(0).kind());
    assertEquals("demo> ", interpretation.outputs().get(0).text());
    verify(commandService).enqueue("1", "MOVE north", false);
  }

  private String renderedResponse(
      String rawCommand, TextCommandInterpretationResult interpretation) {
    return outputRenderer.renderAll(
        new TextCommandParser().parse(rawCommand),
        interpretation.commandResult(),
        interpretation.outputs());
  }

  private static GameplayCatalogProperties.World world(
      String slug, long tenantId, long gameInstanceId, boolean requiresCharacterSelection) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(
        switch (slug) {
          case "demo" -> "Demo World";
          case "sandbox" -> "Builder Sandbox";
          default -> slug;
        });
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug("production");
    realm.setDisplayName("Live Realm");
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setVisible(true);
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    world.setRealms(List.of(realm));
    return world;
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
        null,
        bootstrapGameInstanceId,
        "demo",
        "production",
        1L,
        null);
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
