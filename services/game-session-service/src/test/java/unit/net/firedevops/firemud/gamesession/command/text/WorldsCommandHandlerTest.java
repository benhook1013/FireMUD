package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextConnectScopeTarget;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsCommandHandlerTest {
  private static final UUID ADMISSION_REALM_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ADMISSION_NAMESPACE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(
          TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
          Mockito.mock(AccountClient.class),
          DirectTextConnectScopeSessionStore.inMemoryForTest());

  @BeforeEach
  void setUp() {
    // The default demo realm is the tenant's sole public-production target.
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
  }

  @Test
  void browseViewReturnsStructuredWorldList() {
    GameplayCatalogProperties.World demoWorld = world("demo", 22L, 1L, false);
    demoWorld.setDisplayName("Demo World");
    GameplayCatalogProperties.World sandboxWorld = world("sandbox", 23L, 2L, true);
    sandboxWorld.setDisplayName("Builder Sandbox");
    gameplayCatalogProperties.setWorlds(List.of(demoWorld, sandboxWorld));

    WorldsViewOutput response = handler.browseView();

    assertThat(response.worlds()).hasSize(2);
    assertThat(response.worlds().get(0).slug()).isEqualTo("demo");
    assertThat(response.worlds().get(0).displayName()).isEqualTo("Demo World");
    assertThat(response.worlds().get(1).slug()).isEqualTo("sandbox");
    assertThat(response.worlds().get(1).displayName()).isEqualTo("Builder Sandbox");
  }

  @Test
  void browseRealmsReturnsStructuredRealmList() {
    RealmBrowseViewOutput response = handler.browseRealms("demo").orElseThrow();

    assertThat(response.worldSlug()).isEqualTo("demo");
    assertThat(response.realms()).hasSize(1);
    assertThat(response.realms().get(0).realmSlug()).isEqualTo("production");
    assertThat(response.realms().get(0).stateScope()).isEqualTo("SHARED");
    assertThat(response.realms().get(0).characterCreationPolicy()).isEqualTo("ALLOW_NEW");
  }

  @Test
  void publicRealmSelectionsIgnorePointerChangesToDeniedPrivateRealms() {
    GameplayCatalogProperties properties = publicWorldWithPrivateRealm();
    GameplayCatalogProperties.Realm privateRealm =
        properties.getWorlds().getFirst().getRealms().get(1);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubPublicConnectScope(accountClient, "scope-public");
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq("demo"),
                Mockito.eq("playtest"),
                Mockito.anyString()))
        .thenReturn(grant("demo", "playtest", false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    privateRealm.setPointerVersion(2L);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", null))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verify(accountClient, Mockito.times(1))
        .issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verify(accountClient, Mockito.never())
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void numericRealmSnapshotRejectsChangedVisibleTarget() {
    GameplayCatalogProperties properties = publicProductionProperties();
    GameplayCatalogProperties.Realm publicRealm =
        properties.getWorlds().getFirst().getRealms().getFirst();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubPublicConnectScope(accountClient, "scope-public");
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    publicRealm.setPointerVersion(2L);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH"));
  }

  @Test
  void privateRealmPointerChangeCannotEnterThePublicRealmSnapshot() {
    GameplayCatalogProperties properties = publicWorldWithPrivateRealm();
    GameplayCatalogProperties.Realm privateRealm =
        properties.getWorlds().getFirst().getRealms().get(1);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubPublicConnectScope(accountClient, "scope-public");
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success -> {
              assertThat(success.output().realms())
                  .extracting(RealmBrowseViewOutput.RealmEntry::ordinal)
                  .containsExactly(1);
              assertThat(success.output().realms())
                  .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
                  .containsExactly("production");
            });
    privateRealm.setPointerVersion(2L);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "playtest"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidRealm("demo"));
    Mockito.verify(accountClient).issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void joinKeepsTheRetainedPublicScopeWhenDeniedPrivatePointerChanges() {
    GameplayCatalogProperties properties = publicWorldWithPrivateRealm();
    GameplayCatalogProperties.Realm privateRealm =
        properties.getWorlds().getFirst().getRealms().get(1);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubPublicConnectScope(accountClient, "scope-public");
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq("demo"),
                Mockito.eq("playtest"),
                Mockito.anyString()))
        .thenReturn(grant("demo", "playtest", false));
    Mockito.when(
            accountClient.joinPublicProductionMembership(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(Instant.class)))
        .thenReturn(JoinPublicProductionMembershipResponse.newBuilder().setSuccess(true).build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    privateRealm.setPointerVersion(2L);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.JoinMembershipResult.Response.class);
    Mockito.verify(accountClient, Mockito.times(1))
        .issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, Mockito.times(1))
        .joinPublicProductionMembership(
            Mockito.any(),
            Mockito.eq("scope-public"),
            Mockito.anyString(),
            Mockito.any(Instant.class));
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void joinRejectsStaleRealmSnapshotBeforeCallingAccount() {
    GameplayCatalogProperties properties = publicProductionProperties();
    GameplayCatalogProperties.Realm publicRealm =
        properties.getWorlds().getFirst().getRealms().getFirst();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubPublicConnectScope(accountClient, "scope-public");
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    publicRealm.setPointerVersion(2L);
    Mockito.clearInvocations(accountClient);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void joinMapsRealmSnapshotRevalidationOutageToAuthUnavailableWithoutAccountCall() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "demo",
            "production",
            "production",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"));
    Mockito.when(authorityService.listPointers()).thenReturn(List.of(pointer));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubPublicConnectScope(accountClient, "scope-public");
    GameplayWorldCatalog catalog = Mockito.spy(new GameplayWorldCatalog(authorityService));
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog, accountClient, DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    Mockito.doThrow(
            new GameplayWorldCatalog.AuthorityPointerReadUnavailableException(
                "pointer authority unavailable"))
        .when(catalog)
        .revalidateRealmDiscoverySnapshot(Mockito.any(), Mockito.anyList());
    Mockito.clearInvocations(accountClient);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void numericRealmSelectorRejectsCatalogReorderInsteadOfSelectingNewTenant() {
    GameplayWorldCatalog.RealmView realmA =
        new GameplayWorldCatalog.RealmView(
            "production",
            "A",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.RealmView realmB =
        new GameplayWorldCatalog.RealmView(
            "production",
            "B",
            23L,
            2L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.WorldView worldA =
        new GameplayWorldCatalog.WorldView("a", "A", List.of(realmA));
    GameplayWorldCatalog.WorldView worldB =
        new GameplayWorldCatalog.WorldView("b", "B", List.of(realmB));
    AtomicReference<List<GameplayWorldCatalog.WorldView>> worlds =
        new AtomicReference<>(List.of(worldA, worldB));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldSupplier(worlds::get),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    localHandler.browseView("7", Optional.of(authenticatedSession()));
    worlds.set(List.of(worldB, worldA));

    assertThat(localHandler.browseRealms("7", authenticatedSession(), "1"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void ambiguousWorldSlugRejectsBareSelectionWhileBoundOrdinalsKeepTenantTargets() {
    GameplayWorldCatalog.WorldView worldA = worldView("demo", "Tenant A", 22L, 1L);
    GameplayWorldCatalog.WorldView worldB = worldView("DEMO", "Tenant B", 33L, 2L);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              DirectTextConnectScopeTarget target = invocation.getArgument(1);
              return IssueDirectTextConnectScopeResponse.newBuilder()
                  .setConnectScopeId("scope-" + target.tenantId())
                  .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                  .build();
            });
    Mockito.when(
            accountClient.joinPublicProductionMembership(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(Instant.class)))
        .thenReturn(JoinPublicProductionMembershipResponse.newBuilder().setSuccess(true).build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(List.of(worldA, worldB)),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsViewOutput worldSnapshot =
        localHandler.browseView("7", Optional.of(authenticatedSession()));
    assertThat(worldSnapshot.worlds())
        .extracting(WorldsViewOutput.WorldEntry::displayName)
        .containsExactly("Tenant A", "Tenant B");

    assertThat(localHandler.browseRealms("7", authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);

    WorldsCommandHandler.RealmBrowseResult tenantARealms =
        localHandler.browseRealms("7", authenticatedSession(), "1");
    WorldsCommandHandler.RealmBrowseResult tenantBRealms =
        localHandler.browseRealms("7", authenticatedSession(), "2");
    assertThat(tenantARealms).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(tenantBRealms).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "1"))
        .isInstanceOf(WorldsCommandHandler.JoinMembershipResult.Response.class);
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "2"))
        .isInstanceOf(WorldsCommandHandler.JoinMembershipResult.Response.class);
    Mockito.verify(accountClient)
        .issueDirectTextConnectScope(
            Mockito.argThat(context -> context.getTenantId().equals("22")),
            Mockito.argThat(
                target ->
                    target.tenantId().equals("22")
                        && target.worldSlug().equals("demo")
                        && target.realmSlug().equals("production")
                        && target.gameInstanceId().equals("1")));
    Mockito.verify(accountClient)
        .issueDirectTextConnectScope(
            Mockito.argThat(context -> context.getTenantId().equals("33")),
            Mockito.argThat(
                target ->
                    target.tenantId().equals("33")
                        && target.worldSlug().equals("DEMO")
                        && target.realmSlug().equals("production")
                        && target.gameInstanceId().equals("2")));
    Mockito.verify(accountClient)
        .joinPublicProductionMembership(
            Mockito.argThat(context -> context.getTenantId().equals("22")),
            Mockito.eq("scope-22"),
            Mockito.anyString(),
            Mockito.any(Instant.class));
    Mockito.verify(accountClient)
        .joinPublicProductionMembership(
            Mockito.argThat(context -> context.getTenantId().equals("33")),
            Mockito.eq("scope-33"),
            Mockito.anyString(),
            Mockito.any(Instant.class));
  }

  @Test
  void joinRejectsUnboundOrdinalBeforeCallingAccount() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(List.of(worldView("demo", "Demo", 22L, 1L))),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    Mockito.clearInvocations(accountClient);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "1"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void joinForwardsTheRetainedAccountScopeExpiry() {
    Instant scopeExpiresAt = Instant.ofEpochMilli(Instant.now().plusSeconds(60).toEpochMilli());
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-with-expiry")
                .setConnectScopeExpiresAt(scopeExpiresAt.toString())
                .build());
    Mockito.when(
            accountClient.joinPublicProductionMembership(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(Instant.class)))
        .thenReturn(JoinPublicProductionMembershipResponse.newBuilder().setSuccess(true).build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(List.of(worldView("demo", "Demo", 22L, 1L))),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.JoinMembershipResult.Response.class);

    Mockito.verify(accountClient)
        .joinPublicProductionMembership(
            Mockito.any(),
            Mockito.eq("scope-with-expiry"),
            Mockito.anyString(),
            Mockito.eq(scopeExpiresAt));
  }

  @Test
  void stableSlugRealmSelectorResolvesAgainstCurrentCatalogAfterReorder() {
    GameplayWorldCatalog.RealmView realmA =
        new GameplayWorldCatalog.RealmView(
            "production",
            "A",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.RealmView realmB =
        new GameplayWorldCatalog.RealmView(
            "production",
            "B",
            23L,
            2L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.WorldView worldA =
        new GameplayWorldCatalog.WorldView("a", "A", List.of(realmA));
    GameplayWorldCatalog.WorldView worldB =
        new GameplayWorldCatalog.WorldView("b", "B", List.of(realmB));
    AtomicReference<List<GameplayWorldCatalog.WorldView>> worlds =
        new AtomicReference<>(List.of(worldA, worldB));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-a")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldSupplier(worlds::get),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    localHandler.browseView("7", Optional.of(authenticatedSession()));
    worlds.set(List.of(worldB, worldA));

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms("7", authenticatedSession(), "a");

    assertThat(result)
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success -> assertThat(success.output().worldSlug()).isEqualTo("a"));
    Mockito.verify(accountClient)
        .issueDirectTextConnectScope(
            Mockito.argThat(context -> context.getTenantId().equals("22")), Mockito.any());
  }

  @Test
  void numericRealmSnapshotIgnoresPrivateReorderingAndRejectsPublicRoutingChanges() {
    GameplayWorldCatalog.RealmView production =
        new GameplayWorldCatalog.RealmView(
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
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.RealmView preview =
        new GameplayWorldCatalog.RealmView(
            "preview",
            "Preview Realm",
            22L,
            2L,
            1L,
            true,
            false,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.WorldView original =
        new GameplayWorldCatalog.WorldView("demo", "Demo World", List.of(production, preview));
    AtomicReference<List<GameplayWorldCatalog.WorldView>> worlds =
        new AtomicReference<>(List.of(original));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldSupplier(worlds::get), accountClient, scopeStore);

    assertThat(localHandler.browseRealms("7", authenticatedSession(), "demo"))
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success -> {
              assertThat(success.output().realms())
                  .extracting(RealmBrowseViewOutput.RealmEntry::ordinal)
                  .containsExactly(1);
              assertThat(success.output().realms())
                  .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
                  .containsExactly("production");
            });
    Mockito.clearInvocations(accountClient);
    worlds.set(
        List.of(
            new GameplayWorldCatalog.WorldView(
                "demo", "Demo World", List.of(preview, production))));

    assertThat(localHandler.browseCharacters("7", authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verifyNoInteractions(accountClient);

    Mockito.clearInvocations(accountClient);
    GameplayWorldCatalog.RealmView reroutedProduction =
        new GameplayWorldCatalog.RealmView(
            production.slug(),
            production.displayName(),
            production.tenantId(),
            production.gameInstanceId() + 1L,
            production.pointerVersion() + 1L,
            production.visible(),
            production.publicProductionRealm(),
            production.requiresCharacterSelection(),
            production.stateScope(),
            production.characterCreationPolicy(),
            production.catalogRevision(),
            production.realmId(),
            production.playableStateNamespaceId());
    worlds.set(
        List.of(
            new GameplayWorldCatalog.WorldView(
                "demo", "Demo World", List.of(preview, reroutedProduction))));

    assertThat(localHandler.browseCharacters("7", authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseRealmsToleratesNullRealmEnums() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setStateScope(null);
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setCharacterCreationPolicy(null);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
            Mockito.mock(AccountClient.class),
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    RealmBrowseViewOutput response = localHandler.browseRealms("demo").orElseThrow();

    assertThat(response.realms().getFirst().stateScope()).isEqualTo("UNSPECIFIED");
    assertThat(response.realms().getFirst().characterCreationPolicy()).isEqualTo("UNSPECIFIED");
  }

  @Test
  void browseCharactersFailsClosedForPublicRealmBeforeRosterPolicyProof() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubPublicConnectScope(accountClient, "scope-public");
    WorldsCommandHandler localHandler =
        authenticatedHandler(gameplayCatalogProperties, accountClient);
    SessionContext session =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt");
    assertThat(localHandler.browseRealms(session, "demo"))
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success -> assertThat(success.output().realms()).hasSize(1));

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(session, "demo", null);

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verify(accountClient).issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verifyNoMoreInteractions(accountClient);
  }

  @Test
  void browseCharactersFailsClosedForIsolatedRealmBeforeRosterPolicyProof() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setStateScope(GameplayCatalogProperties.RealmStateScope.ISOLATED);
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.COPIED_ONLY);
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setGameInstanceId(41L);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        authenticatedHandler(gameplayCatalogProperties, accountClient);
    SessionContext session =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt");
    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(session, "demo", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseCharactersRejectsNullRealmScopeBeforeReadingRoster() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(true);
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setStateScope(null);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        authenticatedHandler(gameplayCatalogProperties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            "production");

    assertThat(result)
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseCharactersMapsMalformedRealmDiscoveryPointerToAdmissionPointerUnavailable() {
    GameplayCatalogProperties properties = publicProductionProperties();
    GameplayWorldCatalog baseCatalog = TestGameplayWorldCatalogs.fromProperties(properties);
    GameplayWorldCatalog.DiscoverySnapshot snapshot = baseCatalog.readDiscoverySnapshot();
    GameplayWorldCatalog catalog = Mockito.spy(baseCatalog);
    Mockito.doReturn(snapshot).when(catalog).readDiscoverySnapshot();
    Mockito.doThrow(new GameplayWorldCatalog.AuthorityPointerUnavailableException("malformed"))
        .when(catalog)
        .readRealmDiscoverySnapshot(Mockito.any(GameplayWorldCatalog.WorldView.class));
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog,
            Mockito.mock(AccountClient.class),
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
  }

  @Test
  void privateOnlyCharacterSelectionMatchesUnknownWithoutLegacyAuthorizationReads() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void legacyAuthorityVariantsNeverExposePrivateOnlyRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    List<GetTenantMembershipForRuntimeResponse> memberships =
        List.of(
            activeMembership(),
            publicMembership(false, false, 0L, 0L, "MISSING"),
            publicMembership(true, false, 1L, 1L, "INACTIVE"),
            activeMembership().toBuilder().setEvaluatedAt("not-an-instant").build(),
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("AUTH_UNAVAILABLE"))
                .build());
    List<GetRealmAccessGrantForRuntimeResponse> grants =
        List.of(
            grant("preview", "production", true),
            grant("preview", "production", false),
            grant("preview", "production", true).toBuilder()
                .setEvaluatedAt(Instant.now().minusSeconds(60).toString())
                .build(),
            GetRealmAccessGrantForRuntimeResponse.getDefaultInstance(),
            grant("preview", "other", true));
    List<GetTenantEntitlementsForRuntimeResponse> entitlements =
        List.of(
            publicEntitlement(false),
            publicEntitlement(false).toBuilder().setGameplayAvailable(false).build(),
            publicEntitlement(false).toBuilder().setTenantId("23").build(),
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("ENTITLEMENT_UNAVAILABLE"))
                .build(),
            GetTenantEntitlementsForRuntimeResponse.getDefaultInstance());

    for (int index = 0; index < memberships.size(); index++) {
      AccountClient accountClient = Mockito.mock(AccountClient.class);
      Mockito.when(
              accountClient.getTenantMembershipForRuntime(
                  Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
          .thenReturn(memberships.get(index));
      Mockito.when(
              accountClient.getRealmAccessGrantForRuntime(
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString()))
          .thenReturn(grants.get(index));
      Mockito.when(
              accountClient.getTenantEntitlementsForRuntime(
                  Mockito.anyString(), Mockito.anyString()))
          .thenReturn(entitlements.get(index));
      WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

      assertThat(localHandler.browseRealms(authenticatedSession(), "preview"))
          .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
      assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
      Mockito.verifyNoInteractions(accountClient);
    }
  }

  @Test
  void browseCharactersDoesNotRevealDeniedPrivateRealmInPublicWorld() {
    GameplayCatalogProperties properties = publicWorldWithPrivateRealm();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult unknownRealm =
        localHandler.browseCharacters(authenticatedSession(), "demo", "guessed");
    assertThat(unknownRealm)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.InvalidRealm("demo"));

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "playtest"))
        .isEqualTo(unknownRealm);
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseCharactersRedactsPrivateRealmWithoutAccountOrEntityReads() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseCharactersRedactsPrivateOnlyWorldWithoutPublicProductionRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    SessionContext session = authenticatedSession();
    GameplayWorldCatalog catalog = TestGameplayWorldCatalogs.fromProperties(properties);
    GameplayWorldCatalog.WorldView selectedWorld = catalog.resolveWorld("preview").orElseThrow();
    GameplayWorldCatalog.RealmDiscoverySnapshot snapshot =
        catalog.realmDiscoverySnapshot(selectedWorld, catalog.visibleRealms(selectedWorld));
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(catalog, accountClient, scopeStore);

    assertThat(localHandler.browseCharacters(session, "preview", "unknown"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    scopeStore.replaceRealmSnapshot(
        session,
        "preview",
        22L,
        "preview",
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        List.of(),
        Instant.now());

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(session, "preview", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseCharactersRedactsNonPublicWorldWithAmbiguousPublicProductionCardinality() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("alternate", 22L, 2L, false)));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseCharactersRejectsPointerCutoverToDifferentRuntimeBeforeAdmissionReads() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot pointerA = admissionPointer(1L);
    GameplayAdmissionPointerSnapshot pointerB = admissionPointer(2L);
    AtomicInteger pointerReads = new AtomicInteger();
    Mockito.when(authorityService.listPointers())
        .thenAnswer(
            invocation -> {
              pointerReads.incrementAndGet();
              return List.of(pointerA);
            });
    Mockito.when(authorityService.listPointersByTenant(22L))
        .thenAnswer(
            invocation -> {
              pointerReads.incrementAndGet();
              return List.of(pointerB);
            });
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            new GameplayWorldCatalog(authorityService),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    assertThat(pointerReads).hasValue(2);
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseCharactersRejectsIncompleteRealmPointerBeforeAuthorityOrRosterReads() {
    UUID realmId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    GameplayWorldCatalog.RealmView complete =
        new GameplayWorldCatalog.RealmView(
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
            realmId,
            namespaceId);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    for (GameplayWorldCatalog.RealmView incomplete :
        List.of(
            new GameplayWorldCatalog.RealmView(
                complete.slug(),
                complete.displayName(),
                complete.tenantId(),
                complete.gameInstanceId(),
                complete.pointerVersion(),
                complete.visible(),
                complete.publicProductionRealm(),
                complete.requiresCharacterSelection(),
                complete.stateScope(),
                complete.characterCreationPolicy(),
                0L,
                realmId,
                namespaceId),
            new GameplayWorldCatalog.RealmView(
                complete.slug(),
                complete.displayName(),
                complete.tenantId(),
                complete.gameInstanceId(),
                complete.pointerVersion(),
                complete.visible(),
                complete.publicProductionRealm(),
                complete.requiresCharacterSelection(),
                complete.stateScope(),
                complete.characterCreationPolicy(),
                1L,
                null,
                namespaceId),
            new GameplayWorldCatalog.RealmView(
                complete.slug(),
                complete.displayName(),
                complete.tenantId(),
                complete.gameInstanceId(),
                complete.pointerVersion(),
                complete.visible(),
                complete.publicProductionRealm(),
                complete.requiresCharacterSelection(),
                complete.stateScope(),
                complete.characterCreationPolicy(),
                1L,
                realmId,
                null))) {
      WorldsCommandHandler localHandler =
          new WorldsCommandHandler(
              GameplayWorldCatalog.forWorldViews(
                  List.of(new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(incomplete)))),
              accountClient,
              DirectTextConnectScopeSessionStore.inMemoryForTest());

      assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
          .isEqualTo(
              WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    }
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void browseRealmsAndCharactersRedactPrivateOnlySelectorsBeforeAccountReads() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "preview"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void freshBoundPrivateSelectionFailsClosedWithAuthUnavailableBeforeAccountAccess() {
    GameplayWorldCatalog.WorldView world =
        mixedWorld(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    GameplayWorldCatalog catalog = snapshotCatalog(world);
    GameplayWorldCatalog.RealmDiscoverySnapshot snapshot =
        catalog.realmDiscoverySnapshot(world, catalog.visibleRealms(world));
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    scopeStore.replaceRealmSnapshot(
        authenticatedSession(),
        "demo",
        22L,
        "demo",
        snapshot.catalogFingerprint(),
        snapshot.ordinalTargets(),
        List.of(),
        Instant.now());
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(catalog, accountClient, scopeStore);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void staleOrExpiredPrivateSnapshotCannotBindCurrentSameSlugTarget() {
    UUID productionRealmId = UUID.randomUUID();
    UUID productionNamespaceId = UUID.randomUUID();
    GameplayWorldCatalog.WorldView previousWorld =
        mixedWorld(UUID.randomUUID(), UUID.randomUUID(), productionRealmId, productionNamespaceId);
    GameplayWorldCatalog previousCatalog = snapshotCatalog(previousWorld);
    GameplayWorldCatalog.RealmDiscoverySnapshot previousSnapshot =
        previousCatalog.realmDiscoverySnapshot(
            previousWorld, previousCatalog.visibleRealms(previousWorld));

    for (boolean expireSnapshot : List.of(false, true)) {
      GameplayWorldCatalog.WorldView currentWorld =
          expireSnapshot
              ? previousWorld
              : mixedWorld(
                  UUID.randomUUID(), UUID.randomUUID(), productionRealmId, productionNamespaceId);
      AtomicReference<List<GameplayWorldCatalog.WorldView>> currentWorlds =
          new AtomicReference<>(List.of(currentWorld));
      GameplayWorldCatalog changingCatalog =
          GameplayWorldCatalog.forWorldSupplier(currentWorlds::get);
      DirectTextConnectScopeSessionStore scopeStore =
          DirectTextConnectScopeSessionStore.inMemoryForTest();
      scopeStore.replaceRealmSnapshot(
          authenticatedSession(),
          "demo",
          22L,
          "demo",
          previousSnapshot.catalogFingerprint(),
          previousSnapshot.ordinalTargets(),
          List.of(),
          expireSnapshot ? Instant.now().minus(Duration.ofDays(2)) : Instant.now());
      AccountClient accountClient = Mockito.mock(AccountClient.class);
      Mockito.when(
              accountClient.getRealmAccessGrantForRuntime(
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString()))
          .thenReturn(grant("demo", "preview", true));
      WorldsCommandHandler localHandler =
          new WorldsCommandHandler(changingCatalog, accountClient, scopeStore);

      assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "preview"))
          .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.InvalidRealm("demo"));
      if (expireSnapshot) {
        Mockito.verifyNoInteractions(accountClient);
      } else {
        Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
            .thenReturn(
                IssueDirectTextConnectScopeResponse.newBuilder()
                    .setConnectScopeId("public-scope")
                    .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                    .build());
        assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
            .isInstanceOfSatisfying(
                WorldsCommandHandler.RealmBrowseResult.Success.class,
                success ->
                    assertThat(success.output().realms())
                        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
                        .containsExactly("production"));
        assertThat(
                scopeStore
                    .realmsSnapshot(authenticatedSession(), 22L, "demo", Instant.now())
                    .orElseThrow()
                    .ordinalTargets())
            .extracting(DirectTextConnectScopeSessionStore.RealmOrdinalTarget::realmSlug)
            .containsExactly("production");
        assertThat(
                scopeStore.publicProductionScope(
                    authenticatedSession(), 22L, "demo", Instant.now()))
            .hasValueSatisfying(
                scopedRealm ->
                    assertThat(scopedRealm.playerContext().getRealmId())
                        .isEqualTo(currentWorld.realms().getFirst().realmId().toString()));
        org.mockito.ArgumentCaptor<PlayerExecutionContext> issuedContexts =
            org.mockito.ArgumentCaptor.forClass(PlayerExecutionContext.class);
        Mockito.verify(accountClient)
            .issueDirectTextConnectScope(issuedContexts.capture(), Mockito.any());
        assertThat(issuedContexts.getValue().getRealmId())
            .isEqualTo(currentWorld.realms().getFirst().realmId().toString());
        Mockito.verify(accountClient, Mockito.never())
            .getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        Mockito.verify(accountClient, Mockito.never())
            .getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString());
        Mockito.verify(accountClient, Mockito.never())
            .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
      }
    }
  }

  @Test
  void browseCharactersWithExplicitPublicRealmFailsClosedBeforeAuthorityOrRosterReads() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void authenticatedBrowseOmitsPrivateRealmForNonMember() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicMembership(false, false, 0L, 0L, "MISSING"));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void privateOnlyWorldDenialMatchesUnknownAndDoesNotClearGrantedLobbyScope() {
    GameplayCatalogProperties properties = privateOnlyWorldProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("public-scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(properties), accountClient, scopeStore);

    assertThat(localHandler.browseRealms(authenticatedSession(), "public"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> publicSnapshot =
        scopeStore.realmsSnapshot(authenticatedSession(), 22L, "public", Instant.now());
    assertThat(publicSnapshot).isPresent();

    assertThat(localHandler.browseRealms(authenticatedSession(), "private-only"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.browseRealms(authenticatedSession(), "unknown"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(scopeStore.realmsSnapshot(authenticatedSession(), 22L, "public", Instant.now()))
        .hasValue(publicSnapshot.orElseThrow());
    Mockito.verify(accountClient, Mockito.times(1))
        .issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void privateOnlyCharacterSelectorMatchesUnknownWithoutAccountAuthority() {
    GameplayCatalogProperties properties = privateOnlyWorldProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "unknown", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void privateOnlyUnknownAmbiguousAndUnboundRealmSelectorsMatchUnknownWorld() {
    GameplayCatalogProperties properties = privateOnlyWorldProperties();
    GameplayCatalogProperties.World privateWorld = properties.getWorlds().get(1);
    GameplayCatalogProperties.Realm caseAlias =
        world("private-only", 22L, 3L, false).getRealms().getFirst();
    caseAlias.setSlug("PREVIEW");
    caseAlias.setPublicProductionRealm(false);
    privateWorld.setRealms(List.of(privateWorld.getRealms().getFirst(), caseAlias));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult unknownWorld =
        localHandler.browseCharacters(authenticatedSession(), "unknown", "preview");

    assertThat(unknownWorld).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "missing"))
        .isEqualTo(unknownWorld);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "PrEvIeW"))
        .isEqualTo(unknownWorld);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", null))
        .isEqualTo(unknownWorld);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "PRIVATE-ONLY", "1"))
        .isEqualTo(unknownWorld);
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void unboundPublicRealmOrdinalRemainsConnectScopeMismatch() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicProductionProperties(), accountClient);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void omittedCharactersIgnoreHiddenPrivateRealmCardinalityForPublicDefault() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicWorldWithPrivateRealm(), accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", null);

    // The public default does not reveal hidden cardinality, but actor authority remains closed.
    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void omittedCharactersInPrivateOnlyWorldRemainIndistinguishableFromUnknown() {
    GameplayCatalogProperties properties = privateOnlyWorldProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(properties), accountClient, scopeStore);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", null))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseRealms(authenticatedSession(), "PRIVATE-ONLY"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void mixedRealmDiscoveryAndOmittedCharacterSelectionKeepOnlyPublicProductionTarget() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(publicWorldWithPrivateRealm()),
            accountClient,
            scopeStore);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success ->
                assertThat(success.output().realms())
                    .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
                    .containsExactly("production"));
    Mockito.clearInvocations(accountClient);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", null))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void authenticatedBrowseOmitsPrivateRealmDespitePositiveLegacyGrant() {
    GameplayCatalogProperties properties = publicWorldWithPrivateRealm();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant("demo", "playtest", true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "demo");

    assertThat(result)
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success ->
                assertThat(success.output().realms())
                    .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
                    .containsExactly("production"));
    Mockito.verify(accountClient).issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, Mockito.never())
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verify(accountClient, Mockito.never())
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void authenticatedBrowseKeepsPublicScopeAndOrdinalWhileOmittingPrivateMetadata() {
    UUID realmId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    GameplayWorldCatalog.RealmView realm =
        new GameplayWorldCatalog.RealmView(
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
            realmId,
            namespaceId);
    GameplayWorldCatalog.RealmView privateRealm =
        new GameplayWorldCatalog.RealmView(
            "playtest",
            "Private Playtest",
            22L,
            2L,
            1L,
            true,
            false,
            false,
            "ISOLATED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-1")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(
                List.of(
                    new GameplayWorldCatalog.WorldView(
                        "demo", "Demo", List.of(realm, privateRealm)))),
            accountClient,
            scopeStore);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "demo");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
    Mockito.verify(accountClient).issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> snapshot =
        scopeStore.realmsSnapshot(authenticatedSession(), 22L, "demo", Instant.now());
    assertThat(snapshot).isPresent();
    assertThat(snapshot.orElseThrow().ordinalTargets())
        .extracting(DirectTextConnectScopeSessionStore.RealmOrdinalTarget::realmSlug)
        .containsExactly("production");
    assertThat(scopeStore.publicProductionScope(authenticatedSession(), 22L, "demo", Instant.now()))
        .hasValueSatisfying(
            scopedRealm ->
                assertThat(scopedRealm.playerContext().getRealmId()).isEqualTo(realmId.toString()));
    Mockito.verify(accountClient, Mockito.never())
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verify(accountClient, Mockito.never())
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void authenticatedBrowseCarriesGeneratedRequestIdIntoPublicScopeContext() {
    UUID realmId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    GameplayWorldCatalog.RealmView realm =
        new GameplayWorldCatalog.RealmView(
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
            realmId,
            namespaceId);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    AtomicReference<PlayerExecutionContext> issuedContext = new AtomicReference<>();
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              issuedContext.set(invocation.getArgument(0));
              return IssueDirectTextConnectScopeResponse.newBuilder()
                  .setConnectScopeId("scope-1")
                  .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                  .build();
            });
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(
                List.of(new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(realm)))),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "demo");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(issuedContext)
        .hasValueSatisfying(context -> assertThat(context.getRequestId()).isNotBlank());
  }

  @Test
  void authenticatedBrowsePropagatesClosedPublicRealmInsteadOfReturningEmptySuccess() {
    GameplayWorldCatalog.RealmView realm =
        new GameplayWorldCatalog.RealmView(
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
            UUID.randomUUID(),
            UUID.randomUUID());
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("REALM_UNAVAILABLE"))
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(
                List.of(new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(realm)))),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("REALM_UNAVAILABLE"));
  }

  @Test
  void joinRejectsRetainedRealmScopeWhenCurrentWorldOrdinalChanges() {
    GameplayWorldCatalog.RealmView realmA =
        new GameplayWorldCatalog.RealmView(
            "production",
            "A",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.RealmView realmB =
        new GameplayWorldCatalog.RealmView(
            "production",
            "B",
            23L,
            2L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.WorldView worldA =
        new GameplayWorldCatalog.WorldView("a", "A", List.of(realmA));
    GameplayWorldCatalog.WorldView worldB =
        new GameplayWorldCatalog.WorldView("b", "B", List.of(realmB));
    AtomicReference<List<GameplayWorldCatalog.WorldView>> worlds =
        new AtomicReference<>(List.of(worldA, worldB));
    GameplayWorldCatalog catalog = GameplayWorldCatalog.forWorldSupplier(worlds::get);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-a")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    Mockito.when(
            accountClient.joinPublicProductionMembership(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(Instant.class)))
        .thenReturn(
            JoinPublicProductionMembershipResponse.newBuilder()
                .setSuccess(false)
                .setOutcomeCode("CONNECT_SCOPE_MISMATCH")
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog, accountClient, DirectTextConnectScopeSessionStore.inMemoryForTest());

    localHandler.browseView("7", Optional.of(authenticatedSession()));
    assertThat(localHandler.browseRealms(authenticatedSession(), "1"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    worlds.set(List.of(worldB, worldA));
    assertThat(catalog.resolveWorld("1")).hasValue(worldB);
    Mockito.clearInvocations(accountClient);
    WorldsCommandHandler.JoinMembershipResult join =
        localHandler.joinPublicProductionMembership(authenticatedSession(), "1");

    assertThat(join)
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void joinRejectsRetainedScopeWhenTenantPublicRealmBecomesAmbiguous() {
    GameplayWorldCatalog.RealmView originalRealm =
        new GameplayWorldCatalog.RealmView(
            "production",
            "A",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.RealmView conflictingRealm =
        new GameplayWorldCatalog.RealmView(
            "production",
            "B",
            22L,
            2L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    GameplayWorldCatalog.WorldView originalWorld =
        new GameplayWorldCatalog.WorldView("a", "A", List.of(originalRealm));
    AtomicReference<List<GameplayWorldCatalog.WorldView>> worlds =
        new AtomicReference<>(List.of(originalWorld));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-a")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldSupplier(worlds::get),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "a"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    worlds.set(
        List.of(
            originalWorld,
            new GameplayWorldCatalog.WorldView("b", "B", List.of(conflictingRealm))));

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "a"))
        .isEqualTo(
            WorldsCommandHandler.JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verify(accountClient, Mockito.never())
        .joinPublicProductionMembership(
            Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(Instant.class));
  }

  @Test
  void authenticatedBrowseFailsClosedWhenTenantHasNoPublicProductionRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result)
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void authenticatedBrowseFailsClosedWhenPublicProductionRealmIsDuplicatedAcrossWorlds() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("alternate", 22L, 2L, false)));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "demo");

    assertThat(result)
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void crossTenantCollisionSuppressesValidSlugWhenOtherTenantHasAmbiguousPublicCardinality() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    List<GameplayAdmissionPointerSnapshot> initialPointers =
        List.of(authorityPointer("demo", "production", 22L, 1L, true));
    AtomicReference<List<GameplayAdmissionPointerSnapshot>> pointerSnapshot =
        new AtomicReference<>(initialPointers);
    Mockito.when(authorityService.listPointers()).thenAnswer(invocation -> pointerSnapshot.get());

    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-a")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    GameplayWorldCatalog catalog = new GameplayWorldCatalog(authorityService);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog, accountClient, DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);

    pointerSnapshot.set(
        List.of(
            authorityPointer("demo", "production", 22L, 1L, true),
            authorityPointer("DEMO", "production", 33L, 2L, true),
            authorityPointer("other", "production", 22L, 3L, true)));

    assertThat(catalog.publicProductionRealmCardinality(22L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.MULTIPLE);
    assertThat(catalog.publicProductionRealmCardinality(33L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.EXACTLY_ONE);
    assertThat(catalog.readDiscoverySnapshot().output().worlds()).isEmpty();
    assertThat(catalog.resolvePublicWorld("demo")).isEmpty();
    assertThat(catalog.resolvePublicWorldFromAuthoritySnapshot("demo")).isEmpty();
    Mockito.clearInvocations(accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "other"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void directRealmJoinAndCharacterCommandsMapPointerReadOutageWithoutAccountOrEntityCalls() {
    GameplayAdmissionPointerAuthorityService authorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    Mockito.when(authorityService.listPointers())
        .thenAnswer(
            invocation -> {
              if (unavailable.get()) {
                throw new DataAccessException("pointer authority unavailable");
              }
              return List.of(authorityPointer("demo", "production", 22L, 1L, true));
            });
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-a")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            new GameplayWorldCatalog(authorityService),
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    Mockito.clearInvocations(accountClient);
    unavailable.set(true);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("AUTH_UNAVAILABLE"));
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("AUTH_UNAVAILABLE"));
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);

    Mockito.doReturn(null).when(authorityService).listPointers();
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);

    Mockito.doReturn(List.of(authorityPointer("demo", "production", 0L, 1L, true)))
        .when(authorityService)
        .listPointers();
    Mockito.clearInvocations(accountClient);
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(
            WorldsCommandHandler.JoinMembershipResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);

    Mockito.clearInvocations(accountClient);
    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);
  }

  @Test
  void publicBrowseDoesNotExposePrivateWorldOrRealmMetadata() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "private-world",
                    "Private World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "secret",
                            "Secret Realm",
                            22L,
                            2L,
                            1L,
                            true,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW"))),
                new GameplayWorldCatalog.WorldView(
                    "mixed-world",
                    "Mixed World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            22L,
                            1L,
                            1L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW"),
                        new GameplayWorldCatalog.RealmView(
                            "secret",
                            "Secret Realm",
                            22L,
                            2L,
                            1L,
                            true,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW")))));
    AccountClient localAccount = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog, localAccount, DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseView().worlds())
        .extracting("slug")
        .containsExactly("mixed-world");
    assertThat(localHandler.browseRealms("mixed-world").orElseThrow().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
    assertThat(localHandler.browseRealms("private-world")).isEmpty();
    Mockito.verifyNoInteractions(localAccount);
  }

  @Test
  void charsPrivateOnlyWorldFailsBeforeEntityRead() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "private-world",
                    "Private World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "secret",
                            "Secret Realm",
                            22L,
                            2L,
                            1L,
                            true,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW")))));
    AccountClient localAccount = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog, localAccount, DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "private-world",
            null);

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidWorld.class);
    Mockito.verifyNoInteractions(localAccount);
  }

  @Test
  void charsDoesNotDistinguishPrivateRealmFromAnInvalidRealm() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "mixed-world",
                    "Mixed World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
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
                            ADMISSION_REALM_ID,
                            ADMISSION_NAMESPACE_ID),
                        new GameplayWorldCatalog.RealmView(
                            "secret",
                            "Secret Realm",
                            22L,
                            2L,
                            1L,
                            true,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW")))));
    AccountClient localAccount = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog, localAccount, DirectTextConnectScopeSessionStore.inMemoryForTest());
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt");

    assertThat(localHandler.browseCharacters(context, "mixed-world", "secret"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidRealm.class);
    assertThat(localHandler.browseCharacters(context, "mixed-world", "nonexistent"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidRealm.class);
    assertThat(localHandler.browseCharacters(context, "mixed-world", "production"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Unavailable.class);
    Mockito.verifyNoInteractions(localAccount);
  }

  private WorldsCommandHandler authenticatedHandler(
      GameplayCatalogProperties properties, AccountClient accountClient) {
    return new WorldsCommandHandler(
        TestGameplayWorldCatalogs.fromProperties(properties),
        accountClient,
        DirectTextConnectScopeSessionStore.inMemoryForTest());
  }

  private void stubPublicConnectScope(AccountClient accountClient, String scopeId) {
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId(scopeId)
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
  }

  private GameplayAdmissionPointerSnapshot authorityPointer(
      String worldSlug,
      String realmSlug,
      long tenantId,
      long gameInstanceId,
      boolean publicProduction) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldSlug,
        realmSlug,
        realmSlug,
        tenantId,
        gameInstanceId,
        1L,
        true,
        publicProduction,
        false,
        "SHARED",
        "ALLOW_NEW",
        1L,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private GameplayAdmissionPointerSnapshot admissionPointer(long gameInstanceId) {
    return new GameplayAdmissionPointerSnapshot(
        "demo",
        "Demo World",
        "production",
        "Live Realm",
        22L,
        gameInstanceId,
        1L,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        1L,
        ADMISSION_REALM_ID,
        ADMISSION_NAMESPACE_ID);
  }

  private GameplayWorldCatalog.WorldView mixedWorld(
      UUID privateRealmId,
      UUID privateNamespaceId,
      UUID productionRealmId,
      UUID productionNamespaceId) {
    GameplayWorldCatalog.RealmView production =
        new GameplayWorldCatalog.RealmView(
            "production",
            "Production",
            22L,
            1L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            productionRealmId,
            productionNamespaceId);
    GameplayWorldCatalog.RealmView preview =
        new GameplayWorldCatalog.RealmView(
            "preview",
            "Private Preview",
            22L,
            2L,
            1L,
            true,
            false,
            false,
            "ISOLATED",
            "ALLOW_NEW",
            1L,
            privateRealmId,
            privateNamespaceId);
    return new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(production, preview));
  }

  private GameplayWorldCatalog snapshotCatalog(GameplayWorldCatalog.WorldView world) {
    return GameplayWorldCatalog.forWorldViews(List.of(world));
  }

  private GameplayWorldCatalog.WorldView worldView(
      String worldSlug, String displayName, long tenantId, long gameInstanceId) {
    GameplayWorldCatalog.RealmView realm =
        new GameplayWorldCatalog.RealmView(
            "production",
            "Production",
            tenantId,
            gameInstanceId,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            1L,
            UUID.randomUUID(),
            UUID.randomUUID());
    return new GameplayWorldCatalog.WorldView(worldSlug, displayName, List.of(realm));
  }

  private GameplayCatalogProperties publicProductionProperties() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    return properties;
  }

  private GameplayCatalogProperties publicWorldWithPrivateRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    GameplayCatalogProperties.World world = world("demo", 22L, 1L, false);
    GameplayCatalogProperties.Realm production = world.getRealms().getFirst();
    production.setPublicProductionRealm(true);
    GameplayCatalogProperties.Realm playtest = world("demo", 22L, 2L, false).getRealms().getFirst();
    playtest.setSlug("playtest");
    playtest.setDisplayName("Playtest Realm");
    playtest.setPublicProductionRealm(false);
    world.setRealms(List.of(production, playtest));
    properties.setWorlds(List.of(world));
    return properties;
  }

  private void addPublicProductionAuthority(GameplayCatalogProperties properties) {
    GameplayCatalogProperties.World authority = world("production-authority", 22L, 1L, false);
    authority.getRealms().getFirst().setPublicProductionRealm(true);
    List<GameplayCatalogProperties.World> worlds = new ArrayList<>(properties.getWorlds());
    worlds.add(authority);
    properties.setWorlds(worlds);
  }

  private SessionContext authenticatedSession() {
    return new SessionContext(7L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt");
  }

  private GetTenantMembershipForRuntimeResponse activeMembership() {
    return publicMembership(true, true, 1L, 1L, "ACTIVE");
  }

  private GetTenantMembershipForRuntimeResponse publicMembership(
      boolean membershipExists,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String lifecycleState) {
    return GetTenantMembershipForRuntimeResponse.newBuilder()
        .setAccountId("123")
        .setTenantId("22")
        .setMembershipExists(membershipExists)
        .setGameplayAdmissionAllowed(gameplayAdmissionAllowed)
        .setMembershipVersion(membershipVersion)
        .setMembershipLifecycleState(lifecycleState)
        .setMembershipAuthorityGeneration(membershipAuthorityGeneration)
        .setEvaluatedAt(Instant.now().toString())
        .build();
  }

  private GetTenantEntitlementsForRuntimeResponse publicEntitlement(boolean allowPublicJoin) {
    return GetTenantEntitlementsForRuntimeResponse.newBuilder()
        .setTenantId("22")
        .setGameplayAvailable(true)
        .setAllowPublicJoin(allowPublicJoin)
        .setEntitlementVersion(1L)
        .setTenantBillingSequence(1L)
        .setEvaluatedAt(Instant.now().toString())
        .build();
  }

  private GetRealmAccessGrantForRuntimeResponse grant(
      String worldSlug, String realmSlug, boolean granted) {
    return GetRealmAccessGrantForRuntimeResponse.newBuilder()
        .setAccountId("123")
        .setTenantId("22")
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .setGranted(granted)
        .setGrantVersion(granted ? 1L : 0L)
        .setEvaluatedAt(Instant.now().toString())
        .build();
  }

  private GameplayCatalogProperties privateOnlyWorldProperties() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(
        List.of(world("public", 22L, 1L, false), world("private-only", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    properties.getWorlds().get(1).getRealms().getFirst().setSlug("preview");
    properties.getWorlds().get(1).getRealms().getFirst().setPublicProductionRealm(false);
    return properties;
  }

  private static GameplayCatalogProperties.World world(
      String slug, long tenantId, long gameInstanceId, boolean requiresCharacterSelection) {
    GameplayCatalogProperties.World world = new GameplayCatalogProperties.World();
    world.setSlug(slug);
    world.setDisplayName(slug);
    GameplayCatalogProperties.Realm realm = new GameplayCatalogProperties.Realm();
    realm.setSlug("production");
    realm.setDisplayName("Live Realm");
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setVisible(true);
    realm.setPublicProductionRealm(true);
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    world.setRealms(List.of(realm));
    return world;
  }

  @Test
  void charsDoesNotResolveHiddenRealmByDirectSlug() {
    GameplayWorldCatalog catalog =
        GameplayWorldCatalog.forWorldViews(
            List.of(
                new GameplayWorldCatalog.WorldView(
                    "mixed-world",
                    "Mixed World",
                    List.of(
                        new GameplayWorldCatalog.RealmView(
                            "production",
                            "Live Realm",
                            22L,
                            1L,
                            1L,
                            true,
                            true,
                            false,
                            "SHARED",
                            "ALLOW_NEW"),
                        new GameplayWorldCatalog.RealmView(
                            "secret",
                            "Secret Realm",
                            22L,
                            2L,
                            1L,
                            false,
                            false,
                            false,
                            "ISOLATED",
                            "ALLOW_NEW")))));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            catalog, accountClient, DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseCharacters(authenticatedSession(), "mixed-world", "secret"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidRealm("mixed-world"));
    Mockito.verifyNoInteractions(accountClient);
  }
}
