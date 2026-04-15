package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayCommandHandlerTest {
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final AccountClient accountClient = Mockito.mock(AccountClient.class);
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry =
      Mockito.mock(FirstPartyConnectContextRegistry.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final GameplayWorldCatalog worldCatalog =
      new GameplayWorldCatalog(gameplayCatalogProperties);
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
    handler =
        new PlayCommandHandler(
            sessionAuthenticationService,
            sessionContextService,
            worldCatalog,
            gameLogicProperties,
            accountClient,
            entityManagementClient,
            firstPartyConnectContextRegistry,
            gameplayPresenceLifecycleService,
            meterRegistry);
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
    when(accountClient.ensurePublicProductionPlayerMembership(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            EnsurePublicProductionPlayerMembershipResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setRealmSlug("production")
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(3L)
                .setCreated(true)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
  }

  @Test
  void playPromotesSessionIntoGameplay() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.findCharacterByName(
            "22", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "demo"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7001")
                    .setName("demo")
                    .build()));
    when(sessionContextService.findByGameplayIdentity(22L, 1L, 7001L)).thenReturn(Optional.empty());

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
                7001L,
                "demo",
                1L,
                gameLogicProperties.getDefaultRoomId(),
                "jwt-token",
                0L));
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
                0L));
  }

  @Test
  void playUsesResolvedEntityManagementCharacterIdWhenNameExists() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(entityManagementClient.findCharacterByName(
            "22", "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, "Emberline"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("9007")
                    .setName("Emberline")
                    .build()));
    when(sessionContextService.findByGameplayIdentity(22L, 2L, 9007L)).thenReturn(Optional.empty());

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
                0L));
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
                    0L,
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
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of(), "PLAY"));

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
            "ERROR PLAY_SELECTION_REQUIRED Selection requise. Utilisez PLAY sandbox <realm> [character] ou consultez REALMS dabord.");
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
            "22", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED, "Sora"))
        .thenReturn(
            Optional.of(
                net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                    .setId("7002")
                    .setName("Sora")
                    .build()));
    when(sessionContextService.findByGameplayIdentity(22L, 41L, 7002L))
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
  void playDeniedByMembershipReturnsWorldAccessDenied() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "room-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(2L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
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
  void playCreatesPublicProductionMembershipWhenMissing() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "room-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(0L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(sessionContextService.findByGameplayIdentity(22L, 1L, 123L)).thenReturn(Optional.empty());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    Mockito.verify(accountClient)
        .ensurePublicProductionPlayerMembership(
            Mockito.eq("123"), Mockito.eq("22"), Mockito.eq("production"), Mockito.anyString());
  }

  @Test
  void playBlockedByEntitlementsReturnsBillingBlocked() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "room-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(false)
                .setEntitlementVersion(5L)
                .setTenantBillingSequence(5L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
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
  }

  @Test
  void playWhenMembershipAuthorityUnavailableFailsClosed() {
    SessionContext context =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, "room-1", "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(accountClient.getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("MEMBERSHIP_AUTH_UNAVAILABLE")
                        .setMessage("Membership authority unavailable"))
                .build());

    PlayCommandHandlingResult result =
        handler.handle("1", new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("MEMBERSHIP_AUTH_UNAVAILABLE");
    assertThat(
            meterRegistry
                .counter("gamesession.session.resume_denied", "reason", "authority_unavailable")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void staleRoomContextFallsBackToFreshEntry() {
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 123L, "demo", 1L, null, "jwt-token");
    when(sessionAuthenticationService.resolveSessionContext("1")).thenReturn(Optional.of(context));
    when(sessionContextService.findByGameplayIdentity(22L, 1L, 123L)).thenReturn(Optional.empty());

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
                "jwt-token"));
    assertThat(
            meterRegistry
                .counter(
                    "gamesession.session.fresh_entry_fallback",
                    "reason",
                    "stale_or_missing_context")
                .count())
        .isEqualTo(1.0);
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
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    return realm;
  }
}
