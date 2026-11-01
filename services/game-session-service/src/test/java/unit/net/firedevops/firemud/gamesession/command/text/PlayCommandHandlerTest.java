package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionContextUnavailableException;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

class PlayCommandHandlerTest {
  private static final String ACCOUNT_ID = "5e1340f8-99c8-49fa-a4fe-5fc9d2075621";
  private static final String PLAY_COMMAND_NAME = "PLAY";
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService =
      Mockito.mock(SessionRoutingNormalizationService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final ModerationPolicyClient moderationPolicyClient =
      Mockito.mock(ModerationPolicyClient.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final GameplayWorldCatalog worldCatalog =
      TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private PlayCommandHandler handler;

  @BeforeEach
  void setUp() {
    gameplayCatalogProperties.setWorlds(
        List.of(
            world(
                "demo",
                "Demo World",
                List.of(realm("production", "Live Realm", 22L, 1L, true, false))),
            world(
                "sandbox",
                "Builder Sandbox",
                List.of(
                    realm("production", "Live Realm", 22L, 2L, true, true),
                    realm("preview", "Preview Realm", 22L, 41L, true, true)))));
    // Demo is this tenant's sole public-production realm; sandbox remains explicitly selectable.
    gameplayCatalogProperties.getWorlds().get(1).getRealms().get(0).setPublicProductionRealm(false);
    handler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            worldCatalog,
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry);
    when(moderationPolicyClient.evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyString()))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(true)
                .build());
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipAuthorityGeneration(9L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenAnswer(
            invocation ->
                GetTenantEntitlementsForRuntimeResponse.newBuilder()
                    .setTenantId("22")
                    .setGameplayAvailable(true)
                    .setAllowPublicJoin(true)
                    .setEntitlementVersion(1L)
                    .setTenantBillingSequence(1L)
                    .setEvaluatedAt(Instant.now().toString())
                    .build());
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenAnswer(
            invocation ->
                realmGrantResponse(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    true));
    when(sessionRoutingNormalizationService.normalizeProjectedContext(
            Mockito.any(SessionContext.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(firstPartyConnectContextRegistry.find(Mockito.anyLong())).thenReturn(Optional.empty());
    when(entityManagementClient.listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class)))
        .thenAnswer(
            invocation -> {
              String tenantId = invocation.getArgument(0);
              String accountId = invocation.getArgument(1);
              String gameInstanceId = invocation.getArgument(2);
              PlayableStateScope scope = invocation.getArgument(3);
              String characterName = "demo";
              String characterId = "7001";
              if ("2".equals(gameInstanceId)) {
                characterName = "Emberline";
                characterId = "9007";
              } else if ("41".equals(gameInstanceId)) {
                characterName = "Emberline";
                characterId = "7002";
              }
              return characterRoster(characterId, characterName, tenantId, accountId, scope);
            });
  }

  @Test
  void playComposesSelectionCardinalityAndSuccessFromOneActualPointerList() {
    var authority = Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    var pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.fromString("8845d6f0-e260-4ca2-99d7-8ced4387f96b"),
            UUID.fromString("6c4da6d7-b29d-4a67-a465-b78007868653"));
    when(authority.listPointers()).thenReturn(List.of(pointer), List.of());
    var localHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            new GameplayWorldCatalog(authority),
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry);
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    var result = localHandler.handle("1", new TextCommandParser().parse("PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as demo");
    verify(authority).listPointers();
    verify(authority, never()).listPointersByTenant(Mockito.anyLong());
    verify(authority, never())
        .findPointer(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void playPromotesSessionIntoGameplay() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "play demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(gameplayPresenceLifecycleService)
        .registerConnected(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"),
            command("play-command:1:1:7001:1", PLAY_COMMAND_NAME, "play demo"));
    Mockito.verify(scriptEventPublisher)
        .publishSpawnEvent(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"),
            "play_entry",
            "play-spawn:1:1:7001:1");
    var admissionOrder = Mockito.inOrder(accountClient, entityManagementClient);
    admissionOrder
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
    admissionOrder
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.eq(ACCOUNT_ID), Mockito.eq("22"), Mockito.anyString());
    admissionOrder
        .verify(entityManagementClient)
        .listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(entityManagementClient, never())
        .findCharacterByName(
            Mockito.any(), Mockito.any(PlayableStateScope.class), Mockito.anyString());
  }

  @Test
  void playFailsClosedWhenTenantHasMultiplePublicProductionRealmsAcrossWorlds() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult ambiguousDefaultResult =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(ambiguousDefaultResult.commandResult().accepted()).isFalse();
    assertThat(ambiguousDefaultResult.commandResult().errorCode())
        .isEqualTo("PLAY_SELECTION_REQUIRED");

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ADMISSION_POINTER_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, moderationPolicyClient);
  }

  @Test
  void playFailsClosedWhenTenantHasNoPublicProductionRealm() {
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ADMISSION_POINTER_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.ADMISSION_POINTER_UNAVAILABLE_MESSAGE);
    ErrorOutput errorOutput = (ErrorOutput) result.outputs().getFirst().payload();
    assertThat(errorOutput.messageKey()).isEqualTo("error.play.admission-pointer-unavailable");

    TextPlayerOutputRenderer englishRenderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));
    assertThat(englishRenderer.render(result.outputs().getFirst()))
        .isEqualTo(
            "ERROR ADMISSION_POINTER_UNAVAILABLE Gameplay admission pointer is temporarily unavailable. Retry PLAY shortly.");

    TextPlayerOutputRenderer frenchRenderer =
        new TextPlayerOutputRenderer(
            new PresentationProperties(
                "fr",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)));
    assertThat(frenchRenderer.render(result.outputs().getFirst()))
        .isEqualTo(
            "ERROR ADMISSION_POINTER_UNAVAILABLE Le pointeur d’admission au jeu est temporairement indisponible. Réessayez PLAY sous peu.");
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, moderationPolicyClient);
  }

  @Test
  void playUsesSelectedTenantAuthorityWhenAnotherTenantHasNoPublicProductionRealm() {
    gameplayCatalogProperties.setWorlds(new ArrayList<>(gameplayCatalogProperties.getWorlds()));
    gameplayCatalogProperties
        .getWorlds()
        .add(
            world(
                "maintenance",
                "Maintenance World",
                List.of(realm("maintenance", "Maintenance Realm", 23L, 99L, true, false))));
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.eq(ACCOUNT_ID), Mockito.eq("22"), Mockito.anyString());
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void playStillRejectsSelectedTenantWithoutPublicProductionRealm() {
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    gameplayCatalogProperties.setWorlds(new ArrayList<>(gameplayCatalogProperties.getWorlds()));
    gameplayCatalogProperties
        .getWorlds()
        .add(
            world(
                "other-public",
                "Other Public World",
                List.of(realm("production", "Live Realm", 23L, 99L, true, false))));
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ADMISSION_POINTER_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, moderationPolicyClient);
  }

  @Test
  void playRejectsModerationPolicyDeniedAdmission() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(moderationPolicyClient.evaluateGameplayAdmission(22L, ACCOUNT_ID))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(false)
                .setAction("gameplay_ban")
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("MODERATION_POLICY_DENIED");
    verify(moderationPolicyClient).evaluateGameplayAdmission(22L, ACCOUNT_ID);
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .registerConnected(Mockito.any());
  }

  @Test
  void playUsesPersistedAccountRosterCharacterForSelectedTarget() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 2L, 9007L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "production", "Emberline"),
                "PLAY sandbox production Emberline"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                9007L,
                "Emberline",
                2L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "sandbox",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                9007L,
                "Emberline",
                2L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "sandbox",
                "production",
                1L,
                "SHARED"),
            command(
                "play-command:1:2:9007:1", PLAY_COMMAND_NAME, "PLAY sandbox production Emberline"));
    Mockito.verify(scriptEventPublisher)
        .publishSpawnEvent(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                9007L,
                "Emberline",
                2L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "sandbox",
                "production",
                1L,
                "SHARED"),
            "play_entry",
            "play-spawn:1:2:9007:1");
  }

  @Test
  void playWithoutCharacterSelectorShowsPersistedRosterCharacterName() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "7001",
                "Emberline",
                "22",
                ACCOUNT_ID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as Emberline");
  }

  @Test
  void playWithCaseVariedCharacterSelectorShowsPersistedRosterCharacterName() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "7001",
                "Emberline",
                "22",
                ACCOUNT_ID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "eMbErLiNe"), "PLAY demo eMbErLiNe"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as Emberline");
  }

  @Test
  void playRejectsMalformedPersistedRosterCharacterId() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "abc",
                "Emberline",
                "22",
                ACCOUNT_ID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "production", "Emberline"),
                "PLAY sandbox production Emberline"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_MESSAGE);
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void playRejectsCharacterRosterFromAnotherAccountBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "8008", "demo", "22", "999", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsCharacterRosterFromAnotherTenantBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "8008", "demo", "23", ACCOUNT_ID, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsDuplicatePersistedCharacterIdsBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    ListCharactersByAccountResponse roster =
        characterRoster(
                "8008", "demo", "22", ACCOUNT_ID, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .addCharacters(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("8008")
                    .setTenantId("22")
                    .setAccountId(ACCOUNT_ID)
                    .setName("other")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .build())
            .build();
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(roster);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playWithAmbiguousCharacterNameReturnsSelectionGuidanceBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    ListCharactersByAccountResponse roster =
        characterRoster(
                "8008", "demo", "22", ACCOUNT_ID, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .addCharacters(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("8009")
                    .setTenantId("22")
                    .setAccountId(ACCOUNT_ID)
                    .setName("Demo")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .build())
            .build();
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(roster);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playWithEmptyCharacterRosterReturnsSelectionGuidanceBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.newBuilder().build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playWithoutCharacterSelectorReturnsSelectionGuidanceForMultipleCharacters() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    ListCharactersByAccountResponse roster =
        characterRoster(
                "8008", "demo", "22", ACCOUNT_ID, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .addCharacters(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("8009")
                    .setTenantId("22")
                    .setAccountId(ACCOUNT_ID)
                    .setName("Emberline")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .build())
            .build();
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(roster);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playWithUnmatchedCharacterSelectorReturnsGuidanceBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(TextCommandType.PLAY, List.of("demo", "missing"), "PLAY demo missing"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsUnavailableCharacterRosterBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("CHARACTER_LIST_UNAVAILABLE")
                        .setMessage("Character list unavailable"))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playResumeDoesNotPublishSpawnEvent() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            ACCOUNT_ID,
            "demo@example.com",
            7001L,
            "Emberline",
            1L,
            "R-7",
            "jwt-token",
            null,
            0L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "7001",
                "Emberline",
                "22",
                ACCOUNT_ID,
                PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as Emberline");
    assertThat(result.reconnectRedrawRecommended()).isTrue();
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            context, command("play-command:1:1:7001:1", PLAY_COMMAND_NAME, "PLAY demo"));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.any(), Mockito.any());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
  }

  @Test
  void playIgnoresStaleExistingBindingAfterNormalization() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    SessionContext clearedExisting =
        new SessionContext(
            9L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, null, "old-jwt", 1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.of(clearedExisting));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isFalse();
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .recordDisconnected(Mockito.eq(9L), Mockito.any());
    Mockito.verify(sessionContextService, never()).deleteBySessionId(22L, 9L);
    Mockito.verify(sessionAuthenticationService).resolveByGameplayIdentity(22L, 1L, 7001L);
  }

  @Test
  void playIgnoresExistingBindingWithoutRoomRegion() {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    SessionContext existingWithoutRoom =
        new SessionContext(
            9L,
            22L,
            ACCOUNT_ID,
            "demo@example.com",
            7001L,
            "demo",
            1L,
            null,
            "old-jwt",
            null,
            0L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.of(existingWithoutRoom));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isFalse();
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                0L,
                "demo",
                "production",
                1L,
                "SHARED"));
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .recordDisconnected(Mockito.eq(9L), Mockito.any());
    Mockito.verify(sessionContextService, never()).deleteBySessionId(22L, 9L);
  }

  @Test
  void playWithoutSessionReturnsLoginRequired() {
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void playWithoutArgumentsReturnsInvalidArgument() {
    SessionContext context = new SessionContext(1L, 22L, ACCOUNT_ID, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of(), PLAY_COMMAND_NAME));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  void unknownWorldReturnsSelectionGuidance() {
    SessionContext context = new SessionContext(1L, 22L, ACCOUNT_ID, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("unknown"), "PLAY unknown"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
  }

  @Test
  void playDoesNotAdmitV6QuarantinedPointerWithoutCatalogIdentity() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    when(authorityService.listPointers())
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    22L,
                    11L,
                    1L,
                    true,
                    true,
                    false,
                    "UNKNOWN_LEGACY_SCOPE",
                    "ALLOW_NEW",
                    0L,
                    null,
                    null)));
    GameplayWorldCatalog authorityBackedCatalog = new GameplayWorldCatalog(authorityService);
    PlayCommandHandler authorityBackedHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            authorityBackedCatalog,
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry);
    SessionContext context = new SessionContext(1L, 22L, ACCOUNT_ID, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        authorityBackedHandler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    Mockito.verify(sessionContextService).findBySessionId(1L);
    Mockito.verifyNoMoreInteractions(sessionContextService);
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void retainedPointerWithUnknownStateScopeCannotBindOrSpawn() {
    GameplayAdmissionPointerAuthorityService pointerAuthority =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    when(pointerAuthority.listPointers())
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    22L,
                    1L,
                    1L,
                    true,
                    true,
                    false,
                    "LEGACY_UNKNOWN",
                    "ALLOW_NEW")));
    PlayCommandHandler pointerBackedHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            new GameplayWorldCatalog(pointerAuthority),
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry);
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        pointerBackedHandler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            "22", ACCOUNT_ID, "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(scriptEventPublisher, never()).publishCommandEvent(Mockito.any(), Mockito.any());
  }

  @Test
  void sandboxWithoutCharacterReturnsSelectionRequired() {
    SessionContext context = new SessionContext(1L, 22L, ACCOUNT_ID, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("sandbox"), "PLAY sandbox"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(
            "Selection required. Use PLAY sandbox <realm> [character] or browse REALMS first.");
    assertThat(
            new TextPlayerOutputRenderer(new PresentationProperties())
                .render(result.outputs().get(0), "fr"))
        .isEqualTo(
            "ERROR PLAY_SELECTION_REQUIRED Sélection requise. Utilisez PLAY sandbox <realm> [character] ou consultez REALMS d’abord.");
  }

  @Test
  void explicitRealmWithoutCharacterReturnsCharacterSelectionGuidance() {
    SessionContext context = new SessionContext(1L, 22L, ACCOUNT_ID, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("sandbox", "preview"), "PLAY sandbox preview"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(
            "Selection required. Use PLAY sandbox preview <character> with a known character name.");
  }

  @Test
  void playRejectsIsolatedStateRealm() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.COPIED_ONLY);
    SessionContext context = new SessionContext(1L, 22L, ACCOUNT_ID, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
  }

  @Test
  void firstPartyPlayRejectsIncompletePersistedSelectorWhenRegistryEntryIsMissing() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            ACCOUNT_ID,
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            null,
            41L,
            "sandbox",
            "preview",
            1L,
            null,
            "scope-persisted",
            null);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(TextCommandType.PLAY, List.of("sandbox", "Sora"), "PLAY sandbox Sora"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
  }

  @Test
  void playDeniedByMembershipReturnsWorldAccessDenied() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(2L)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipAuthorityGeneration(9L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("WORLD_ACCESS_DENIED");
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
  }

  @Test
  void playDeniedDoesNotClearCrossTenantBindingForSameVisibleRealm() {
    SessionContext context =
        new SessionContext(
            1L,
            23L,
            ACCOUNT_ID,
            "demo@example.com",
            7001L,
            "demo",
            1L,
            "R-1",
            "jwt-token",
            null,
            1L,
            "sandbox",
            "preview",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(0L)
                .setMembershipLifecycleState("MISSING")
                .setMembershipAuthorityGeneration(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode()).isEqualTo("NON_PUBLIC_ENROLLMENT_REQUIRED");
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
  }

  @Test
  void playNonPublicRealmRequiresExistingAdmissibleMembership() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("MISSING")
                .setMembershipAuthorityGeneration(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.NON_PUBLIC_ENROLLMENT_REQUIRED_CODE);
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void playInvisibleRealmWithGrantedAccessContinuesAdmission() {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(realmGrantResponse(true));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq(ACCOUNT_ID),
            Mockito.eq("22"),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
  }

  @Test
  void playVisibleNonPublicRealmRequiresGrantedAccess() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(realmGrantResponse(false));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.REALM_ACCESS_DENIED_CODE);
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq(ACCOUNT_ID),
            Mockito.eq("22"),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
  }

  @Test
  void playInvisibleRealmWithDeniedGrantClearsBinding() {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(realmGrantResponse(false));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.REALM_ACCESS_DENIED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "access_denied");
  }

  @Test
  void playInvisibleRealmWithNonAuthorityGrantClearsBinding() {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(
            GetRealmAccessGrantForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("PERMISSION_DENIED")
                        .setMessage("grant denied")
                        .build())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.REALM_ACCESS_DENIED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "access_denied");
  }

  @Test
  void playInvisibleRealmWithUnavailableGrantFailsClosed() {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(
            GetRealmAccessGrantForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE)
                        .setMessage(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE)
                        .build())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playMismatchedRealmGrantCorrelationFailsUnavailable() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(realmGrantResponse(true).toBuilder().setRealmSlug("other-realm").build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playGrantedRealmWithoutGrantGenerationFailsUnavailable() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(realmGrantResponse(true).toBuilder().setGrantVersion(0L).build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    verifyNoGameplayBindingSideEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"NOT_FOUND", "PERMISSION_DENIED"})
  void playNonAuthorityMembershipErrorReturnsAccessDeniedAndClearsBinding(String errorCode) {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(errorCode)
                        .setMessage("membership failure")
                        .build())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("WORLD_ACCESS_DENIED");
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.world-access-denied");
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "access_denied");
  }

  @Test
  void playRequiresExplicitJoinWhenPublicProductionMembershipIsMissing() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(0L)
                .setMembershipLifecycleState("MISSING")
                .setMembershipAuthorityGeneration(0L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");
  }

  @Test
  void playRequiresExplicitJoinWhenPublicProductionMembershipIsInactive() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(4L)
                .setMembershipLifecycleState("INACTIVE")
                .setMembershipAuthorityGeneration(9L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "c04bf730-5f95-4357-a850-ac29eca1754e",
        "123",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "5E1340F8-99C8-49FA-A4FE-5FC9D2075621",
        " 5e1340f8-99c8-49fa-a4fe-5fc9d2075621"
      })
  void playWhenMembershipAuthorityAccountDoesNotExactlyMatchFailsClosed(String responseAccountId) {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(responseAccountId)
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipAuthorityGeneration(9L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(moderationPolicyClient, never())
        .evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyString());
  }

  @Test
  void authorityReplyWindowIncludesExactBoundsAndRejectsOneNanosecondOutside() {
    Instant now = Instant.parse("2026-10-31T00:00:00Z");
    assertThat(PlayCommandHandler.isWithinAuthorityReplyWindow(now.minusSeconds(300), now))
        .isTrue();
    assertThat(PlayCommandHandler.isWithinAuthorityReplyWindow(now.plusSeconds(5), now)).isTrue();
    assertThat(
            PlayCommandHandler.isWithinAuthorityReplyWindow(
                now.minusSeconds(300).minusNanos(1), now))
        .isFalse();
    assertThat(
            PlayCommandHandler.isWithinAuthorityReplyWindow(now.plusSeconds(5).plusNanos(1), now))
        .isFalse();
  }

  @Test
  void entitlementFreshnessExpiresAtConservativeDeadlineAndGuardsFutureSkew() {
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    assertThat(
            PlayCommandHandler.isWithinEntitlementReplyWindow(
                now.minusSeconds(10).plusNanos(1), now))
        .isTrue();
    assertThat(PlayCommandHandler.isWithinEntitlementReplyWindow(now.minusSeconds(10), now))
        .isFalse();
    assertThat(
            PlayCommandHandler.isWithinEntitlementReplyWindow(
                now.minusSeconds(10).minusNanos(1), now))
        .isFalse();
    assertThat(PlayCommandHandler.isWithinEntitlementReplyWindow(now.plusSeconds(5), now)).isTrue();
    assertThat(
            PlayCommandHandler.isWithinEntitlementReplyWindow(now.plusSeconds(5).plusNanos(1), now))
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "22,1,1,-9,true", "22,1,1,-11,false", "22,1,1,-299,false",
    "22,1,1,4,true", "22,1,1,6,false", "23,1,1,0,false",
    "022,1,1,0,false", "22,0,1,0,false", "22,1,0,0,false",
    "22,-1,1,0,false", "22,1,-1,0,false"
  })
  void entitlementEvidenceHasItsOwnStrictFreshnessAndTenantVersionGuards(
      String tenantId, long version, long sequence, long secondsFromNow, boolean accepted) {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            entitlementResponse(true).toBuilder()
                .setTenantId(tenantId)
                .setEntitlementVersion(version)
                .setTenantBillingSequence(sequence)
                .setEvaluatedAt(Instant.now().plusSeconds(secondsFromNow).toString())
                .build());

    var result = handler.handle("1", new TextCommandParser().parse("PLAY demo"));

    assertThat(result.commandResult().accepted()).isEqualTo(accepted);
    if (!accepted) {
      assertThat(result.commandResult().errorCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
      verify(accountClient, never())
          .getTenantMembershipForRuntime(
              Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
      verifyNoGameplayBindingSideEffects();
      Mockito.verifyNoInteractions(entityManagementClient, moderationPolicyClient);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-timestamp"})
  void malformedEntitlementTimestampStopsBeforeMembership(String timestamp) {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(entitlementResponse(true).toBuilder().setEvaluatedAt(timestamp).build());

    var result = handler.handle("1", new TextCommandParser().parse("PLAY demo"));

    assertThat(result.commandResult().errorCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
    Mockito.verifyNoInteractions(entityManagementClient, moderationPolicyClient);
  }

  @ParameterizedTest
  @CsvSource({
    "MISSING,false,false,0,0,true,JOIN_REQUIRED",
    "MISSING,false,false,0,0,false,PUBLIC_PRODUCTION_ADMISSION_DENIED",
    "INACTIVE,true,false,12,9,true,JOIN_REQUIRED",
    "INACTIVE,true,false,12,9,false,PUBLIC_PRODUCTION_ADMISSION_DENIED",
    "ACTIVE,true,false,12,9,true,WORLD_ACCESS_DENIED",
    "ACTIVE,true,false,12,9,false,WORLD_ACCESS_DENIED",
    "LEGACY_UNVERIFIED,true,false,12,9,true,WORLD_ACCESS_DENIED",
    "LEGACY_UNVERIFIED,true,false,12,9,false,WORLD_ACCESS_DENIED",
    "UNKNOWN,true,false,12,9,true,AUTH_UNAVAILABLE",
    "'',true,false,12,9,true,AUTH_UNAVAILABLE",
    "MISSING,false,true,0,0,true,AUTH_UNAVAILABLE",
    "MISSING,false,false,1,0,true,AUTH_UNAVAILABLE",
    "MISSING,false,false,0,1,true,AUTH_UNAVAILABLE",
    "ACTIVE,false,false,0,0,true,AUTH_UNAVAILABLE",
    "MISSING,true,false,12,9,true,AUTH_UNAVAILABLE",
    "INACTIVE,true,true,12,9,true,AUTH_UNAVAILABLE",
    "LEGACY_UNVERIFIED,true,true,12,9,true,AUTH_UNAVAILABLE",
    "ACTIVE,true,true,0,9,true,AUTH_UNAVAILABLE",
    "ACTIVE,true,true,12,0,true,AUTH_UNAVAILABLE",
    "ACTIVE,true,true,-1,9,true,AUTH_UNAVAILABLE",
    "ACTIVE,true,true,12,-1,true,AUTH_UNAVAILABLE"
  })
  void publicMembershipUsesLifecycleAndIndependentVersionGenerationWithoutRepairingDenial(
      String lifecycle,
      boolean exists,
      boolean admitting,
      long version,
      long generation,
      boolean allowPublicJoin,
      String expectedCode) {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(entitlementResponse(allowPublicJoin));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(membershipResponse(lifecycle, exists, admitting, version, generation));

    var result = handler.handle("1", new TextCommandParser().parse("PLAY demo"));

    assertThat(result.commandResult().errorCode()).isEqualTo(expectedCode);
    verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
    Mockito.verifyNoInteractions(entityManagementClient, moderationPolicyClient);
  }

  @ParameterizedTest
  @CsvSource({"12,9", "9,12"})
  void activeMemberRetainsAccessWhenPublicEnrollmentCloses(long version, long generation) {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(entitlementResponse(false));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(membershipResponse("ACTIVE", true, true, version, generation));

    var result = handler.handle("1", new TextCommandParser().parse("PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    verify(moderationPolicyClient).evaluateGameplayAdmission(22L, ACCOUNT_ID);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "INACTIVE", "LEGACY_UNVERIFIED"})
  void privateNonActiveMembershipRequiresEnrollmentWithoutGrantOrPublicJoin(String lifecycle) {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    boolean exists = !"MISSING".equals(lifecycle);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(entitlementResponse(false));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            membershipResponse(lifecycle, exists, false, exists ? 12L : 0L, exists ? 9L : 0L));

    var result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode()).isEqualTo("NON_PUBLIC_ENROLLMENT_REQUIRED");
    verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
    Mockito.verifyNoInteractions(entityManagementClient, moderationPolicyClient);
  }

  @ParameterizedTest
  @CsvSource({
    "MISSING,false,ENTITLEMENT_UNAVAILABLE", "INACTIVE,true,ENTITLEMENT_UNAVAILABLE",
    "MISSING,false,TENANT_BILLING_BLOCKED", "INACTIVE,true,TENANT_BILLING_BLOCKED"
  })
  void entitlementFailureMasksPublicRepairBeforeMembershipRead(
      String lifecycle, boolean exists, String errorCode) {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            membershipResponse(lifecycle, exists, false, exists ? 12L : 0L, exists ? 9L : 0L));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder().setCode(errorCode))
                .build());

    var result = handler.handle("1", new TextCommandParser().parse("PLAY demo"));

    assertThat(result.commandResult().errorCode()).isEqualTo(errorCode);
    verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
    Mockito.verifyNoInteractions(entityManagementClient, moderationPolicyClient);
  }

  @Test
  void sameTargetActiveDenialClearsOnlyGameplayAndRetainsAuthenticatedUuidContext() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(membershipResponse("ACTIVE", true, false, 12L, 9L));

    var result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode()).isEqualTo("WORLD_ACCESS_DENIED");
    var saved = org.mockito.ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(saved.capture());
    assertThat(saved.getValue().accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(saved.getValue().loginName()).isEqualTo(context.loginName());
    assertThat(saved.getValue().jwt()).isEqualTo(context.jwt());
    assertThat(saved.getValue().tenantId()).isEqualTo(context.tenantId());
    assertThat(saved.getValue().hasGameplayBinding()).isFalse();
    verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "access_denied");
    Mockito.verifyNoInteractions(
        entityManagementClient, moderationPolicyClient, scriptEventPublisher);
    verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  private static GetTenantMembershipForRuntimeResponse membershipResponse(
      String lifecycle, boolean exists, boolean admitting, long version, long generation) {
    return GetTenantMembershipForRuntimeResponse.newBuilder()
        .setAccountId(ACCOUNT_ID)
        .setTenantId("22")
        .setMembershipExists(exists)
        .setGameplayAdmissionAllowed(admitting)
        .setMembershipLifecycleState(lifecycle)
        .setMembershipVersion(version)
        .setMembershipAuthorityGeneration(generation)
        .setEvaluatedAt(Instant.now().toString())
        .build();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "denied",
        "outage",
        "malformed-error",
        "account",
        "tenant",
        "world",
        "realm",
        "timestamp",
        "version",
        "negative-version"
      })
  void privateGrantOutcomeNeverFallsBackToJoinOrInvokesLaterAdmission(String scenario) {
    SessionContext context =
        new SessionContext(1L, 22L, ACCOUNT_ID, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(entitlementResponse(false));
    var grant = realmGrantResponse(true).toBuilder();
    switch (scenario) {
      case "denied" -> grant.setGranted(false).setGrantVersion(0L);
      case "outage" ->
          grant.setError(
              net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                  .setCode("AUTH_UNAVAILABLE"));
      case "malformed-error" ->
          grant.setError(
              net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                  .setMessage("unclassified failure"));
      case "account" -> grant.setAccountId("00000000-0000-4000-8000-000000000043");
      case "tenant" -> grant.setTenantId("23");
      case "world" -> grant.setWorldSlug("other");
      case "realm" -> grant.setRealmSlug("other");
      case "timestamp" -> grant.setEvaluatedAt("not-a-timestamp");
      case "version" -> grant.setGrantVersion(0L);
      case "negative-version" -> grant.setGranted(false).setGrantVersion(-1L);
      default -> throw new IllegalArgumentException(scenario);
    }
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(grant.build());

    var result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo("denied".equals(scenario) ? "REALM_ACCESS_DENIED" : "AUTH_UNAVAILABLE");
    var order = Mockito.inOrder(accountClient);
    order
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
    order
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.eq(ACCOUNT_ID), Mockito.eq("22"), Mockito.anyString());
    order
        .verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq(ACCOUNT_ID),
            Mockito.eq("22"),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
    Mockito.verifyNoInteractions(entityManagementClient, moderationPolicyClient);
  }

  @Test
  void deniedSelectedRealmDoesNotClearUnrelatedSameTenantGameplayBinding() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(membershipResponse("ACTIVE", true, false, 12L, 9L));

    var result = handler.handle("1", new TextCommandParser().parse("PLAY demo"));

    assertThat(result.commandResult().errorCode()).isEqualTo("WORLD_ACCESS_DENIED");
    verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
    Mockito.verifyNoInteractions(entityManagementClient, moderationPolicyClient);
  }

  private static GetTenantEntitlementsForRuntimeResponse entitlementResponse(
      boolean allowPublicJoin) {
    return GetTenantEntitlementsForRuntimeResponse.newBuilder()
        .setTenantId("22")
        .setGameplayAvailable(true)
        .setAllowPublicJoin(allowPublicJoin)
        .setEntitlementVersion(12L)
        .setTenantBillingSequence(9L)
        .setEvaluatedAt(Instant.now().toString())
        .build();
  }

  @ParameterizedTest
  @CsvSource({
    "membership,-301,false", "membership,-299,true", "membership,4,true", "membership,6,false",
    "realm-grant,-301,false", "realm-grant,-299,true", "realm-grant,4,true", "realm-grant,6,false"
  })
  void membershipAndRealmGrantApplyTheSameTimestampWindow(
      String replyKind, long secondsFromNow, boolean accepted) {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    String evaluatedAt = Instant.now().plusSeconds(secondsFromNow).toString();
    if (replyKind.equals("membership")) {
      when(accountClient.getTenantMembershipForRuntime(
              Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
          .thenReturn(
              GetTenantMembershipForRuntimeResponse.newBuilder()
                  .setAccountId(ACCOUNT_ID)
                  .setTenantId("22")
                  .setMembershipExists(true)
                  .setGameplayAdmissionAllowed(true)
                  .setMembershipVersion(1L)
                  .setMembershipLifecycleState("ACTIVE")
                  .setMembershipAuthorityGeneration(9L)
                  .setEvaluatedAt(evaluatedAt)
                  .build());
    } else {
      when(accountClient.getRealmAccessGrantForRuntime(
              Mockito.anyString(),
              Mockito.anyString(),
              Mockito.anyString(),
              Mockito.anyString(),
              Mockito.anyString()))
          .thenReturn(realmGrantResponse(true).toBuilder().setEvaluatedAt(evaluatedAt).build());
    }

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    if (accepted) {
      assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    } else {
      assertThat(result.commandResult().errorCode())
          .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
      Mockito.verify(gameplayPresenceLifecycleService, never())
          .clearGameplayBinding(Mockito.any(), Mockito.anyString());
      Mockito.verify(sessionContextService, never()).save(Mockito.any());
      Mockito.verifyNoInteractions(entityManagementClient);
    }
  }

  @Test
  void playWhenMembershipAuthorityTimestampIsMalformedFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipAuthorityGeneration(9L)
                .setEvaluatedAt("not-a-timestamp")
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playWithUnavailableSessionContextReturnsRetryableAuthorityFailure() {
    when(sessionContextService.findBySessionId(1L))
        .thenThrow(new SessionContextUnavailableException("session context unavailable", null));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        sessionAuthenticationService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void playWithUnavailableSessionStoreReturnsRetryableAuthorityFailure() {
    when(sessionContextService.findBySessionId(1L))
        .thenThrow(new DataAccessResourceFailureException("session store unavailable"));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verifyNoInteractions(sessionAuthenticationService, gameplayPresenceLifecycleService);
  }

  @Test
  void playBlockedByEntitlementsReturnsBillingBlocked() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(false)
                .setEntitlementVersion(5L)
                .setTenantBillingSequence(5L)
                .setEvaluatedAt(Instant.now().toString())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("TENANT_BILLING_BLOCKED");
    assertThat(
            meterRegistry
                .counter("gamesession.session.resume_denied", "reason", "tenant_unavailable")
                .count())
        .isEqualTo(1.0);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "tenant_unavailable");
  }

  @Test
  void playMapsNonAuthorityEntitlementFailureToBillingBlocked() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("TENANT_BILLING_BLOCKED")
                        .setMessage("Tenant billing blocks gameplay"))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.billing-blocked");
    assertThat(
            meterRegistry
                .counter("gamesession.session.resume_denied", "reason", "tenant_unavailable")
                .count())
        .isEqualTo(1.0);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "tenant_unavailable");
  }

  @Test
  void playWhenMembershipAuthorityUnavailableFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE)
                        .setMessage(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    assertThat(meterRegistry.find("gamesession.session.resume_denied").counters()).isEmpty();
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ENTITLEMENT_UNAVAILABLE",
        "AUTH_UNAVAILABLE",
        "UNAVAILABLE",
        "DEADLINE_EXCEEDED",
        "UNKNOWN",
        ""
      })
  void playWhenEntitlementAuthorityUnavailablePreservesExistingBinding(String unavailableCode) {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(unavailableCode)
                        .setMessage(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.entitlement-unavailable");
    assertThat(meterRegistry.find("gamesession.session.resume_denied").counters()).isEmpty();
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
  }

  @Test
  void staleContextCharacterIsReplacedByPersistedRosterActorBeforeFreshEntry() {
    SessionContext context =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                1L,
                "demo",
                "production",
                1L,
                "SHARED"));
    assertThat(
            meterRegistry
                .counter(
                    "gamesession.session.fresh_entry_fallback",
                    "reason",
                    "stale_or_missing_context")
                .count())
        .isEqualTo(0.0);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                1L,
                "demo",
                "production",
                1L,
                "SHARED"),
            command("play-command:1:1:7001:1", PLAY_COMMAND_NAME, "PLAY demo"));
    Mockito.verify(scriptEventPublisher)
        .publishSpawnEvent(
            new SessionContext(
                1L,
                22L,
                ACCOUNT_ID,
                "demo@example.com",
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                1L,
                "demo",
                "production",
                1L,
                "SHARED"),
            "play_entry",
            "play-spawn:1:1:7001:1");
  }

  private static String joinedOutputText(List<PlayerOutput> outputs) {
    return outputs.stream()
        .map(PlayerOutput::text)
        .filter(text -> text != null && !text.isBlank())
        .reduce((left, right) -> left + "\n" + right)
        .orElse(null);
  }

  private void markPreviewRealmInvisible() {
    gameplayCatalogProperties.getWorlds().get(1).getRealms().get(1).setVisible(false);
  }

  private SessionContext previewRealmContext() {
    return new SessionContext(
        1L,
        22L,
        ACCOUNT_ID,
        "demo@example.com",
        123L,
        "Emberline",
        41L,
        "R-1",
        "jwt-token",
        null,
        41L,
        "sandbox",
        "preview",
        1L,
        "SHARED");
  }

  private TextCommand previewRealmPlayCommand() {
    return new TextCommand(
        TextCommandType.PLAY,
        List.of("sandbox", "preview", "Emberline"),
        "PLAY sandbox preview Emberline");
  }

  private static GetRealmAccessGrantForRuntimeResponse realmGrantResponse(boolean granted) {
    return realmGrantResponse(ACCOUNT_ID, "22", "sandbox", "preview", granted);
  }

  private static GetRealmAccessGrantForRuntimeResponse realmGrantResponse(
      String accountId, String tenantId, String worldSlug, String realmSlug, boolean granted) {
    return GetRealmAccessGrantForRuntimeResponse.newBuilder()
        .setAccountId(accountId)
        .setTenantId(tenantId)
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .setGranted(granted)
        .setGrantVersion(granted ? 3L : 0L)
        .setEvaluatedAt(Instant.now().toString())
        .build();
  }

  private static GameplayCatalogProperties.World world(
      String slug, String displayName, List<GameplayCatalogProperties.Realm> realms) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(displayName);
    world.setRealms(new ArrayList<>(realms));
    return world;
  }

  private static GameplayCatalogProperties.Realm realm(
      String slug,
      String displayName,
      long tenantId,
      long gameInstanceId,
      boolean visible,
      boolean requiresCharacterSelection) {
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug(slug);
    realm.setDisplayName(displayName);
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setVisible(visible);
    realm.setPublicProductionRealm("production".equalsIgnoreCase(slug));
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    return realm;
  }

  private static ListCharactersByAccountResponse characterRoster(
      String id, String name, String tenantId, String accountId, PlayableStateScope scope) {
    return ListCharactersByAccountResponse.newBuilder()
        .addCharacters(
            net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                .setId(id)
                .setTenantId(tenantId)
                .setAccountId(accountId)
                .setName(name)
                .setPlayableStateScope(scope)
                .build())
        .build();
  }

  private void verifyNoGameplayBindingSideEffects() {
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  private void assertDemoCharacterSelectionRequired(PlayCommandHandlingResult result) {
    String expectedMessage =
        "Selection required. Use PLAY demo <character> with a known character name.";
    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(result.commandResult().errorMessage()).isEqualTo(expectedMessage);

    ErrorOutput error = (ErrorOutput) result.outputs().getFirst().payload();
    assertThat(error.code()).isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(error.message()).isEqualTo(expectedMessage);
    assertThat(error.messageKey()).isEqualTo("error.play.character-selection-required");
    assertThat(error.arguments())
        .containsEntry("worldSlug", "demo")
        .containsEntry("realmSlug", "production")
        .containsEntry("playUsage", "PLAY demo <character>");
  }

  private static GameplayCommand command(String commandId, String commandName, String commandText) {
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId(commandId);
    gameplayCommand.setCommandName(commandName);
    gameplayCommand.setCommandText(commandText);
    return gameplayCommand;
  }

  @Test
  void firstPartyPlayRereadsCanonicalOwnerThenDeniesBeforeLegacyAdmissionEffects() {
    var fixture = new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture();
    FirstPartyConnectContext context = fixture.context();
    when(firstPartyConnectContextRegistry.find(1L))
        .thenAnswer(invocation -> Optional.of(fixture.service.requireRetained(context)));
    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo", "hero"), "PLAY demo hero"));
    assertThat(result.commandResult().errorCode()).isEqualTo("FIRST_PARTY_AUTHORITY_UNAVAILABLE");
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey()).isNull();
    Mockito.verify(firstPartyConnectContextRegistry).find(1L);
    Mockito.verify(firstPartyConnectContextRegistry, never()).requireRetained(Mockito.any());
    Mockito.verify(fixture.routes, Mockito.atLeast(2))
        .readCurrentTarget(
            net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture.TENANT);
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        sessionAuthenticationService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playDefensivelyDeniesFirstPartyContextAddedAfterTopLevelCheck() {
    var fixture = new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture();
    FirstPartyConnectContext context = fixture.context();
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(Optional.empty())
        .thenAnswer(invocation -> Optional.of(fixture.service.requireRetained(context)));
    SessionContext legacyContext =
        new SessionContext(
            1L, 22L, ACCOUNT_ID, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1"))
        .thenReturn(Optional.of(legacyContext));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo", "hero"), "PLAY demo hero"));

    assertThat(result.commandResult().errorCode()).isEqualTo("FIRST_PARTY_AUTHORITY_UNAVAILABLE");
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey()).isNull();
    Mockito.verify(firstPartyConnectContextRegistry, Mockito.times(2)).find(1L);
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
  }

  @Test
  void changedFirstPartyOwnerDeniesPlayBeforeLegacyAdmissionEffects() {
    var fixture = new net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture();
    FirstPartyConnectContext context = fixture.context();
    when(firstPartyConnectContextRegistry.find(1L))
        .thenAnswer(invocation -> Optional.of(fixture.service.requireRetained(context)));
    when(fixture.routes.readCurrentTarget(
            net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture.TENANT))
        .thenReturn(null);
    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo", "hero"), "PLAY demo hero"));
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        sessionAuthenticationService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }
}
