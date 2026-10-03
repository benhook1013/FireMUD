package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.UnknownFieldSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PlayCommandHandlerTest {
  private static final String PLAY_COMMAND_NAME = "PLAY";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID ADMISSION_REALM_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ADMISSION_NAMESPACE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
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
  private final DirectTextConnectScopeSessionStore connectScopeSessionStore =
      DirectTextConnectScopeSessionStore.inMemoryForTest();
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
            meterRegistry,
            connectScopeSessionStore);
    when(moderationPolicyClient.evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyLong()))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(true)
                .build());
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .active(123L, 22L, "1")),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenAnswer(
            invocation ->
                validGrant(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)));
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
              } else if ("23".equals(tenantId)) {
                characterName = "Sora";
              }
              return characterRoster(characterId, characterName, tenantId, accountId, scope);
            });
  }

  @Test
  void playPromotesSessionIntoGameplay() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "play demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isFalse();
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                123L,
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
                123L,
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
                123L,
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
                123L,
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
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    admissionOrder
        .verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(entityManagementClient, never())
        .findCharacterByName(
            Mockito.any(), Mockito.any(PlayableStateScope.class), Mockito.anyString());
  }

  @Test
  void playUsesSelectedTargetRosterInsteadOfStaleContextCharacterId() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 9999L, "demo", 41L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "8008", "demo", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 8008L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(sessionContextService)
        .save(Mockito.argThat(saved -> saved.characterId() == 8008L));
    Mockito.verify(gameplayPresenceLifecycleService)
        .registerConnected(Mockito.argThat(saved -> saved.characterId() == 8008L));
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(Mockito.argThat(saved -> saved.characterId() == 8008L), Mockito.any());
  }

  @Test
  void playRejectsSameNameCharacterFromAnotherAccountBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
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
  void playRejectsMissingCharacterEvidenceBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.newBuilder().build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.character-selection-required");
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsAmbiguousCharacterEvidenceBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    ListCharactersByAccountResponse roster =
        characterRoster("8008", "demo", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .addCharacters(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("8009")
                    .setTenantId("22")
                    .setAccountId("123")
                    .setName("Demo")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .build())
            .build();
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(roster);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.character-selection-required");
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsNamespaceUnqualifiedRosterBeforeAdmissionOrBindingSideEffects() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
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
    assertThat(((ErrorOutput) result.outputs().getFirst().payload()).code())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    Mockito.verify(moderationPolicyClient, Mockito.never())
        .evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyLong());
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void numericWorldPlayRejectsReorderedWorldSnapshotBeforeAdmissionSideEffects() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .forEach(realm -> realm.setTenantId(23L));
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    GameplayWorldCatalog.DiscoverySnapshot snapshot = worldCatalog.readDiscoverySnapshot();
    connectScopeSessionStore.replaceWorldSnapshot(
        context.sessionId(),
        context.accountId(),
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        java.time.Instant.now());
    gameplayCatalogProperties.setWorlds(
        List.of(
            gameplayCatalogProperties.getWorlds().get(1),
            gameplayCatalogProperties.getWorlds().get(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("1"), "PLAY 1"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        firstPartyConnectContextRegistry,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void numericWorldSelectorUsesInjectedAuthorityClockForSnapshotLookup() {
    Instant authorityNow = Instant.parse("2000-01-02T03:04:05Z");
    Clock authorityClock = Clock.fixed(authorityNow, ZoneOffset.UTC);
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayWorldCatalog.DiscoverySnapshot snapshot = worldCatalog.readDiscoverySnapshot();
    DirectTextConnectScopeSessionStore scopeStore =
        Mockito.spy(DirectTextConnectScopeSessionStore.inMemoryForTest());
    scopeStore.replaceWorldSnapshot(
        context.sessionId(),
        context.accountId(),
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        authorityNow);
    PlayCommandHandler clockHandler = handlerWithClock(authorityClock, scopeStore);

    PlayCommandHandlingResult result =
        clockHandler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("1"), "PLAY 1"));

    assertThat(result.commandResult().errorCode()).isNotEqualTo("CONNECT_SCOPE_MISMATCH");
    Mockito.verify(scopeStore).worldsSnapshot(context, authorityNow);
  }

  @Test
  void numericRealmSelectorUsesInjectedAuthorityClockForSnapshotLookup() {
    Instant authorityNow = Instant.parse("2000-01-02T03:04:05Z");
    Clock authorityClock = Clock.fixed(authorityNow, ZoneOffset.UTC);
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayWorldCatalog.WorldView world = worldCatalog.resolveWorld("demo").orElseThrow();
    GameplayWorldCatalog.RealmDiscoverySnapshot snapshot =
        worldCatalog.readRealmDiscoverySnapshot(world);
    DirectTextConnectScopeSessionStore scopeStore =
        Mockito.spy(DirectTextConnectScopeSessionStore.inMemoryForTest());
    scopeStore.replaceRealmSnapshot(
        context,
        "demo",
        22L,
        "demo",
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        List.of(),
        authorityNow);
    PlayCommandHandler clockHandler = handlerWithClock(authorityClock, scopeStore);

    clockHandler.handle(
        "1", new TextCommand(TextCommandType.PLAY, List.of("demo", "999"), "PLAY demo 999"));

    Mockito.verify(scopeStore).realmsSnapshot(context, 22L, "demo", authorityNow);
  }

  @ParameterizedTest
  @ValueSource(strings = {"read-unavailable", "malformed"})
  void numericRealmSelectorMapsPointerExceptionsToTypedFailures(String pointerFailure) {
    Instant authorityNow = Instant.parse("2030-05-06T07:08:09Z");
    Clock authorityClock = Clock.fixed(authorityNow, ZoneOffset.UTC);
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayWorldCatalog baseCatalog = worldCatalog;
    GameplayWorldCatalog.DiscoverySnapshot worldSnapshot = baseCatalog.readDiscoverySnapshot();
    GameplayWorldCatalog.WorldView world = baseCatalog.resolveWorld("demo").orElseThrow();
    GameplayWorldCatalog.RealmDiscoverySnapshot realmSnapshot =
        baseCatalog.readRealmDiscoverySnapshot(world);
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    scopeStore.replaceRealmSnapshot(
        context,
        "demo",
        22L,
        "demo",
        realmSnapshot.catalogFingerprint(),
        realmSnapshot.ordinalTargets(),
        List.of(),
        authorityNow);
    GameplayWorldCatalog catalog = Mockito.spy(baseCatalog);
    Mockito.doReturn(worldSnapshot).when(catalog).readDiscoverySnapshot();
    if ("read-unavailable".equals(pointerFailure)) {
      Mockito.doThrow(
              new GameplayWorldCatalog.AuthorityPointerReadUnavailableException("read failed"))
          .when(catalog)
          .readRealmDiscoverySnapshot(Mockito.any(GameplayWorldCatalog.WorldView.class));
    } else {
      Mockito.doThrow(new GameplayWorldCatalog.AuthorityPointerUnavailableException("malformed"))
          .when(catalog)
          .readRealmDiscoverySnapshot(Mockito.any(GameplayWorldCatalog.WorldView.class));
    }
    PlayCommandHandler pointerFailureHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            catalog,
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry,
            scopeStore,
            authorityClock);

    PlayCommandHandlingResult result =
        pointerFailureHandler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo", "1"), "PLAY demo 1"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(
            "read-unavailable".equals(pointerFailure)
                ? GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE
                : "ADMISSION_POINTER_UNAVAILABLE");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void numericWorldPlayTreatsUnrecognizedSecondSelectorAsCharacterWhenDefaultRealmIsUnambiguous() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayWorldCatalog.DiscoverySnapshot snapshot = worldCatalog.readDiscoverySnapshot();
    connectScopeSessionStore.replaceWorldSnapshot(
        context.sessionId(),
        context.accountId(),
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        java.time.Instant.now());
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Sora"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("Sora")
                    .build()));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "7001", "Sora", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("1", "Sora"), "PLAY 1 Sora"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as Sora");
  }

  @Test
  void numericWorldCharacterPlayRejectsStaleWorldSnapshotBeforeAdmissionSideEffects() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayWorldCatalog.DiscoverySnapshot snapshot = worldCatalog.readDiscoverySnapshot();
    connectScopeSessionStore.replaceWorldSnapshot(
        context.sessionId(),
        context.accountId(),
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        java.time.Instant.now());
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setPointerVersion(2L);

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("1", "Sora"), "PLAY 1 Sora"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        firstPartyConnectContextRegistry,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void numericRealmPlayWithoutRealmsSnapshotNeverFallsThroughAsCharacter() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo", "1"), "PLAY demo 1"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        firstPartyConnectContextRegistry,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void publicNumericPlayIgnoresPointerChangesToDeniedPrivateRealms() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());
    GameplayCatalogProperties.World demoWorld = gameplayCatalogProperties.getWorlds().getFirst();
    GameplayCatalogProperties.Realm hiddenPrivateRealm =
        realm("vault", "Private Vault", 22L, 99L, true, false);
    hiddenPrivateRealm.setPublicProductionRealm(false);
    List<GameplayCatalogProperties.Realm> demoRealms = new ArrayList<>(demoWorld.getRealms());
    demoRealms.add(hiddenPrivateRealm);
    demoWorld.setRealms(demoRealms);
    GameplayWorldCatalog.WorldView world = worldCatalog.resolveWorld("demo").orElseThrow();
    List<GameplayWorldCatalog.RealmView> responseRealms =
        world.realms().stream()
            .filter(GameplayWorldCatalog.RealmView::publicProductionRealm)
            .toList();
    retainRealmSnapshot(context, "demo", world, responseRealms);

    hiddenPrivateRealm.setPointerVersion(2L);

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "1", "demo"), "PLAY demo 1 demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as demo");
    Mockito.verify(sessionContextService).save(Mockito.any());
  }

  @Test
  void numericPlayRealmRejectsChangedTargetThatWasShownToTheCaller() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayCatalogProperties.World demoWorld = gameplayCatalogProperties.getWorlds().getFirst();
    GameplayCatalogProperties.Realm shownPrivateRealm =
        realm("vault", "Private Vault", 22L, 99L, true, false);
    shownPrivateRealm.setPublicProductionRealm(false);
    List<GameplayCatalogProperties.Realm> demoRealms = new ArrayList<>(demoWorld.getRealms());
    demoRealms.add(shownPrivateRealm);
    demoWorld.setRealms(demoRealms);
    GameplayWorldCatalog.WorldView world = worldCatalog.resolveWorld("demo").orElseThrow();
    retainRealmSnapshot(context, "demo", world, world.realms());
    shownPrivateRealm.setPointerVersion(2L);

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "2", "demo"), "PLAY demo 2 demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void numericPlayStillDeniesWhenSelectedPrivateGrantHasBeenRevoked() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayCatalogProperties.World demoWorld = gameplayCatalogProperties.getWorlds().getFirst();
    GameplayCatalogProperties.Realm privateRealm =
        realm("preview", "Preview Realm", 22L, 99L, true, true);
    List<GameplayCatalogProperties.Realm> demoRealms = new ArrayList<>(demoWorld.getRealms());
    demoRealms.add(privateRealm);
    demoWorld.setRealms(demoRealms);
    GameplayWorldCatalog.WorldView world = worldCatalog.resolveWorld("demo").orElseThrow();
    retainRealmSnapshot(
        context,
        "demo",
        world,
        world.realms().stream().filter(realm -> realm.slug().equals("preview")).toList());
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.eq("demo"),
            Mockito.eq("preview"),
            Mockito.anyString()))
        .thenReturn(
            validGrant("123", "22", "demo", "preview").toBuilder().setGranted(false).build());

    PlayCommandHandlingResult denied =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "1", "Emberline"),
                "PLAY demo 1 Emberline",
                null,
                new TextCommandPayload.PlayRequest("demo", "1", "Emberline")));
    PlayCommandHandlingResult unknown =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "unlisted", "Emberline"),
                "PLAY demo unlisted Emberline",
                null,
                new TextCommandPayload.PlayRequest("demo", "unlisted", "Emberline")));

    assertHiddenRealmDenialMatchesUnknownSelection(denied, unknown, "preview");
    Mockito.verify(accountClient, Mockito.times(1))
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
            Mockito.eq("22"),
            Mockito.eq("demo"),
            Mockito.eq("preview"),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void numericPlayRealmUsesTenantQualifiedSnapshotForDuplicateWorldSlug() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GameplayCatalogProperties.World secondTenantWorld =
        world(
            "demo",
            "Demo World - Second Tenant",
            List.of(realm("production", "Second Tenant Live Realm", 23L, 3L, true, false)));
    List<GameplayCatalogProperties.World> configuredWorlds =
        new ArrayList<>(gameplayCatalogProperties.getWorlds());
    configuredWorlds.add(secondTenantWorld);
    gameplayCatalogProperties.setWorlds(configuredWorlds);

    GameplayWorldCatalog.DiscoverySnapshot worlds = worldCatalog.readDiscoverySnapshot();
    DirectTextConnectScopeSessionStore.WorldOrdinalTarget selectedWorldTarget =
        worlds.ordinalTargets().stream()
            .filter(target -> target.tenantId() == 23L)
            .findFirst()
            .orElseThrow();
    GameplayWorldCatalog.WorldView selectedWorld =
        worlds.visibleWorlds().stream()
            .filter(
                candidate -> candidate.realms().stream().anyMatch(realm -> realm.tenantId() == 23L))
            .findFirst()
            .orElseThrow();
    GameplayWorldCatalog.RealmDiscoverySnapshot realms =
        worldCatalog.readRealmDiscoverySnapshot(selectedWorld);
    String worldSelector = Integer.toString(selectedWorldTarget.ordinal());
    connectScopeSessionStore.replaceWorldSnapshot(
        context.sessionId(),
        context.accountId(),
        worlds.catalogFingerprint(),
        worlds.ordinalTargets(),
        Instant.now());
    connectScopeSessionStore.replaceRealmSnapshot(
        context,
        worldSelector,
        23L,
        selectedWorld.slug(),
        realms.catalogFingerprint(),
        realms.ordinalTargets(),
        List.of(),
        Instant.now());
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Sora"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("Sora")
                    .build()));
    Mockito.doAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .active(123L, 23L, "1")),
                        invocation.getArgument(0)))
        .when(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.eq("23"), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("23")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
    when(sessionAuthenticationService.resolveByGameplayIdentity(23L, 3L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of(worldSelector, "1", "Sora"),
                "PLAY " + worldSelector + " 1 Sora"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verify(moderationPolicyClient).evaluateGameplayAdmission(23L, 123L);
    Mockito.verify(sessionContextService)
        .save(
            Mockito.argThat(
                saved ->
                    saved.tenantId() == 23L
                        && saved.gameInstanceId() == 3L
                        && "demo".equals(saved.worldSlug())
                        && "production".equals(saved.realmSlug())));
  }

  @Test
  void numericPlayRealmFailsClosedForWrongTenantOrStaleRealmSnapshot() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    List<GameplayCatalogProperties.World> configuredWorlds =
        new ArrayList<>(gameplayCatalogProperties.getWorlds());
    configuredWorlds.add(
        world(
            "demo",
            "Demo World - Second Tenant",
            List.of(realm("production", "Second Tenant Live Realm", 23L, 3L, true, false))));
    gameplayCatalogProperties.setWorlds(configuredWorlds);

    GameplayWorldCatalog.DiscoverySnapshot worlds = worldCatalog.readDiscoverySnapshot();
    DirectTextConnectScopeSessionStore.WorldOrdinalTarget selectedWorldTarget =
        worlds.ordinalTargets().stream()
            .filter(target -> target.tenantId() == 23L)
            .findFirst()
            .orElseThrow();
    GameplayWorldCatalog.WorldView selectedWorld =
        worlds.visibleWorlds().stream()
            .filter(
                candidate -> candidate.realms().stream().anyMatch(realm -> realm.tenantId() == 23L))
            .findFirst()
            .orElseThrow();
    GameplayWorldCatalog.RealmDiscoverySnapshot realms =
        worldCatalog.readRealmDiscoverySnapshot(selectedWorld);
    String worldSelector = Integer.toString(selectedWorldTarget.ordinal());
    connectScopeSessionStore.replaceWorldSnapshot(
        context.sessionId(),
        context.accountId(),
        worlds.catalogFingerprint(),
        worlds.ordinalTargets(),
        Instant.now());

    // A snapshot for the colliding slug under the other tenant must not bind this selection.
    connectScopeSessionStore.replaceRealmSnapshot(
        context,
        worldSelector,
        22L,
        selectedWorld.slug(),
        realms.catalogFingerprint(),
        realms.ordinalTargets(),
        List.of(),
        Instant.now());
    PlayCommandHandlingResult wrongTenantResult =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of(worldSelector, "1"), "PLAY " + worldSelector + " 1"));
    assertThat(wrongTenantResult.commandResult().accepted()).isFalse();
    assertThat(wrongTenantResult.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");

    // The right tenant key still fails when its retained REALMS fingerprint is stale.
    connectScopeSessionStore.replaceRealmSnapshot(
        context,
        worldSelector,
        23L,
        selectedWorld.slug(),
        "stale-fingerprint",
        realms.ordinalTargets(),
        List.of(),
        Instant.now());
    PlayCommandHandlingResult staleResult =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of(worldSelector, "1"), "PLAY " + worldSelector + " 1"));

    assertThat(staleResult.commandResult().accepted()).isFalse();
    assertThat(staleResult.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, moderationPolicyClient);
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
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
  void playRejectsPointerRevisionCutoverBeforeAdmissionReads() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot pointerA = admissionPointer(1L);
    GameplayAdmissionPointerSnapshot pointerB = admissionPointer(2L);
    AtomicInteger pointerReads = new AtomicInteger();
    when(authorityService.listPointers()).thenReturn(List.of(pointerA));
    when(authorityService.listPointersByTenant(22L))
        .thenAnswer(
            invocation -> {
              pointerReads.incrementAndGet();
              return List.of(pointerB);
            });
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
            meterRegistry,
            connectScopeSessionStore);
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        authorityBackedHandler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().errorCode()).isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
    assertThat(pointerReads).hasValue(1);
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @ParameterizedTest
  @ValueSource(strings = {"throw", "null"})
  void playMapsFinalPointerReadOutageToAuthUnavailableWithoutAdmissionSideEffects(
      String failureMode) {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    AtomicInteger pointerReads = new AtomicInteger();
    when(authorityService.listPointers()).thenReturn(List.of(admissionPointer(1L)));
    when(authorityService.listPointersByTenant(22L))
        .thenAnswer(
            invocation -> {
              pointerReads.incrementAndGet();
              if ("throw".equals(failureMode)) {
                throw new DataAccessException("authority down on final read");
              }
              return null;
            });
    PlayCommandHandler authorityBackedHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            new GameplayWorldCatalog(authorityService),
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry,
            connectScopeSessionStore);
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        authorityBackedHandler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().getFirst().payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    assertThat(pointerReads).hasValue(1);
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
    Mockito.verify(firstPartyConnectContextRegistry, never())
        .register(Mockito.anyLong(), Mockito.any());
    Mockito.verify(firstPartyConnectContextRegistry, never()).unregister(Mockito.anyLong());
  }

  @Test
  void playMapsPointerAuthorityReadFailureBeforeAccountOrEntityAdmissionReads() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    when(authorityService.listPointers()).thenThrow(new DataAccessException("authority down"));
    PlayCommandHandler authorityBackedHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            new GameplayWorldCatalog(authorityService),
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry,
            connectScopeSessionStore);
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        authorityBackedHandler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("AUTH_UNAVAILABLE");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void playMapsMalformedDiscoveryPointerToAdmissionPointerUnavailableWithoutSideEffects() {
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
                    0L,
                    11L,
                    1L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    1L,
                    UUID.randomUUID(),
                    UUID.randomUUID())));
    PlayCommandHandler authorityBackedHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            new GameplayWorldCatalog(authorityService),
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry,
            connectScopeSessionStore);
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        authorityBackedHandler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @ParameterizedTest
  @ValueSource(strings = {"throw", "null"})
  void playMapsDefaultRealmPointerReadFailureToAuthUnavailableWithoutAdmissionSideEffects(
      String failureMode) {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    AtomicInteger pointerReads = new AtomicInteger();
    when(authorityService.listPointers())
        .thenAnswer(
            invocation -> {
              if (pointerReads.getAndIncrement() == 0) {
                return List.of(admissionPointer(1L));
              }
              if ("throw".equals(failureMode)) {
                throw new DataAccessException("authority down during default realm resolution");
              }
              return null;
            });
    PlayCommandHandler authorityBackedHandler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            sessionRoutingNormalizationService,
            new GameplayWorldCatalog(authorityService),
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            moderationPolicyClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            scriptEventPublisher,
            meterRegistry,
            connectScopeSessionStore);
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        authorityBackedHandler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().getFirst().payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    assertThat(pointerReads).hasValue(2);
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
    Mockito.verify(firstPartyConnectContextRegistry, never())
        .register(Mockito.anyLong(), Mockito.any());
    Mockito.verify(firstPartyConnectContextRegistry, never()).unregister(Mockito.anyLong());
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
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
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(moderationPolicyClient.evaluateGameplayAdmission(22L, 123L))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(false)
                .setAction("gameplay_ban")
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("MODERATION_POLICY_DENIED");
    Mockito.verify(sessionContextService, Mockito.never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, Mockito.never())
        .registerConnected(Mockito.any());
  }

  @Test
  void playUsesPersistedAccountRosterCharacterForSelectedTarget() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "9007", "Emberline", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
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
                123L,
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
                123L,
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
                123L,
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "7001", "Emberline", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as Emberline");
  }

  @Test
  void playWithCaseVariedCharacterSelectorShowsPersistedRosterCharacterName() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "7001", "Emberline", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "abc", "Emberline", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "8008", "demo", "23", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsDuplicatePersistedCharacterIdsBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    ListCharactersByAccountResponse roster =
        characterRoster("8008", "demo", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .addCharacters(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("8008")
                    .setTenantId("22")
                    .setAccountId("123")
                    .setName("other")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .build())
            .build();
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    ListCharactersByAccountResponse roster =
        characterRoster("8008", "demo", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .addCharacters(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("8009")
                    .setTenantId("22")
                    .setAccountId("123")
                    .setName("Demo")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .build())
            .build();
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(roster);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playWithEmptyCharacterRosterReturnsSelectionGuidanceBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.newBuilder().build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playWithoutCharacterSelectorReturnsSelectionGuidanceForMultipleCharacters() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    ListCharactersByAccountResponse roster =
        characterRoster("8008", "demo", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .addCharacters(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("8009")
                    .setTenantId("22")
                    .setAccountId("123")
                    .setName("Emberline")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .build())
            .build();
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(roster);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playWithUnmatchedCharacterSelectorReturnsGuidanceBeforeBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
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
  void playResumeRejectsChangedRosterIdentityWithoutBinding() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
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
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void playRejectsEntityRosterErrorWithoutBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Mockito.doReturn(
            ListCharactersByAccountResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build())
        .when(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
  }

  @Test
  void playReadsMembershipGrantAndEntitlementBeforeActorRoster() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "41",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    org.mockito.InOrder order = Mockito.inOrder(accountClient, entityManagementClient);
    order
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
    order
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    order
        .verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
            Mockito.eq("22"),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
    order
        .verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void playResumeDoesNotPublishSpawnEvent() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
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
            "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            characterRoster(
                "7001", "Emberline", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    SessionContext clearedExisting =
        new SessionContext(9L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, "old-jwt", 1L);
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
                123L,
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    SessionContext existingWithoutRoom =
        new SessionContext(
            9L,
            22L,
            123L,
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
                123L,
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
  void firstPartyPlayRejectsMismatchedConnectScope() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "first-party:123", 0L, null, 0L, null, null, 41L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    123L,
                    22L,
                    "demo",
                    "production",
                    41L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Sora"),
                "PLAY sandbox preview Sora"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void firstPartyPlayRejectsMismatchedWorldSlug() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "first-party:123", 0L, null, 0L, null, null, 1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    123L,
                    22L,
                    "sandbox",
                    "production",
                    1L,
                    1L,
                    "scope-1",
                    "jti-1",
                    "req-1",
                    "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void firstPartyPlayRejectsStalePointerVersion() {
    gameplayCatalogProperties.getWorlds().get(0).getRealms().get(0).setPointerVersion(9L);
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "first-party:123", 0L, null, 0L, null, null, 1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    123L, 22L, "demo", "production", 1L, 8L, "scope-1", "jti-1", "req-1", "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void firstPartyPlayRejectsConnectContextMissingRealmSlug() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "first-party:123", 0L, null, 0L, null, null, 1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    123L, 22L, "demo", null, 1L, 1L, "scope-1", "jti-1", "req-1", "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
    assertThat(joinedOutputText(result.outputs()))
        .isEqualTo("ERROR CONNECT_CONTEXT_INVALID Connect context invalid");
  }

  @Test
  void firstPartyPlayRejectsConnectContextMissingConnectScope() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "first-party:123", 0L, null, 0L, null, null, 1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    123L, 22L, "demo", "production", 1L, 1L, "", "jti-1", "req-1", "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
  }

  @Test
  void firstPartyPlayRejectsConnectContextMissingConnectRequest() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "first-party:123", 0L, null, 0L, null, null, 1L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    123L, 22L, "demo", "production", 1L, 1L, "scope-1", "jti-1", "", "gw-1")));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("CONNECT_CONTEXT_INVALID");
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
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of(), PLAY_COMMAND_NAME));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  void unknownWorldReturnsSelectionGuidance() {
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
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
            meterRegistry,
            connectScopeSessionStore);
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        authorityBackedHandler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "production"), "PLAY demo production"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
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
            meterRegistry,
            connectScopeSessionStore);
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));

    PlayCommandHandlingResult result =
        pointerBackedHandler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PLAY_SELECTION_REQUIRED");
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(scriptEventPublisher, never()).publishCommandEvent(Mockito.any(), Mockito.any());
  }

  @Test
  void sandboxWithoutCharacterReturnsSelectionRequired() {
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
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
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
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
            "Selection required. Use PLAY sandbox preview <character> with a known character; character browsing is currently unavailable.");
    assertThat(result.commandResult().errorMessage()).doesNotContain("CHARS");
    TextPlayerOutputRenderer renderer = new TextPlayerOutputRenderer(new PresentationProperties());
    assertThat(renderer.render(result.outputs().getFirst(), "fr"))
        .isEqualTo(
            "ERROR PLAY_SELECTION_REQUIRED Sélection requise. Utilisez PLAY sandbox preview <character> avec un personnage connu ; la consultation des personnages n’est pas disponible actuellement.");
    String fallback = renderer.render(result.outputs().getFirst(), "de");
    assertThat(fallback)
        .isEqualTo(
            "ERROR PLAY_SELECTION_REQUIRED Selection required. Use PLAY sandbox preview <character> with a known character; character browsing is currently unavailable.");
    assertThat(fallback).doesNotContain("CHARS");
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
    SessionContext context = new SessionContext(1L, 22L, 123L, 0L, 0L, "jwt-token");
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
  void firstPartyPlayAcceptsNonProductionRealmWhenScopeMatches() {
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .get(1)
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "first-party:123", 0L, null, 0L, null, null, 41L);
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(firstPartyConnectContextRegistry.find(1L))
        .thenReturn(
            Optional.of(
                new FirstPartyConnectContext(
                    123L, 22L, "sandbox", "preview", 41L, 1L, "scope-1", "jti-1", "req-1",
                    "gw-1")));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(
            characterRoster(
                "7002", "Sora", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 41L, 7002L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Sora"),
                "PLAY sandbox preview Sora"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
  }

  @Test
  void firstPartyPlayFallsBackToPersistedSelectorWhenRegistryEntryIsMissing() {
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
            123L,
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
            "req-persisted");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.listCharactersByAccount(
            "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(
            characterRoster(
                "7002", "Sora", "22", "123", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 41L, 7002L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(TextCommandType.PLAY, List.of("sandbox", "Sora"), "PLAY sandbox Sora"));

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
            123L,
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
  void playPrivateMembershipDenialUsesNonEnumeratingSelectionFailure() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
            123L, 22L));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    ErrorOutput error = (ErrorOutput) result.outputs().get(0).payload();
    assertThat(error.messageKey()).isEqualTo("error.play.selection-required");
    assertThat(error.arguments()).isEmpty();
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
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
            123L,
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
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing(123L, 22L)),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    ErrorOutput error = (ErrorOutput) result.outputs().get(0).payload();
    assertThat(error.messageKey()).isEqualTo("error.play.selection-required");
    assertThat(error.arguments()).isEmpty();
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
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
  void playDeniedDoesNotClearCrossTenantSameSlugBinding() {
    SessionContext context =
        new SessionContext(
            1L, 23L, 123L, "demo@example.com", 7001L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
            123L, 22L));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PUBLIC_PRODUCTION_ADMISSION_DENIED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsMalformedActiveMembershipEvidenceWithoutGameplaySideEffects() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                                net.firedevops.firemud.gamesession.support
                                    .RuntimeMembershipTestFixtures.active(123L, 22L, "2"))
                            .toBuilder()
                            .setGameplayAdmissionAllowed(false)
                            .build(),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    org.mockito.InOrder order = Mockito.inOrder(accountClient);
    order
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
    order
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing(123L, 22L)),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    ErrorOutput error = (ErrorOutput) result.outputs().get(0).payload();
    assertThat(error.messageKey()).isEqualTo("error.play.selection-required");
    assertThat(error.arguments()).isEmpty();
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @Test
  void playNonPublicLeftMembershipRequiresEnrollment() {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                123L, 22L, List.of("designer", "player")));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.selection-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        moderationPolicyClient,
        scriptEventPublisher);
  }

  @Test
  void playBoundNonPublicLeftMembershipRequiresEnrollmentAndClearsBinding() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                123L, 22L, List.of("designer", "player")));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.selection-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    org.mockito.InOrder order = Mockito.inOrder(accountClient);
    order
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
    order
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "non_public_enrollment_required");
    Mockito.verify(sessionContextService)
        .save(
            Mockito.argThat(
                saved ->
                    saved.characterId() == 0L
                        && saved.characterName() == null
                        && saved.gameInstanceId() == 0L
                        && saved.roomInstanceId() == null));
    Mockito.verifyNoInteractions(
        entityManagementClient, moderationPolicyClient, scriptEventPublisher);
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
        .thenReturn(validGrant());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
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
        .thenReturn(GetRealmAccessGrantForRuntimeResponse.newBuilder().setGranted(false).build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "realm_access_denied");
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
            Mockito.eq("22"),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
  }

  @Test
  void playNonPublicRealmDoesNotConsumePublicJoinPolicy() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
            Mockito.eq("22"),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
  }

  @Test
  void privateOnlyWorldPlayDenialMatchesUnknownAndGrantedAccessStillWorks() {
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(123L, 22L),
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.active(
            123L, 22L, "1"));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString()))
        .thenReturn(validGrant());

    PlayCommandHandlingResult denied = handler.handle("1", previewRealmPlayCommand());
    PlayCommandHandlingResult unknown =
        handler.handle(
            "1", new TextCommand(TextCommandType.PLAY, List.of("unknown"), "PLAY unknown"));
    PlayCommandHandlingResult granted = handler.handle("1", previewRealmPlayCommand());

    assertThat(denied.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(unknown.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(granted.commandResult()).isEqualTo(CommandEnqueueResult.success());
  }

  @Test
  void playCharacterShorthandDoesNotDistinguishHiddenRealmFromUnknownName() {
    addNonPublicRealmToPublicDemoWorld(false);
    when(sessionAuthenticationService.resolveSessionContext("1"))
        .thenReturn(Optional.of(loggedInUnboundContext()));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
            123L, 22L));

    PlayCommandHandlingResult hidden =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "invite-only"), "PLAY demo invite-only"));
    PlayCommandHandlingResult unknown =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY, List.of("demo", "unlisted"), "PLAY demo unlisted"));

    assertThat(hidden.commandResult()).isEqualTo(unknown.commandResult());
    assertThat(hidden.outputs()).isEqualTo(unknown.outputs());
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playHiddenRealmInPublicWorldMasksMembershipDenialLikeUnknownSelection() {
    addNonPublicRealmToPublicDemoWorld(false);
    SessionContext context = loggedInUnboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
            123L, 22L));

    PlayCommandHandlingResult denied =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "invite-only", "Emberline"),
                "PLAY demo invite-only Emberline"));
    PlayCommandHandlingResult unknown =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "unlisted", "Emberline"),
                "PLAY demo unlisted Emberline"));

    assertHiddenRealmDenialMatchesUnknownSelection(denied, unknown, "invite-only");
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playUnlistedNonPublicRealmInPublicWorldMasksDeniedGrantLikeUnknownSelection() {
    addNonPublicRealmToPublicDemoWorld(true);
    SessionContext context = loggedInUnboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.active(
            123L, 22L, "1"));
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.eq("demo"),
            Mockito.eq("invite-only"),
            Mockito.anyString()))
        .thenReturn(GetRealmAccessGrantForRuntimeResponse.newBuilder().setGranted(false).build());

    PlayCommandHandlingResult denied =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "invite-only", "Emberline"),
                "PLAY demo invite-only Emberline"));
    PlayCommandHandlingResult unknown =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "unlisted", "Emberline"),
                "PLAY demo unlisted Emberline"));

    assertHiddenRealmDenialMatchesUnknownSelection(denied, unknown, "invite-only");
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
            Mockito.eq("22"),
            Mockito.eq("demo"),
            Mockito.eq("invite-only"),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playHiddenRealmInPublicWorldDoesNotMaskUnavailableMembershipAuthority() {
    addNonPublicRealmToPublicDemoWorld(false);
    SessionContext context = loggedInUnboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("AUTH_UNAVAILABLE")
                        .setMessage("Membership authority unavailable")
                        .build())
                .build());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "invite-only", "Emberline"),
                "PLAY demo invite-only Emberline"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    ErrorOutput error = (ErrorOutput) result.outputs().getFirst().payload();
    assertThat(error.arguments()).isEmpty();
    assertThat(error.message()).doesNotContain("invite-only", "51");
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    verifyNoGameplayBindingSideEffects();
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
        .thenReturn(GetRealmAccessGrantForRuntimeResponse.newBuilder().setGranted(false).build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "realm_access_denied");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PERMISSION_DENIED", "FAILED_PRECONDITION"})
  void playInvisibleRealmWithNonAuthorityGrantClearsBinding(String errorCode) {
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
                        .setCode(errorCode)
                        .setMessage("grant denied")
                        .build())
                .build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(context, "realm_access_denied");
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

  @ParameterizedTest
  @ValueSource(strings = {"NOT_FOUND", "PERMISSION_DENIED", "FAILED_PRECONDITION"})
  void playNonAuthorityMembershipErrorReturnsAccessDeniedAndClearsBinding(String errorCode) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
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
    assertPlayJoinRequiredPreservesAuthenticationAndSupportsExplicitJoin("MISSING", 0L);
  }

  @Test
  void playPublicMissingMembershipReturnsJoinRequiredBeforeCharacterRosterLookup() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing(123L, 22L)),
                        invocation.getArgument(0)));
    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    verifyNoGameplayBindingSideEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ACTIVE", "UNKNOWN"})
  void malformedMembershipLifecycleCannotBecomeJoinOpportunity(String lifecycleState) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .missing(123L, 22L));
              var malformedLifecycle =
                  response.toBuilder()
                      .setMembershipLifecycleState(lifecycleState)
                      .setMembershipBaseline(
                          response.getMembershipBaseline().toBuilder()
                              .setMembershipLifecycleState(lifecycleState))
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(malformedLifecycle, invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "INACTIVE"})
  void nonAdmittingMembershipLifecycleWithAdmissionFlagIsDenied(String lifecycleState) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  "INACTIVE".equals(lifecycleState)
                      ? freshMembership(
                          net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                              .inactive(123L, 22L, "3"))
                      : freshMembership(
                          net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                              .missing(123L, 22L));
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      response.toBuilder().setGameplayAdmissionAllowed(true).build(),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
  }

  @Test
  void playLeftMembershipRequiresJoinForPublicProduction() {
    SessionContext context = unboundContext();
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                123L, 22L, List.of("designer", "player")));
    var leftEvent =
        MembershipAuthorityEventV1Codec.verify(
            leftMembership.getOutboxSourceEvidence(0).getCanonicalEventJson());
    assertThat(leftMembership.getMembershipExists()).isTrue();
    assertThat(leftMembership.getGameplayAdmissionAllowed()).isFalse();
    assertThat(leftMembership.getMembershipLifecycleState()).isEqualTo("INACTIVE");
    assertThat(leftMembership.getAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(leftMembership.getAccountId())
        .isEqualTo("00000000-0000-0000-0000-000000000123");
    assertThat(leftMembership.getTenantId())
        .isEqualTo("00000000-0000-0000-0000-000000000022");
    assertThat(leftMembership.getRequestAccountId()).isEqualTo("123");
    assertThat(leftMembership.getRequestTenantId()).isEqualTo("22");
    assertThat(leftMembership.getMembershipVersionMap())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "3"));
    assertThat(leftMembership.getMembershipAuthorityGeneration()).isEqualTo("2");
    assertThat(leftMembership.getIssuanceFence()).isEqualTo("2");
    assertThat(leftMembership.getRolesList()).containsExactly("designer", "player");
    assertThat(leftMembership.getMembershipBaseline().getMembershipLifecycleState())
        .isEqualTo("INACTIVE");
    assertThat(leftMembership.getMembershipBaseline().getMembershipVersionMap())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "3"));
    assertThat(leftMembership.getMembershipBaseline().getMembershipAuthorityGeneration())
        .isEqualTo("2");
    var authorityTuple = leftMembership.getAuthorityTuple();
    assertThat(authorityTuple.getIssuerAuthGeneration()).isEqualTo("1");
    assertThat(authorityTuple.getAccountAuthorityGeneration()).isEqualTo("1");
    assertThat(authorityTuple.getTenantAuthorityGenerationMap())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "1"));
    assertThat(authorityTuple.getMembershipAuthorityGenerationMap())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "2"));
    assertThat(authorityTuple.getPrivateRealmGrantVersionsList()).isEmpty();
    assertThat(authorityTuple.hasAccountSecurityCutoff()).isFalse();
    assertThat(authorityTuple.hasTenantBillingCutoff()).isFalse();
    assertThat(leftMembership.getOutboxCheckpointsList())
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.getOutboxStreamKey()).isEqualTo(leftEvent.outboxStreamKey());
              assertThat(checkpoint.getOutboxSequence()).isEqualTo("2");
            });
    assertThat(leftMembership.getOutboxSourceEvidence(0).getEventId())
        .isEqualTo(leftEvent.eventId());
    assertThat(leftMembership.getOutboxSourceEvidence(0).getEventDigest())
        .isEqualTo(leftEvent.eventDigest());
    assertThat(leftMembership.getOutboxSourceEvidence(0).getCanonicalEventJson())
        .isEqualTo(leftEvent.canonicalJson());
    assertThat(leftEvent.outboxSequence()).isEqualTo("2");
    assertThat(leftEvent.membershipVersion())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "3"));
    assertThat(leftEvent.membershipAuthorityGeneration()).isEqualTo("2");
    assertThat(leftEvent.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(leftEvent.authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(leftEvent.authorityTuple().tenantAuthorityGeneration())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "1"));
    assertThat(leftEvent.authorityTuple().membershipAuthorityGeneration())
        .containsExactly(Map.entry(leftMembership.getTenantId(), "2"));
    assertThat(leftEvent.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(leftEvent.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(leftEvent.authorityTuple().tenantBillingCutoff()).isEmpty();
    assertThat(leftEvent.issuanceFence()).isEqualTo("2");
    assertThat(leftEvent.callerBoundAuthorityInvalidated()).isTrue();
    assertThat(leftEvent.roles()).containsExactly("designer", "player");
    assertThat(leftEvent.gameplayAdmissionAllowed()).isFalse();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playPrivateDeniedReturnsPrivacyOutcomeBeforeCharacterRosterLookup() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .inactive(123L, 22L, "4")),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRequiresExplicitJoinWhenPublicProductionMembershipIsInactive() {
    assertPlayJoinRequiredPreservesAuthenticationAndSupportsExplicitJoin("INACTIVE", 4L);
  }

  @Test
  void playPublicAdmissionDeniedWhenFreshEntitlementsDisallowJoin() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 0L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
            123L, 22L));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.PUBLIC_PRODUCTION_ADMISSION_DENIED_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.public-production-admission-denied");
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"outage", "malformed", "stale"})
  void playPublicJoinRequiresFreshEntitlementEvidence(String evidence) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 0L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing(123L, 22L)),
                        invocation.getArgument(0)));
    GetTenantEntitlementsForRuntimeResponse entitlementResponse;
    if ("outage".equals(evidence)) {
      entitlementResponse =
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE)
                      .setMessage(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE))
              .build();
    } else {
      GetTenantEntitlementsForRuntimeResponse.Builder builder = publicEntitlement(true).toBuilder();
      if ("malformed".equals(evidence)) {
        builder.clearTenantId();
      } else {
        builder.setEvaluatedAt(Instant.now().minusSeconds(16).toString());
      }
      entitlementResponse = builder.build();
    }
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(entitlementResponse);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.entitlement-unavailable");
    Mockito.verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verifyNoInteractions(sessionContextService, gameplayPresenceLifecycleService);
  }

  @Test
  void playBoundLeftMembershipRequiresJoinAndClearsBindingForPublicProduction() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    var leftMembership =
        freshMembership(
            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
                123L, 22L, List.of("designer", "player")));
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(leftMembership, invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    assertThat(result.outputs()).hasSize(1);
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");
    Mockito.verify(sessionContextService)
        .save(
            Mockito.argThat(
                saved ->
                    saved.characterId() == 0L
                        && saved.characterName() == null
                        && saved.gameInstanceId() == 0L
                        && saved.roomInstanceId() == null));
    Mockito.verifyNoInteractions(
        entityManagementClient, moderationPolicyClient, scriptEventPublisher);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "membership-generation",
        "issuance-fence",
        "zero-sequence",
        "removed-event",
        "altered-roles",
        "admission-allowed"
      })
  void contradictoryLeftEventEvidenceCannotAdmitGameplay(String defect) {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(leftMembershipWithDefect(defect)),
                        invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).payload()).isInstanceOf(ErrorOutput.class);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.world-access-denied");
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        moderationPolicyClient,
        scriptEventPublisher);
  }

  @Test
  void playAuthorityFreshnessUsesInjectedClockAtExactInclusiveBoundaries() {
    Instant authorityNow = Instant.parse("2030-05-06T07:08:09Z");
    handler = handlerWithClock(Clock.fixed(authorityNow, ZoneOffset.UTC));
    List<String> evaluatedAtValues =
        List.of(
            authorityNow.toString(),
            authorityNow.minusSeconds(15).toString(),
            authorityNow.minusSeconds(15).minusNanos(1).toString(),
            authorityNow.plusNanos(1).toString());
    SessionContext unboundContext =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1"))
        .thenReturn(Optional.of(unboundContext));

    for (int index = 0; index < evaluatedAtValues.size(); index++) {
      String evaluatedAt = evaluatedAtValues.get(index);
      Mockito.doAnswer(
              invocation ->
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                      .echoRequestId(missingMembershipAt(evaluatedAt), invocation.getArgument(0)))
          .when(accountClient)
          .getTenantMembershipForRuntime(
              Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
      Mockito.doReturn(publicEntitlement(true, authorityNow.toString()))
          .when(accountClient)
          .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());

      PlayCommandHandlingResult result =
          handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

      assertThat(result.commandResult().errorCode())
          .isEqualTo(
              index < 2
                  ? GameplayStageCommandConstants.JOIN_REQUIRED_CODE
                  : GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    }

    for (int index = 0; index < evaluatedAtValues.size(); index++) {
      String evaluatedAt = evaluatedAtValues.get(index);
      Mockito.doAnswer(
              invocation ->
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                      .echoRequestId(
                          missingMembershipAt(authorityNow.toString()), invocation.getArgument(0)))
          .when(accountClient)
          .getTenantMembershipForRuntime(
              Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
      Mockito.doReturn(publicEntitlement(true, evaluatedAt))
          .when(accountClient)
          .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());

      PlayCommandHandlingResult result =
          handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

      assertThat(result.commandResult().errorCode())
          .isEqualTo(
              index < 2
                  ? GameplayStageCommandConstants.JOIN_REQUIRED_CODE
                  : GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE);
    }

    when(sessionAuthenticationService.resolveSessionContext("1"))
        .thenReturn(Optional.of(previewRealmContext()));
    Mockito.doReturn(publicEntitlement(true, authorityNow.toString()))
        .when(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
    for (int index = 0; index < evaluatedAtValues.size(); index++) {
      String evaluatedAt = evaluatedAtValues.get(index);
      Mockito.doAnswer(
              invocation ->
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                      .echoRequestId(
                          activeMembershipAt(authorityNow.toString()), invocation.getArgument(0)))
          .when(accountClient)
          .getTenantMembershipForRuntime(
              Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
      Mockito.doReturn(validGrant().toBuilder().setEvaluatedAt(evaluatedAt).build())
          .when(accountClient)
          .getRealmAccessGrantForRuntime(
              Mockito.anyString(),
              Mockito.anyString(),
              Mockito.anyString(),
              Mockito.anyString(),
              Mockito.anyString());

      PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

      if (index < 2) {
        assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
      } else {
        assertThat(result.commandResult().errorCode())
            .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ENTITLEMENT_UNAVAILABLE", "AUTH_UNAVAILABLE"})
  void playEntitlementFailureMasksMissingPublicMembership(String errorCode) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing(123L, 22L)),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode(errorCode).build())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(
            "ENTITLEMENT_UNAVAILABLE".equals(errorCode)
                ? GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE
                : GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
    Mockito.verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @Test
  void playBillingDenialMasksMissingPublicMembership() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(false)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_CODE);
    Mockito.verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @Test
  void playPublicPolicyDenialMasksMissingMembershipWithoutBindingOrEntityCalls() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubMembershipResponses(
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
            123L, 22L));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PUBLIC_PRODUCTION_ADMISSION_DENIED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.public-production-admission-denied");
    org.mockito.InOrder order = Mockito.inOrder(accountClient);
    order
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
    order
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @Test
  void playExistingPublicMemberCanEnterWhenNewPublicJoinsAreDisabled() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(sessionContextService).save(Mockito.any(SessionContext.class));
  }

  @Test
  void playReturnsJoinRequiredAfterFreshPublicPolicyAllowsJoinWithoutBindingOrEntityCalls() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .missing(123L, 22L)),
                        invocation.getArgument(0)));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
    Mockito.verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(
        entityManagementClient, sessionContextService, gameplayPresenceLifecycleService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"malformed", "future", "stale"})
  void playWhenMembershipAuthorityTimestampIsUnsafeFailsClosed(String defect) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                          net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                              .active(123L, 22L, "1"))
                      .toBuilder()
                      .setEvaluatedAt(
                          switch (defect) {
                            case "future" -> Instant.now().plusSeconds(1L).toString();
                            case "stale" -> Instant.now().minus(16L, ChronoUnit.SECONDS).toString();
                            default -> "not-a-timestamp";
                          })
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(response, invocation.getArgument(0));
            });

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
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void playWhenMembershipRequestIdEchoDoesNotMatchFailsClosed() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                      .echoRequestId(
                          freshMembership(
                              net.firedevops.firemud.gamesession.support
                                  .RuntimeMembershipTestFixtures.active(123L, 22L, "1")),
                          invocation.getArgument(0));
              return response.toBuilder().setRequestId("wrong-request-id").build();
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "stale",
        "future",
        "wrong-account",
        "wrong-tenant",
        "unknown-lifecycle",
        "inactive-admitting",
        "missing-version",
        "missing-generation"
      })
  void playRejectsInvalidPositiveMembershipAuthority(String invalidEvidence) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    GetTenantMembershipForRuntimeResponse.Builder response =
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.active(
            123L, 22L, "1")
            .toBuilder();
    switch (invalidEvidence) {
      case "stale" -> response.setEvaluatedAt(Instant.now().minusSeconds(16).toString());
      case "future" -> response.setEvaluatedAt(Instant.now().plusSeconds(1).toString());
      case "wrong-account" -> response.setAccountId("00000000-0000-0000-0000-000000000999");
      case "wrong-tenant" -> response.setTenantId("00000000-0000-0000-0000-000000000023");
      case "unknown-lifecycle" -> response.setMembershipLifecycleState("UNKNOWN");
      case "inactive-admitting" -> {
        response.setMembershipLifecycleState("INACTIVE");
        response.setMembershipBaseline(
            response.getMembershipBaseline().toBuilder().setMembershipLifecycleState("INACTIVE"));
      }
      case "missing-version" -> {
        String tenantUuid = "00000000-0000-0000-0000-000000000022";
        response.putMembershipVersion(tenantUuid, "0");
        response.setMembershipBaseline(
            response.getMembershipBaseline().toBuilder().putMembershipVersion(tenantUuid, "0"));
      }
      case "missing-generation" -> {
        response.setMembershipAuthorityGeneration("");
        response.setMembershipBaseline(
            response.getMembershipBaseline().toBuilder().setMembershipAuthorityGeneration(""));
      }
      default -> throw new IllegalArgumentException(invalidEvidence);
    }
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(response.build(), invocation.getArgument(0)));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"stale", "future", "wrong-tenant", "missing-version", "missing-sequence"})
  void playRejectsInvalidEntitlementAuthority(String invalidEvidence) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    GetTenantEntitlementsForRuntimeResponse.Builder response =
        GetTenantEntitlementsForRuntimeResponse.newBuilder()
            .setTenantId("22")
            .setGameplayAvailable(true)
            .setEntitlementVersion(1L)
            .setTenantBillingSequence(1L)
            .setEvaluatedAt(evaluatedAtNow());
    switch (invalidEvidence) {
      case "stale" -> response.setEvaluatedAt(Instant.now().minusSeconds(16).toString());
      case "future" -> response.setEvaluatedAt(Instant.now().plusSeconds(1).toString());
      case "wrong-tenant" -> response.setTenantId("23");
      case "missing-version" -> response.setEntitlementVersion(0L);
      case "missing-sequence" -> response.setTenantBillingSequence(0L);
      default -> throw new IllegalArgumentException(invalidEvidence);
    }
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(response.build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"stale", "future", "wrong-account", "wrong-realm"})
  void playRejectsInvalidPositiveGrantAuthority(String invalidEvidence) {
    markPreviewRealmInvisible();
    SessionContext context = previewRealmContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    GetRealmAccessGrantForRuntimeResponse.Builder grant =
        GetRealmAccessGrantForRuntimeResponse.newBuilder()
            .setAccountId("123")
            .setTenantId("22")
            .setWorldSlug("sandbox")
            .setRealmSlug("preview")
            .setGranted(true)
            .setGrantVersion(1L)
            .setEvaluatedAt(evaluatedAtNow());
    switch (invalidEvidence) {
      case "stale" -> grant.setEvaluatedAt(Instant.now().minusSeconds(16).toString());
      case "future" -> grant.setEvaluatedAt(Instant.now().plusSeconds(1).toString());
      case "wrong-account" -> grant.setAccountId("999");
      case "wrong-realm" -> grant.setRealmSlug("production");
      default -> throw new IllegalArgumentException(invalidEvidence);
    }
    when(accountClient.getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(grant.build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playWhenMembershipAuthorityGenerationIsMissingFailsClosed() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                      .echoRequestId(
                          freshMembership(
                              net.firedevops.firemud.gamesession.support
                                  .RuntimeMembershipTestFixtures.active(123L, 22L, "1")),
                          invocation.getArgument(0));
              return response.toBuilder().clearMembershipAuthorityGeneration().build();
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playWhenMembershipAuthorityTupleHasUnknownFieldsIsDeniedBeforeAdmission() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active(123L, 22L, "1"));
              var tupleWithUnknownField =
                  response.getAuthorityTuple().toBuilder()
                      .setUnknownFields(
                          UnknownFieldSet.newBuilder()
                              .addField(
                                  999, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                              .build())
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      response.toBuilder().setAuthorityTuple(tupleWithUnknownField).build(),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.authority-unavailable");
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
  }

  @Test
  void playWhenMembershipCanonicalEventChangesWithValidDigestFailsClosed() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active(123L, 22L, "1"));
              String changedCanonicalJson =
                  resealWithMembershipVersion(
                      response.getOutboxSourceEvidence(0).getCanonicalEventJson(), "2");
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      replaceCanonicalEvent(response, changedCanonicalJson, true),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void playWhenMembershipCanonicalEventDigestDoesNotMatchFailsClosed() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active(123L, 22L, "1"));
              String changedCanonicalJson =
                  changeCanonicalEventWithoutResealing(
                      response.getOutboxSourceEvidence(0).getCanonicalEventJson());
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      replaceCanonicalEvent(response, changedCanonicalJson, false),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void playWhenMembershipCanonicalEventContentIsAbsentFailsClosed() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active(123L, 22L, "1"));
              var source =
                  response.getOutboxSourceEvidence(0).toBuilder().clearCanonicalEventJson();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(
                      response.toBuilder()
                          .clearOutboxSourceEvidence()
                          .addOutboxSourceEvidence(source)
                          .build(),
                      invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void playWhenMembershipResponseDiffersFromCanonicalEventFailsClosed() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var response =
                  freshMembership(
                      net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .active(123L, 22L, "1"));
              var mismatchedResponse =
                  response.toBuilder()
                      .putMembershipVersion("00000000-0000-0000-0000-000000000022", "2")
                      .setMembershipBaseline(
                          response.getMembershipBaseline().toBuilder()
                              .putMembershipVersion("00000000-0000-0000-0000-000000000022", "2"))
                      .build();
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(mismatchedResponse, invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"target", "version", "stale", "future", "malformed"})
  void unsafeEntitlementSnapshotFailsClosed(String defect) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    String tenantId = "target".equals(defect) ? "23" : "22";
    long entitlementVersion = "version".equals(defect) ? 0L : 1L;
    String evaluatedAt =
        switch (defect) {
          case "stale" -> Instant.now().minus(16L, ChronoUnit.SECONDS).toString();
          case "future" -> Instant.now().plus(1L, ChronoUnit.SECONDS).toString();
          case "malformed" -> "not-a-timestamp";
          default -> Instant.now().toString();
        };
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId(tenantId)
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(entitlementVersion)
                .setTenantBillingSequence(0L)
                .setEvaluatedAt(evaluatedAt)
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE);
    Mockito.verify(accountClient, never())
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    Mockito.verifyNoInteractions(entityManagementClient, sessionContextService);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
  }

  @Test
  void playBlockedByEntitlementsReturnsBillingBlocked() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(false)
                .setEntitlementVersion(5L)
                .setTenantBillingSequence(5L)
                .setEvaluatedAt(evaluatedAtNow())
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
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
  @CsvSource({
    "ACTIVE,ABSENT",
    "ACTIVE,UNKNOWN",
    "ACTIVE,UNAVAILABLE",
    "MISSING,ABSENT",
    "MISSING,UNKNOWN",
    "MISSING,UNAVAILABLE",
    "INACTIVE,ABSENT",
    "INACTIVE,UNKNOWN",
    "INACTIVE,UNAVAILABLE"
  })
  void playRequiresExplicitAvailableMembershipAuthorityBeforePublicOutcomes(
      String lifecycle, String availability) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var membership =
                  switch (lifecycle) {
                    case "ACTIVE" ->
                        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                            .active(123L, 22L, "1");
                    case "MISSING" ->
                        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                            .missing(123L, 22L);
                    case "INACTIVE" ->
                        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                            .inactive(123L, 22L, "3");
                    default ->
                        throw new IllegalArgumentException("Unexpected lifecycle: " + lifecycle);
                  };
              var builder = freshMembership(membership).toBuilder();
              if (!"ABSENT".equals(availability)) {
                builder.setAuthorityAvailability(availability);
              } else {
                builder.clearAuthorityAvailability();
              }
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(builder.build(), invocation.getArgument(0));
            });

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @ParameterizedTest
  @CsvSource({
    "MISSING,ABSENT",
    "MISSING,UNKNOWN",
    "MISSING,UNAVAILABLE",
    "INACTIVE,ABSENT",
    "INACTIVE,UNKNOWN",
    "INACTIVE,UNAVAILABLE"
  })
  void playRequiresExplicitAvailableMembershipAuthorityBeforeNonPublicOutcome(
      String lifecycle, String availability) {
    SessionContext context = unboundContext();
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var membership =
                  "MISSING".equals(lifecycle)
                      ? net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .missing(123L, 22L)
                      : net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                          .inactive(123L, 22L, "3");
              var builder = freshMembership(membership).toBuilder();
              if (!"ABSENT".equals(availability)) {
                builder.setAuthorityAvailability(availability);
              } else {
                builder.clearAuthorityAvailability();
              }
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(builder.build(), invocation.getArgument(0));
            });

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE);
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(
        entityManagementClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void playWhenEntitlementAuthorityUnavailablePreservesExistingBinding() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE)
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
  void roomlessRetainedActorFallsBackToFreshEntryAndRequestsRedraw() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "demo",
            1L,
            null,
            "jwt-token",
            null,
            1L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isTrue();
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo as demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                123L,
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
        .isEqualTo(1.0);
    Mockito.verify(sessionAuthenticationService).resolveByGameplayIdentity(22L, 1L, 7001L);
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                123L,
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
                123L,
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

  @Test
  void roomlessRetainedActorMissingFromRosterCannotBeSilentlyReplaced() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            1L,
            null,
            "jwt-token",
            null,
            1L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void roomlessRetainedActorMayChangeThroughExplicitCharacterSelection() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            1L,
            null,
            "jwt-token",
            null,
            1L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7002L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("demo", "production", "Sora"),
                "PLAY demo production Sora"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.reconnectRedrawRecommended()).isFalse();
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                123L,
                "demo@example.com",
                7002L,
                "Sora",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                null,
                1L,
                "demo",
                "production",
                1L,
                "SHARED"));
  }

  private static String joinedOutputText(List<PlayerOutput> outputs) {
    return outputs.stream()
        .map(PlayerOutput::text)
        .filter(text -> text != null && !text.isBlank())
        .reduce((left, right) -> left + "\n" + right)
        .orElse(null);
  }

  private static GetTenantMembershipForRuntimeResponse replaceCanonicalEvent(
      GetTenantMembershipForRuntimeResponse response,
      String canonicalJson,
      boolean synchronizeSourceMetadata) {
    var source =
        response.getOutboxSourceEvidence(0).toBuilder().setCanonicalEventJson(canonicalJson);
    if (synchronizeSourceMetadata) {
      var event = MembershipAuthorityEventV1Codec.verify(canonicalJson);
      source
          .setOutboxStreamKey(event.outboxStreamKey())
          .setOutboxSequence(event.outboxSequence())
          .setEventId(event.eventId())
          .setEventDigest(event.eventDigest());
    }
    return response.toBuilder().clearOutboxSourceEvidence().addOutboxSourceEvidence(source).build();
  }

  private static String resealWithMembershipVersion(
      String canonicalJson, String membershipVersion) {
    try {
      ObjectNode event = (ObjectNode) JSON.readTree(canonicalJson);
      ((ObjectNode) event.get("membershipVersion"))
          .put("00000000-0000-0000-0000-000000000022", membershipVersion);
      event.remove("eventDigest");
      return MembershipAuthorityEventV1Codec.seal(
              JSON.convertValue(event, new TypeReference<Map<String, Object>>() {}))
          .canonicalJson();
    } catch (IOException ex) {
      throw new AssertionError("test event should be valid JSON", ex);
    }
  }

  private static String changeCanonicalEventWithoutResealing(String canonicalJson) {
    try {
      ObjectNode event = (ObjectNode) JSON.readTree(canonicalJson);
      event.put("requestId", "tampered-membership-request");
      return event.toString();
    } catch (IOException ex) {
      throw new AssertionError("test event should be valid JSON", ex);
    }
  }

  private static GetTenantMembershipForRuntimeResponse freshMembership(
      GetTenantMembershipForRuntimeResponse response) {
    return response.toBuilder().setEvaluatedAt(Instant.now().toString()).build();
  }

  private static GetTenantMembershipForRuntimeResponse leftMembershipWithDefect(String defect) {
    var response =
        net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.left(
            123L, 22L, List.of("designer", "player"));
    var builder = response.toBuilder();
    switch (defect) {
      case "membership-generation" -> {
        String tenantUuid = response.getTenantId();
        builder
            .setMembershipAuthorityGeneration("3")
            .setMembershipBaseline(
                response.getMembershipBaseline().toBuilder().setMembershipAuthorityGeneration("3"))
            .setAuthorityTuple(
                response.getAuthorityTuple().toBuilder()
                    .putMembershipAuthorityGeneration(tenantUuid, "3"));
      }
      case "issuance-fence" -> builder.setIssuanceFence("3");
      case "zero-sequence" -> {
        String membershipStream = response.getOutboxSourceEvidence(0).getOutboxStreamKey();
        boolean updated = false;
        for (int index = 0; index < response.getOutboxCheckpointsCount(); index++) {
          var checkpoint = response.getOutboxCheckpoints(index);
          if (checkpoint.getOutboxStreamKey().equals(membershipStream)) {
            builder.setOutboxCheckpoints(
                index, checkpoint.toBuilder().setOutboxSequence("0").build());
            updated = true;
            break;
          }
        }
        if (!updated) {
          throw new IllegalStateException("LEFT fixture is missing its membership checkpoint");
        }
      }
      case "removed-event" -> builder.clearOutboxSourceEvidence();
      case "altered-roles" -> builder.clearRoles().addRoles("player");
      case "admission-allowed" -> builder.setGameplayAdmissionAllowed(true);
      default -> throw new IllegalArgumentException("Unknown LEFT evidence defect: " + defect);
    }
    return builder.build();
  }

  private void assertIdentityUnavailableWithoutMutation(PlayCommandHandlingResult result) {
    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE);
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.anyString(), Mockito.anyString());
  }

  private PlayCommandHandler handlerWithClock(Clock clock) {
    return handlerWithClock(clock, connectScopeSessionStore);
  }

  private PlayCommandHandler handlerWithClock(
      Clock clock, DirectTextConnectScopeSessionStore scopeStore) {
    return new PlayCommandHandler(
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
        meterRegistry,
        scopeStore,
        clock);
  }

  private static GetTenantMembershipForRuntimeResponse missingMembershipAt(String evaluatedAt) {
    return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
            123L, 22L)
        .toBuilder()
        .setEvaluatedAt(evaluatedAt)
        .build();
  }

  private static GetTenantMembershipForRuntimeResponse activeMembershipAt(String evaluatedAt) {
    return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.active(
            123L, 22L, "1")
        .toBuilder()
        .setEvaluatedAt(evaluatedAt)
        .build();
  }

  private static String evaluatedAtNow() {
    return Instant.now().toString();
  }

  private void stubMembershipResponses(GetTenantMembershipForRuntimeResponse... responses) {
    if (responses.length == 0) {
      throw new IllegalArgumentException("At least one membership response is required");
    }
    AtomicInteger nextResponse = new AtomicInteger();
    Mockito.doAnswer(
            invocation -> {
              int index = Math.min(nextResponse.getAndIncrement(), responses.length - 1);
              return net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                  .echoRequestId(freshMembership(responses[index]), invocation.getArgument(0));
            })
        .when(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
  }

  private static GetRealmAccessGrantForRuntimeResponse validGrant() {
    return validGrant("123", "22", "sandbox", "preview");
  }

  private static GetRealmAccessGrantForRuntimeResponse validGrant(
      String accountId, String tenantId, String worldSlug, String realmSlug) {
    return GetRealmAccessGrantForRuntimeResponse.newBuilder()
        .setAccountId(accountId)
        .setTenantId(tenantId)
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .setGranted(true)
        .setGrantVersion(1L)
        .setEvaluatedAt(evaluatedAtNow())
        .build();
  }

  private static GetTenantEntitlementsForRuntimeResponse publicEntitlement(
      boolean allowPublicJoin) {
    return GetTenantEntitlementsForRuntimeResponse.newBuilder()
        .setTenantId("22")
        .setGameplayAvailable(true)
        .setAllowPublicJoin(allowPublicJoin)
        .setEntitlementVersion(1L)
        .setTenantBillingSequence(1L)
        .setEvaluatedAt(evaluatedAtNow())
        .build();
  }

  private static GetTenantEntitlementsForRuntimeResponse publicEntitlement(
      boolean allowPublicJoin, String evaluatedAt) {
    return publicEntitlement(allowPublicJoin).toBuilder().setEvaluatedAt(evaluatedAt).build();
  }

  private void markPreviewRealmInvisible() {
    gameplayCatalogProperties.getWorlds().get(1).getRealms().get(1).setVisible(false);
  }

  private void retainRealmSnapshot(
      SessionContext context,
      String worldSelector,
      GameplayWorldCatalog.WorldView world,
      List<GameplayWorldCatalog.RealmView> responseRealms) {
    long tenantId = world.realms().getFirst().tenantId();
    GameplayWorldCatalog.RealmDiscoverySnapshot snapshot =
        worldCatalog.realmDiscoverySnapshot(world, responseRealms);
    connectScopeSessionStore.replaceRealmSnapshot(
        context,
        worldSelector,
        tenantId,
        world.slug(),
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        List.of(),
        Instant.now());
  }

  private void addNonPublicRealmToPublicDemoWorld(boolean visible) {
    GameplayCatalogProperties.Realm hiddenRealm =
        realm("invite-only", "Invite-only Realm", 22L, 51L, visible, true);
    hiddenRealm.setPublicProductionRealm(false);
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().add(hiddenRealm);
  }

  private void assertHiddenRealmDenialMatchesUnknownSelection(
      PlayCommandHandlingResult denied, PlayCommandHandlingResult unknown, String hiddenRealmSlug) {
    assertThat(denied.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(unknown.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    ErrorOutput error = (ErrorOutput) denied.outputs().getFirst().payload();
    assertThat(error).isEqualTo(unknown.outputs().getFirst().payload());
    assertThat(error.message()).doesNotContain(hiddenRealmSlug, "51");
  }

  private SessionContext loggedInUnboundContext() {
    return new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
  }

  private SessionContext previewRealmContext() {
    return new SessionContext(
        1L,
        22L,
        123L,
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

  private SessionContext unboundContext() {
    return new SessionContext(
        1L,
        22L,
        123L,
        "demo@example.com",
        0L,
        null,
        0L,
        null,
        "jwt-token",
        null,
        0L,
        null,
        null,
        0L,
        null);
  }

  private TextCommand previewRealmPlayCommand() {
    return new TextCommand(
        TextCommandType.PLAY,
        List.of("sandbox", "preview", "Emberline"),
        "PLAY sandbox preview Emberline");
  }

  private void assertPlayJoinRequiredPreservesAuthenticationAndSupportsExplicitJoin(
      String lifecycleState, long membershipVersion) {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            1L,
            "R-1",
            "jwt-token",
            "en-NZ",
            1L,
            "demo",
            "production",
            1L,
            "SHARED",
            "login-account-scope",
            "login-account-request");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    var membershipResponse =
        "MISSING".equals(lifecycleState)
            ? net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.missing(
                123L, 22L)
            : net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures.inactive(
                123L, 22L, Long.toString(membershipVersion));
    Mockito.doAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(freshMembership(membershipResponse), invocation.getArgument(0)))
        .when(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(result.commandResult().errorMessage())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_MESSAGE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    TextPlayerOutputRenderer renderer = new TextPlayerOutputRenderer(new PresentationProperties());
    assertThat(renderer.render(result.outputs().get(0), "fr"))
        .isEqualTo(
            "ERROR JOIN_REQUIRED Une adhésion est requise avant PLAY. Consultez d’abord REALMS <monde>, puis utilisez JOIN <monde>.");
    assertThat(renderer.render(result.outputs().get(0), "de"))
        .isEqualTo(
            "ERROR JOIN_REQUIRED Membership is required before PLAY. Run REALMS <world> first, then JOIN <world>.");
    assertThat(
            meterRegistry
                .counter("gamesession.session.resume_denied", "reason", "join_required")
                .count())
        .isEqualTo(1.0);
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");

    ArgumentCaptor<SessionContext> savedContextCaptor =
        ArgumentCaptor.forClass(SessionContext.class);
    Mockito.verify(sessionContextService).save(savedContextCaptor.capture());
    SessionContext savedContext = savedContextCaptor.getValue();
    assertThat(savedContext.accountId()).isEqualTo(context.accountId());
    assertThat(savedContext.loginName()).isEqualTo(context.loginName());
    assertThat(savedContext.jwt()).isEqualTo(context.jwt());
    assertThat(savedContext.connectScopeId()).isEqualTo(context.connectScopeId());
    assertThat(savedContext.connectRequestId()).isEqualTo(context.connectRequestId());
    assertThat(savedContext.localeTag()).isEqualTo(context.localeTag());
    assertThat(savedContext.bootstrapGameInstanceId()).isEqualTo(context.bootstrapGameInstanceId());
    assertThat(savedContext.characterId()).isZero();
    assertThat(savedContext.characterName()).isNull();
    assertThat(savedContext.gameInstanceId()).isZero();
    assertThat(savedContext.roomInstanceId()).isNull();
    assertThat(savedContext.playableStateScope()).isNull();
    assertThat(savedContext.worldSlug()).isEqualTo("demo");
    assertThat(savedContext.realmSlug()).isEqualTo("production");
    assertThat(savedContext.pointerVersion()).isEqualTo(1L);

    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(entityManagementClient, never())
        .findCharacterByName(
            Mockito.any(), Mockito.any(PlayableStateScope.class), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishCommandEvent(Mockito.any(), Mockito.any(GameplayCommand.class));
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, never())
        .joinPublicProductionMembership(
            Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(Instant.class));

    when(sessionContextService.findBySessionId(1L)).thenReturn(Optional.of(savedContext));
    when(sessionContextService.findByTenantAndSessionId(22L, 1L))
        .thenReturn(Optional.of(savedContext));
    SessionRoutingNormalizationService realRoutingNormalizationService =
        new SessionRoutingNormalizationService(
            sessionContextService, Mockito.mock(GameplayAdmissionPointerAuthorityService.class));
    SessionAuthenticationService realSessionAuthenticationService =
        new SessionAuthenticationService(
            sessionContextService,
            new GameSessionProperties(),
            realRoutingNormalizationService,
            gameplayPresenceLifecycleService);
    SessionContext resolvedContext =
        realSessionAuthenticationService.resolveSessionContext("1").orElseThrow();
    assertThat(resolvedContext).isEqualTo(savedContext);
    assertThat(resolvedContext.accountId()).isEqualTo(123L);
    Mockito.verify(sessionContextService, Mockito.times(1)).save(Mockito.any());

    String exactJoinScopeId = "account-issued-join-scope";
    when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId(exactJoinScopeId)
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    when(accountClient.joinPublicProductionMembership(
            Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(Instant.class)))
        .thenReturn(JoinPublicProductionMembershipResponse.newBuilder().setSuccess(true).build());
    WorldsCommandHandler worldsCommandHandler =
        new WorldsCommandHandler(worldCatalog, accountClient, connectScopeSessionStore);
    assertThat(worldsCommandHandler.browseRealms(resolvedContext, "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    WorldsTextCommandDispatchHandler worldsDispatchHandler =
        new WorldsTextCommandDispatchHandler(worldsCommandHandler, scriptEventPublisher);
    TextCommandInterpretationResult joinResult =
        worldsDispatchHandler.handle(
            new TextCommandDispatchRequest(
                "1",
                new TextCommand(TextCommandType.JOIN, List.of("demo"), "JOIN demo"),
                false,
                Optional.of(resolvedContext)));
    assertThat(joinResult.commandResult()).isEqualTo(CommandEnqueueResult.success());

    ArgumentCaptor<net.firedevops.firemud.shared.v1.PlayerExecutionContext> playerContextCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class);
    ArgumentCaptor<String> scopeIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> requestIdCaptor = ArgumentCaptor.forClass(String.class);
    Mockito.verify(accountClient)
        .joinPublicProductionMembership(
            playerContextCaptor.capture(),
            scopeIdCaptor.capture(),
            requestIdCaptor.capture(),
            Mockito.any(Instant.class));
    assertThat(scopeIdCaptor.getValue()).isEqualTo(exactJoinScopeId);
    assertThat(playerContextCaptor.getValue().getAccountId()).isEqualTo("123");
    assertThat(playerContextCaptor.getValue().getSessionId()).isEqualTo("1");
    assertThat(playerContextCaptor.getValue().getTenantId()).isEqualTo("22");
    assertThat(playerContextCaptor.getValue().getRequestId()).isEqualTo(requestIdCaptor.getValue());
  }

  private static GameplayCatalogProperties.World world(
      String slug, String displayName, List<GameplayCatalogProperties.Realm> realms) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(displayName);
    world.setRealms(new ArrayList<>(realms));
    return world;
  }

  private static GameplayAdmissionPointerSnapshot admissionPointer(long pointerVersion) {
    return new GameplayAdmissionPointerSnapshot(
        "demo",
        "Demo World",
        "production",
        "Live Realm",
        22L,
        1L,
        pointerVersion,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        1L,
        ADMISSION_REALM_ID,
        ADMISSION_NAMESPACE_ID);
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
    Mockito.verify(scriptEventPublisher, never()).publishCommandEvent(Mockito.any(), Mockito.any());
    Mockito.verify(scriptEventPublisher, never())
        .publishSpawnEvent(Mockito.any(), Mockito.any(), Mockito.any());
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  private void assertDemoCharacterSelectionRequired(PlayCommandHandlingResult result) {
    String expectedMessage =
        "Selection required. Use PLAY demo <character> with a known character; character browsing is currently unavailable.";
    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(result.commandResult().errorMessage()).isEqualTo(expectedMessage);

    ErrorOutput error = (ErrorOutput) result.outputs().getFirst().payload();
    assertThat(error.code()).isEqualTo(GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE);
    assertThat(error.message()).isEqualTo(expectedMessage);
    assertThat(error.messageKey()).isEqualTo("error.play.character-selection-required");
    assertThat(error.arguments())
        .containsOnlyKeys("playUsage")
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
  void deniedSameSlugSelectionInAnotherTenantPreservesExistingBinding() {
    GameplayCatalogProperties.Realm selectedRealm =
        gameplayCatalogProperties.getWorlds().get(1).getRealms().get(0);
    selectedRealm.setTenantId(23L);
    selectedRealm.setGameInstanceId(7001L);
    selectedRealm.setPublicProductionRealm(true);
    gameplayCatalogProperties.getWorlds().get(1).getRealms().get(1).setTenantId(23L);
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            2L,
            "R-1",
            "jwt-token",
            null,
            2L,
            "sandbox",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Mockito.doAnswer(
            invocation ->
                net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                    .echoRequestId(
                        freshMembership(
                            net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures
                                .inactive(123L, 23L, "2")),
                        invocation.getArgument(0)))
        .when(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.eq("23"), Mockito.anyString()))
        .thenReturn(
            net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("23")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "production", "Emberline"),
                "PLAY sandbox production Emberline"));

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
  }

  @Test
  void playReadsAccountMembershipGrantAndEntitlementBeforeActorRoster() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Mockito.doReturn(
            roster(actor("7001", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)))
        .when(entityManagementClient)
        .listCharactersByAccount("22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "preview", "Emberline"),
                "PLAY sandbox preview Emberline"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    org.mockito.InOrder order = Mockito.inOrder(accountClient, entityManagementClient);
    order
        .verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
    order
        .verify(accountClient)
        .getTenantMembershipForRuntime(
            Mockito.any(net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
    order
        .verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
            Mockito.eq("22"),
            Mockito.eq("sandbox"),
            Mockito.eq("preview"),
            Mockito.anyString());
    order
        .verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void playRejectsAmbiguousOwnedRosterWhenActorIsNotSelected() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7001", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
  }

  @Test
  void playRejectsAnotherAccountsActorEvenWhenNameMatches() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Character foreign =
        actorForTenant("22", "7001", "Emberline", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .toBuilder()
            .setAccountId("999")
            .build();
    Mockito.doReturn(roster(foreign))
        .when(entityManagementClient)
        .listCharactersByAccount("22", "123", "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    PlayCommandHandlingResult result =
        handler.handle(
            "1",
            new TextCommand(
                TextCommandType.PLAY,
                List.of("sandbox", "production", "Emberline"),
                "PLAY sandbox production Emberline"));

    assertIdentityUnavailableWithoutMutation(result);
  }

  @Test
  void playRejectsMissingOwnedActorWithoutSynthesizingIdentity() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    stubOwnedRoster("1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertDemoCharacterSelectionRequired(result);
    verifyNoGameplayBindingSideEffects();
    Mockito.verify(entityManagementClient, never())
        .findCharacterByName(
            Mockito.any(), Mockito.any(PlayableStateScope.class), Mockito.anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "scope"})
  void playRejectsRosterRowsOutsideSelectedTenantOrScope(String mismatch) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    Character valid = actor("7001", "demo", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Character mismatched =
        "tenant".equals(mismatch)
            ? valid.toBuilder().setTenantId("23").build()
            : valid.toBuilder()
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                .build();
    stubOwnedRoster("1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, mismatched);

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
  }

  @Test
  void playRejectsStaleRetainedActorWhenSelectedRosterNoLongerContainsIt() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
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
    stubOwnedRoster(
        "1",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        actor("7002", "Sora", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertIdentityUnavailableWithoutMutation(result);
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void playEntitlementCallerTargetPreconditionPreservesExistingBinding() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            123L,
            "Emberline",
            1L,
            "R-1",
            "jwt-token",
            "en",
            0L,
            "demo",
            "production",
            1L,
            "SHARED");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("FAILED_PRECONDITION")
                        .setMessage(
                            "This request cannot establish an authorized caller and target binding")
                        .build())
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
    assertThat(context.hasGameplayBinding()).isTrue();
    assertThat(context.tenantId()).isEqualTo(22L);
    assertThat(context.accountId()).isEqualTo(123L);
    assertThat(context.characterId()).isEqualTo(123L);
    assertThat(context.gameInstanceId()).isEqualTo(1L);
    assertThat(context.worldSlug()).isEqualTo("demo");
    assertThat(context.realmSlug()).isEqualTo("production");
    assertThat(context.pointerVersion()).isEqualTo(1L);
    assertThat(context.playableStateScope()).isEqualTo("SHARED");
    assertThat(context.connectScopeId()).isNull();
    assertThat(context.connectRequestId()).isNull();
    assertThat(meterRegistry.find("gamesession.session.resume_denied").counters()).isEmpty();
    Mockito.verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(sessionAuthenticationService, never())
        .resolveByGameplayIdentity(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.verify(entityManagementClient, never())
        .listCharactersByAccount(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(PlayableStateScope.class));
    Mockito.verify(moderationPolicyClient, never())
        .evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyLong());
    Mockito.verify(accountClient, never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verifyNoInteractions(scriptEventPublisher);
  }

  private void stubOwnedRoster(
      String gameInstanceId, PlayableStateScope scope, Character... characters) {
    Mockito.doReturn(roster(characters))
        .when(entityManagementClient)
        .listCharactersByAccount(
            tenantForGameInstance(gameInstanceId), "123", gameInstanceId, scope);
  }

  private static ListCharactersByAccountResponse roster(Character... characters) {
    return ListCharactersByAccountResponse.newBuilder()
        .addAllCharacters(List.of(characters))
        .build();
  }

  private static Character actor(String id, String name, PlayableStateScope scope) {
    return actorForTenant("22", id, name, scope);
  }

  private static Character actorForTenant(
      String tenantId, String id, String name, PlayableStateScope scope) {
    return Character.newBuilder()
        .setId(id)
        .setTenantId(tenantId)
        .setAccountId("123")
        .setName(name)
        .setPlayableStateScope(scope)
        .build();
  }

  private static String tenantForGameInstance(String gameInstanceId) {
    return "2".equals(gameInstanceId) || "41".equals(gameInstanceId) ? "23" : "22";
  }
}
