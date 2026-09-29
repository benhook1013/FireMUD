package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class PlayCommandHandlerTest {
  private static final String PLAY_COMMAND_NAME = "PLAY";
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
    // The catalogue contract permits one public-production realm per tenant. Keep the sandbox
    // production realm non-public so these tests can exercise its explicit non-public path.
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
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
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipVersion(1L)
                .setMembershipAuthorityGeneration(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
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
  }

  @Test
  void playPromotesSessionIntoGameplay() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "demo"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("demo")
                    .build()));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "play demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo");
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
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.eq("123"), Mockito.eq("23"), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("23")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipVersion(1L)
                .setMembershipAuthorityGeneration(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
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
        .getTenantMembershipForRuntime(Mockito.eq("123"), Mockito.eq("23"), Mockito.anyString());
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

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
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
    assertThat(result.commandResult().errorCode()).isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
    Mockito.verifyNoInteractions(accountClient, entityManagementClient, moderationPolicyClient);
  }

  @Test
  void playRejectsPointerRevisionCutoverBeforeAdmissionReads() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot pointerA = admissionPointer(1L);
    GameplayAdmissionPointerSnapshot pointerB = admissionPointer(2L);
    AtomicInteger pointerReads = new AtomicInteger();
    when(authorityService.listPointers())
        .thenAnswer(
            invocation ->
                pointerReads.getAndIncrement() == 0 ? List.of(pointerA) : List.of(pointerB));
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
    assertThat(pointerReads).hasValue(2);
    Mockito.verifyNoInteractions(
        accountClient,
        entityManagementClient,
        moderationPolicyClient,
        sessionContextService,
        gameplayPresenceLifecycleService,
        scriptEventPublisher);
  }

  @Test
  void playMapsPointerAuthorityReadFailureBeforeAccountOrEntityAdmissionReads() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    when(authorityService.listPointers()).thenThrow(new IllegalStateException("authority down"));
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
  void playRejectsModerationPolicyDeniedAdmission() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "demo"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("demo")
                    .build()));
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
  void playUsesResolvedEntityManagementCharacterIdWhenNameExists() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Emberline"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("9007")
                    .setName("Emberline")
                    .build()));
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
  void playRejectsMalformedResolvedCharacterId() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Emberline"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("abc")
                    .setName("Emberline")
                    .build()));

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
  void playResumeDoesNotPublishSpawnEvent() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "demo",
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

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
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
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "demo"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("demo")
                    .build()));
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
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "demo"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("demo")
                    .build()));
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
            "Selection required. Use PLAY sandbox preview <character> or browse CHARS sandbox preview first.");
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
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED, "Sora"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7002")
                    .setName("Sora")
                    .build()));
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
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED, "Sora"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7002")
                    .setName("Sora")
                    .build()));
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
        .findCharacterByName(
            Mockito.any(), Mockito.any(PlayableStateScope.class), Mockito.anyString());
  }

  @Test
  void playDeniedByMembershipReturnsWorldAccessDenied() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("INACTIVE")
                .setMembershipVersion(2L)
                .setMembershipAuthorityGeneration(1L)
                .setEvaluatedAt(evaluatedAtNow())
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
  }

  @Test
  void playActiveNonAdmittingMembershipDoesNotSuggestJoinOrBind() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.findCharacterByName(
            context, PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "demo"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("demo")
                    .build()));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipVersion(2L)
                .setMembershipAuthorityGeneration(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.world-access-denied");
    Mockito.verify(accountClient, never())
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
    Mockito.verify(scriptEventPublisher, never()).publishCommandEvent(Mockito.any(), Mockito.any());
  }

  @Test
  void playNonPublicRealmRequiresExistingAdmissibleMembership() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("MISSING")
                .setMembershipVersion(0L)
                .setMembershipAuthorityGeneration(0L)
                .setEvaluatedAt(evaluatedAtNow())
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
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
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
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
    Mockito.verify(accountClient)
        .getRealmAccessGrantForRuntime(
            Mockito.eq("123"),
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
        .thenReturn(GetRealmAccessGrantForRuntimeResponse.newBuilder().setGranted(false).build());

    PlayCommandHandlingResult result = handler.handle("1", previewRealmPlayCommand());

    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
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
        .isEqualTo(GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE);
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

  @ParameterizedTest
  @ValueSource(strings = {"NOT_FOUND", "PERMISSION_DENIED"})
  void playNonAuthorityMembershipErrorReturnsAccessDeniedAndClearsBinding(String errorCode) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
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
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("MISSING")
                .setMembershipVersion(0L)
                .setMembershipAuthorityGeneration(0L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
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
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");
  }

  @Test
  void playRequiresExplicitJoinWhenPublicProductionMembershipIsInactive() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("INACTIVE")
                .setMembershipVersion(4L)
                .setMembershipAuthorityGeneration(1L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo(GameplayStageCommandConstants.JOIN_REQUIRED_CODE);
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.play.join-required");
    Mockito.verify(gameplayPresenceLifecycleService).clearGameplayBinding(context, "join_required");
  }

  @Test
  void playPublicAdmissionDeniedWhenFreshEntitlementsDisallowJoin() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 0L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("MISSING")
                .setMembershipVersion(0L)
                .setMembershipAuthorityGeneration(0L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("Public joining is not available for this world.");
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
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("MISSING")
                .setMembershipVersion(0L)
                .setMembershipAuthorityGeneration(0L)
                .setEvaluatedAt(evaluatedAtNow())
                .build());
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
    Mockito.verify(sessionContextService, never()).save(Mockito.any());
    Mockito.verify(gameplayPresenceLifecycleService, never()).registerConnected(Mockito.any());
  }

  @Test
  void playWhenMembershipAuthorityTimestampIsMalformedFailsClosed() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setMembershipLifecycleState("ACTIVE")
                .setMembershipAuthorityGeneration(1L)
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

  @ParameterizedTest
  @ValueSource(strings = {"stale", "future", "wrong-account", "missing-generation"})
  void playRejectsInvalidPositiveMembershipAuthority(String invalidEvidence) {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "R-1", "jwt-token");
    GetTenantMembershipForRuntimeResponse.Builder response =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId("123")
            .setTenantId("22")
            .setMembershipExists(true)
            .setGameplayAdmissionAllowed(true)
            .setMembershipLifecycleState("ACTIVE")
            .setMembershipVersion(1L)
            .setMembershipAuthorityGeneration(1L)
            .setEvaluatedAt(evaluatedAtNow());
    switch (invalidEvidence) {
      case "stale" -> response.setEvaluatedAt(Instant.now().minusSeconds(16).toString());
      case "future" -> response.setEvaluatedAt(Instant.now().plusSeconds(1).toString());
      case "wrong-account" -> response.setAccountId("999");
      case "missing-generation" -> response.setMembershipAuthorityGeneration(0L);
      default -> throw new IllegalArgumentException(invalidEvidence);
    }
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(response.build());

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
  void staleRoomContextFallsBackToFreshEntry() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionAuthenticationService.resolveByGameplayIdentity(22L, 1L, 123L))
        .thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(joinedOutputText(result.outputs())).isEqualTo("Entered world: demo");
    Mockito.verify(sessionContextService)
        .save(
            new SessionContext(
                1L,
                22L,
                123L,
                "demo@example.com",
                123L,
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
    Mockito.verify(scriptEventPublisher)
        .publishCommandEvent(
            new SessionContext(
                1L,
                22L,
                123L,
                "demo@example.com",
                123L,
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
            command("play-command:1:1:123:1", PLAY_COMMAND_NAME, "PLAY demo"));
    Mockito.verify(scriptEventPublisher)
        .publishSpawnEvent(
            new SessionContext(
                1L,
                22L,
                123L,
                "demo@example.com",
                123L,
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
            "play-spawn:1:1:123:1");
  }

  private static String joinedOutputText(List<PlayerOutput> outputs) {
    return outputs.stream()
        .map(PlayerOutput::text)
        .filter(text -> text != null && !text.isBlank())
        .reduce((left, right) -> left + "\n" + right)
        .orElse(null);
  }

  private static String evaluatedAtNow() {
    return Instant.now().toString();
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

  private void markPreviewRealmInvisible() {
    gameplayCatalogProperties.getWorlds().get(1).getRealms().get(1).setVisible(false);
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

  private TextCommand previewRealmPlayCommand() {
    return new TextCommand(
        TextCommandType.PLAY,
        List.of("sandbox", "preview", "Emberline"),
        "PLAY sandbox preview Emberline");
  }

  private static GameplayCatalogProperties.World world(
      String slug, String displayName, List<GameplayCatalogProperties.Realm> realms) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(displayName);
    world.setRealms(realms);
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

  private static GameplayCommand command(String commandId, String commandName, String commandText) {
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId(commandId);
    gameplayCommand.setCommandName(commandName);
    gameplayCommand.setCommandText(commandText);
    return gameplayCommand;
  }
}
