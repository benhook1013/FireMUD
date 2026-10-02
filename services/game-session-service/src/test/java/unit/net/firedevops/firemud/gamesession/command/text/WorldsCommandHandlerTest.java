package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

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
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsCommandHandlerTest {
  private static final UUID ADMISSION_REALM_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ADMISSION_NAMESPACE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(
          TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
          entityManagementClient);

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
    RealmBrowseViewOutput response = handler.browseRealms("sandbox").orElseThrow();

    assertThat(response.worldSlug()).isEqualTo("sandbox");
    assertThat(response.realms()).hasSize(1);
    assertThat(response.realms().get(0).realmSlug()).isEqualTo("production");
    assertThat(response.realms().get(0).stateScope()).isEqualTo("SHARED");
    assertThat(response.realms().get(0).characterCreationPolicy()).isEqualTo("ALLOW_NEW");
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
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    localHandler.browseView("7", Optional.of(authenticatedSession()));
    worlds.set(List.of(worldB, worldA));

    assertThat(localHandler.browseRealms("7", authenticatedSession(), "1"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void sameWorldSlugAcrossTenantsKeepsDistinctOrdinalsAndJoinTargets() {
    GameplayWorldCatalog.WorldView worldA = worldView("demo", "Tenant A", 22L, 1L);
    GameplayWorldCatalog.WorldView worldB = worldView("DEMO", "Tenant B", 33L, 2L);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
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
            GameplayWorldCatalog.forWorldViews(List.of(worldA, worldB)),
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsViewOutput worlds = localHandler.browseView("7", Optional.of(authenticatedSession()));
    assertThat(worlds.worlds())
        .extracting(WorldsViewOutput.WorldEntry::ordinal)
        .containsExactly(1, 2);
    assertThat(worlds.worlds())
        .extracting(WorldsViewOutput.WorldEntry::slug)
        .containsExactly("demo", "DEMO");
    assertThat(localHandler.browseRealms("7", authenticatedSession(), "1"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(localHandler.browseRealms("7", authenticatedSession(), "2"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);

    Mockito.clearInvocations(accountClient);
    assertThat(localHandler.browseRealms("7", authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "1"))
        .isInstanceOf(WorldsCommandHandler.JoinMembershipResult.Response.class);
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "2"))
        .isInstanceOf(WorldsCommandHandler.JoinMembershipResult.Response.class);
    Mockito.verify(accountClient)
        .joinPublicProductionMembership(
            Mockito.argThat(context -> context.getTenantId().equals("22")),
            Mockito.eq("scope"),
            Mockito.anyString(),
            Mockito.any(Instant.class));
    Mockito.verify(accountClient)
        .joinPublicProductionMembership(
            Mockito.argThat(context -> context.getTenantId().equals("33")),
            Mockito.eq("scope"),
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
            entityManagementClient,
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
            entityManagementClient,
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
            entityManagementClient,
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
  void numericRealmSelectorRejectsReorderedRealmsBeforeAccountOrEntityAdmission() {
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
    stubActivePrivateAuthorization(accountClient, "demo");
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq("demo"),
                Mockito.eq("preview"),
                Mockito.anyString()))
        .thenReturn(grant("demo", "preview", true));
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldSupplier(worlds::get),
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms("7", authenticatedSession(), "demo"))
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success -> assertThat(success.output().realms()).hasSize(2));
    Mockito.clearInvocations(accountClient, entityManagementClient);
    worlds.set(
        List.of(
            new GameplayWorldCatalog.WorldView(
                "demo", "Demo World", List.of(preview, production))));

    assertThat(localHandler.browseCharacters("7", authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseRealmsToleratesNullRealmEnums() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
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
            entityManagementClient);

    RealmBrowseViewOutput response = localHandler.browseRealms("demo").orElseThrow();

    assertThat(response.realms().getFirst().stateScope()).isEqualTo("UNSPECIFIED");
    assertThat(response.realms().getFirst().characterCreationPolicy()).isEqualTo("UNSPECIFIED");
  }

  @Test
  void browseCharactersReturnsStructuredCharacterList() {
    gameplayCatalogProperties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("sandbox", 22L, 2L, true)));
    gameplayCatalogProperties
        .getWorlds()
        .getFirst()
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    addPublicProductionAuthority(gameplayCatalogProperties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubActivePrivateAuthorization(accountClient, "demo");
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("7001")
                        .setTenantId("22")
                        .setAccountId("123")
                        .setName("Emberline")
                        .setLevel(12)
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                        .build())
                .build());
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

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
    CharacterBrowseViewOutput output =
        ((WorldsCommandHandler.CharacterBrowseResult.Success) result).output();
    assertThat(output.worldSlug()).isEqualTo("demo");
    assertThat(output.realmSlug()).isEqualTo("production");
    assertThat(output.stateScope()).isEqualTo("SHARED");
    assertThat(output.characterCreationPolicy()).isEqualTo("ALLOW_NEW");
    assertThat(output.characters()).hasSize(1);
    assertThat(output.characters().get(0).characterName()).isEqualTo("Emberline");
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void browseCharactersFailsClosedWhenNamespaceQualifiedRosterAuthorityIsUnavailable() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("CHARACTER_LIST_UNAVAILABLE")
                        .setMessage("Character list unavailable"))
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void browseCharactersUsesIsolatedStateRealmRoster() {
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
    addPublicProductionAuthority(gameplayCatalogProperties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    stubActivePrivateAuthorization(accountClient, "demo");
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("8001")
                        .setTenantId("22")
                        .setAccountId("123")
                        .setName("Forkline")
                        .setLevel(5)
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                        .build())
                .build());
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

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
    CharacterBrowseViewOutput output =
        ((WorldsCommandHandler.CharacterBrowseResult.Success) result).output();
    assertThat(output.stateScope()).isEqualTo("ISOLATED");
    assertThat(output.characterCreationPolicy()).isEqualTo("COPIED_ONLY");
    assertThat(output.characters())
        .extracting(CharacterBrowseViewOutput.CharacterEntry::characterName)
        .containsExactly("Forkline");
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount(
            "22", "123", "41", PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);
  }

  @Test
  void browseCharactersRejectsMismatchedOrDuplicateEntityRows() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicProductionProperties(), accountClient);
    net.firedevops.firemud.entitymanagement.v1.Character valid =
        net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
            .setId("7001")
            .setTenantId("22")
            .setAccountId("123")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setName("Emberline")
            .build();
    for (net.firedevops.firemud.entitymanagement.v1.Character invalid :
        List.of(
            valid.toBuilder().setTenantId("23").build(),
            valid.toBuilder().setAccountId("456").build(),
            valid.toBuilder()
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                .build(),
            valid.toBuilder().clearId().build(),
            valid.toBuilder().setId("not-a-number").build(),
            valid.toBuilder().setId("0").build(),
            valid.toBuilder().setId("-1").build(),
            valid.toBuilder().setId("9223372036854775808").build(),
            valid.toBuilder().setId("-9223372036854775809").build(),
            valid.toBuilder().clearName().build())) {
      Mockito.when(
              entityManagementClient.listCharactersByAccount(
                  "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
          .thenReturn(ListCharactersByAccountResponse.newBuilder().addCharacters(invalid).build());
      assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    }

    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(valid)
                .addCharacters(valid.toBuilder().setId("07001").build())
                .addCharacters(valid.toBuilder().setId("+7001").build())
                .build());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
  }

  @Test
  void browseCharactersRejectsNullRealmScopeBeforeReadingRoster() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setStateScope(null);
    addPublicProductionAuthority(gameplayCatalogProperties);
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
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersReturnsJoinRequiredForPublicNonMemberWithoutReadingEntityRoster() {
    GameplayCatalogProperties properties = publicProductionProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicMembership(false, false, 0L, 0L, "MISSING"));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.Failure("JOIN_REQUIRED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersReturnsJoinRequiredForPublicInactiveMemberWithoutReadingEntityRoster() {
    GameplayCatalogProperties properties = publicProductionProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicMembership(true, false, 3L, 4L, "INACTIVE"));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.Failure("JOIN_REQUIRED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersFailsClosedForUnknownPublicMembershipLifecycleWithoutReadingEntityRoster() {
    assertPublicMembershipFailsClosed(publicMembership(true, true, 1L, 1L, "UNKNOWN"));
  }

  @Test
  void browseCharactersDeniesActivePublicMembershipWithoutAdmissionWithoutReadingEntityRoster() {
    GameplayCatalogProperties properties = publicProductionProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicMembership(true, false, 1L, 1L, "ACTIVE"));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.failure(
                "PUBLIC_PRODUCTION_ADMISSION_DENIED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void
      browseCharactersFailsClosedForAbsentPublicMembershipWithActiveLifecycleWithoutReadingEntityRoster() {
    assertPublicMembershipFailsClosed(publicMembership(false, false, 0L, 0L, "ACTIVE"));
  }

  @Test
  void
      browseCharactersFailsClosedForInactivePublicMembershipWithAdmissionWithoutReadingEntityRoster() {
    assertPublicMembershipFailsClosed(publicMembership(true, true, 1L, 1L, "INACTIVE"));
  }

  @Test
  void browseCharactersDeniesPublicJoinWhenAdmissionPolicyIsClosedBeforeReadingEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(0L)
                .setMembershipAuthorityGeneration(0L)
                .setMembershipLifecycleState("MISSING")
                .setEvaluatedAt(Instant.now().toString())
                .build());
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "PUBLIC_PRODUCTION_ADMISSION_DENIED"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersReturnsUnavailableEntitlementBeforeReadingPublicEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setMembershipExists(false)
                .setGameplayAdmissionAllowed(false)
                .setMembershipVersion(0L)
                .setMembershipAuthorityGeneration(0L)
                .setMembershipLifecycleState("MISSING")
                .setEvaluatedAt(Instant.now().toString())
                .build());
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("ENTITLEMENT_UNAVAILABLE")
                        .setMessage("Entitlement authority unavailable")
                        .build())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure("ENTITLEMENT_UNAVAILABLE"));
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersDeniesRevokedPrivateMembershipBeforeReadingEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            activeMembership().toBuilder()
                .setGameplayAdmissionAllowed(false)
                .setMembershipLifecycleState("INACTIVE")
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void browseRealmsOmitsActivePrivateMembershipWithoutAdmissionWhileCharactersDeny() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicMembership(true, false, 1L, 1L, "ACTIVE"));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "preview"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void browseCharactersDoesNotRevealDeniedPrivateRealmInPublicWorld() {
    GameplayCatalogProperties properties = publicWorldWithPrivateRealm();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            publicMembership(false, false, 0L, 0L, "MISSING"),
            publicMembership(true, false, 1L, 1L, "INACTIVE"),
            activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq("demo"),
                Mockito.eq("playtest"),
                Mockito.anyString()))
        .thenReturn(grant("demo", "playtest", false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult unknownRealm =
        localHandler.browseCharacters(authenticatedSession(), "demo", "guessed");
    assertThat(unknownRealm)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.InvalidRealm("demo"));

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "playtest"))
        .isEqualTo(unknownRealm);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "playtest"))
        .isEqualTo(unknownRealm);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "playtest"))
        .isEqualTo(unknownRealm);

    Mockito.verify(accountClient, Mockito.times(3))
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(accountClient, Mockito.times(1))
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.eq("demo"),
            Mockito.eq("playtest"),
            Mockito.anyString());
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersDeniesPrivateRealmWithoutExactGrantBeforeReadingEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
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
        .thenReturn(grant(false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersRejectsZeroPublicProductionCardinalityWithExplicitRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersRejectsMultiplePublicProductionCardinalityWithExplicitRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("alternate", 22L, 2L, false)));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            new WorldsCommandHandler.CharacterBrowseResult.Failure(
                "ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
            invocation ->
                pointerReads.getAndIncrement() == 0 ? List.of(pointerA) : List.of(pointerB));
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            new GameplayWorldCatalog(authorityService),
            entityManagementClient,
            Mockito.mock(AccountClient.class),
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    assertThat(pointerReads).hasValue(2);
    Mockito.verifyNoInteractions(entityManagementClient);
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
              entityManagementClient,
              accountClient,
              DirectTextConnectScopeSessionStore.inMemoryForTest());

      assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
          .isEqualTo(
              WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    }
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersRejectsIncompleteOrStaleEntitlementBeforeReadingRoster() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicProductionProperties(), accountClient);
    GetTenantEntitlementsForRuntimeResponse valid = publicEntitlement(true);
    for (GetTenantEntitlementsForRuntimeResponse invalid :
        List.of(
            valid.toBuilder().setEntitlementVersion(0L).build(),
            valid.toBuilder().setTenantBillingSequence(0L).build(),
            valid.toBuilder().setEvaluatedAt(Instant.now().minusSeconds(16).toString()).build(),
            valid.toBuilder().setEvaluatedAt(Instant.now().plusSeconds(30).toString()).build())) {
      Mockito.when(
              accountClient.getTenantEntitlementsForRuntime(
                  Mockito.anyString(), Mockito.anyString()))
          .thenReturn(invalid);

      assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("ENTITLEMENT_UNAVAILABLE"));
    }
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseCharactersRejectsInvalidPublicMembershipBeforeReadingRoster() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicProductionProperties(), accountClient);

    for (String evaluatedAt : invalidEvaluationTimes()) {
      Mockito.when(
              accountClient.getTenantMembershipForRuntime(
                  Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
          .thenReturn(activeMembership().toBuilder().setEvaluatedAt(evaluatedAt).build());

      assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("AUTH_UNAVAILABLE"));
    }
    Mockito.verifyNoInteractions(entityManagementClient);
  }

  @Test
  void browseRealmsAndCharactersRejectInvalidPrivateMembership() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    for (String evaluatedAt : invalidEvaluationTimes()) {
      Mockito.when(
              accountClient.getTenantMembershipForRuntime(
                  Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
          .thenReturn(activeMembership().toBuilder().setEvaluatedAt(evaluatedAt).build());

      assertThat(localHandler.browseRealms(authenticatedSession(), "preview"))
          .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("AUTH_UNAVAILABLE"));
      assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("AUTH_UNAVAILABLE"));
    }
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void browseRealmsAndCharactersRejectInvalidPrivateGrant() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(true));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    for (String evaluatedAt : invalidEvaluationTimes()) {
      Mockito.when(
              accountClient.getRealmAccessGrantForRuntime(
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString(),
                  Mockito.anyString()))
          .thenReturn(grant(true).toBuilder().setEvaluatedAt(evaluatedAt).build());

      assertThat(localHandler.browseRealms(authenticatedSession(), "preview"))
          .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("AUTH_UNAVAILABLE"));
      assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("AUTH_UNAVAILABLE"));
    }
    Mockito.verifyNoInteractions(entityManagementClient);
    Mockito.verify(accountClient, Mockito.never())
        .getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void browseCharactersAllowsActivePublicMemberWhenJoiningIsClosedAndReadsEntityRoster() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setAllowPublicJoin(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.getDefaultInstance());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
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
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership(), publicMembership(false, false, 0L, 0L, "MISSING"));
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq("private-only"),
                Mockito.eq("preview"),
                Mockito.anyString()))
        .thenReturn(grant("private-only", "preview", true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(properties),
            entityManagementClient,
            accountClient,
            scopeStore);

    assertThat(localHandler.browseRealms(authenticatedSession(), "private-only"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> grantedSnapshot =
        scopeStore.realmsSnapshot(authenticatedSession(), 22L, "private-only", Instant.now());
    assertThat(grantedSnapshot).isPresent();

    assertThat(localHandler.browseRealms(authenticatedSession(), "private-only"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.browseRealms(authenticatedSession(), "unknown"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(
            scopeStore.realmsSnapshot(authenticatedSession(), 22L, "private-only", Instant.now()))
        .hasValue(grantedSnapshot.orElseThrow());
  }

  @Test
  void privateOnlyCharacterSelectorMatchesUnknownWhenDeniedAndRemainsReachableWhenGranted() {
    GameplayCatalogProperties properties = privateOnlyWorldProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicMembership(false, false, 0L, 0L, "MISSING"), activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq("private-only"),
                Mockito.eq("preview"),
                Mockito.anyString()))
        .thenReturn(grant("private-only", "preview", true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.getDefaultInstance());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "unknown", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "preview"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
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
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void unboundPublicRealmOrdinalRemainsConnectScopeMismatch() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicProductionProperties(), accountClient);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "1"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void omittedCharactersIgnoreHiddenPrivateRealmCardinalityForPublicDefault() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(ListCharactersByAccountResponse.getDefaultInstance());
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicWorldWithPrivateRealm(), accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", null);

    assertThat(result)
        .isInstanceOfSatisfying(
            WorldsCommandHandler.CharacterBrowseResult.Success.class,
            success -> assertThat(success.output().realmSlug()).isEqualTo("production"));
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    Mockito.verify(accountClient, Mockito.never())
        .getRealmAccessGrantForRuntime(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString());
  }

  @Test
  void omittedCharactersRequireChoiceAmongCallerVisibleRealmsAndKeepOrdinalBinding() {
    GameplayCatalogProperties properties = privateOnlyWorldProperties();
    GameplayCatalogProperties.World privateWorld = properties.getWorlds().get(1);
    GameplayCatalogProperties.Realm staff =
        world("private-only", 22L, 3L, false).getRealms().getFirst();
    staff.setSlug("staff");
    staff.setDisplayName("Staff Realm");
    staff.setPublicProductionRealm(false);
    privateWorld.setRealms(List.of(privateWorld.getRealms().getFirst(), staff));

    AccountClient accountClient = Mockito.mock(AccountClient.class);
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq("private-only"),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenAnswer(
            invocation -> grant("private-only", invocation.getArgument(3, String.class), true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                Mockito.eq("22"),
                Mockito.eq("123"),
                Mockito.anyString(),
                Mockito.eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)))
        .thenReturn(ListCharactersByAccountResponse.getDefaultInstance());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(properties),
            entityManagementClient,
            accountClient,
            scopeStore);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", null))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseRealms(authenticatedSession(), "PRIVATE-ONLY"))
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success -> assertThat(success.output().realms()).hasSize(2));
    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", null))
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.realmSelectionRequired("private-only"));

    WorldsCommandHandler.CharacterBrowseResult ordinalSelection =
        localHandler.browseCharacters(authenticatedSession(), "PRIVATE-ONLY", "1");

    assertThat(ordinalSelection)
        .isInstanceOfSatisfying(
            WorldsCommandHandler.CharacterBrowseResult.Success.class,
            success -> assertThat(success.output().realmSlug()).isEqualTo("preview"));
    Mockito.verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "2", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void omittedCharactersRequireExplicitRealmWhenPublicAndPrivateChoicesWereDiscovered() {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    DirectTextConnectScopeSessionStore scopeStore =
        DirectTextConnectScopeSessionStore.inMemoryForTest();
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
        .thenReturn(grant("demo", "playtest", true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(publicEntitlement(false));
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(publicWorldWithPrivateRealm()),
            entityManagementClient,
            accountClient,
            scopeStore);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOfSatisfying(
            WorldsCommandHandler.RealmBrowseResult.Success.class,
            success -> assertThat(success.output().realms()).hasSize(2));
    Mockito.clearInvocations(accountClient, entityManagementClient);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", null))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.realmSelectionRequired("demo"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void authenticatedBrowseOmitsPrivateRealmWithoutExactGrant() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
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
        .thenReturn(grant(false));
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
  }

  @Test
  void authenticatedBrowseFailsClosedWhenMembershipAuthorityUnavailable() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("AUTH_UNAVAILABLE")
                        .build())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result)
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("AUTH_UNAVAILABLE"));
  }

  @Test
  void authenticatedBrowseIncludesPrivateRealmForActiveMemberWithExactGrant() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
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
        .thenReturn(grant(true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
  }

  @Test
  void authenticatedBrowseRejectsPrivateRealmWhenEntitlementTenantDoesNotMatch() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
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
        .thenReturn(grant(true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("23")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result)
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ENTITLEMENT_UNAVAILABLE"));
  }

  @Test
  void authenticatedBrowseOmitsPrivateRealmWhenGameplayEntitlementIsUnavailable() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
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
        .thenReturn(grant(true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(false)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result).isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
  }

  @Test
  void authenticatedBrowseFailsClosedWhenGameplayEntitlementIsUnavailable() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
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
        .thenReturn(grant(true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("ENTITLEMENT_UNAVAILABLE")
                        .setMessage("Entitlement authority unavailable")
                        .build())
                .build());
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "preview");

    assertThat(result)
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ENTITLEMENT_UNAVAILABLE"));
    Mockito.verify(accountClient)
        .getTenantEntitlementsForRuntime(Mockito.eq("22"), Mockito.anyString());
  }

  @Test
  void authenticatedBrowseKeepsPublicRealmDiscoveryAndScopeIssuanceWithoutMembership() {
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
    Mockito.when(accountClient.issueDirectTextConnectScope(Mockito.any(), Mockito.any()))
        .thenReturn(
            IssueDirectTextConnectScopeResponse.newBuilder()
                .setConnectScopeId("scope-1")
                .setConnectScopeExpiresAt(Instant.now().plusSeconds(60).toString())
                .build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(
                List.of(new GameplayWorldCatalog.WorldView("demo", "Demo", List.of(realm)))),
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    WorldsCommandHandler.RealmBrowseResult result =
        localHandler.browseRealms(authenticatedSession(), "demo");

    assertThat(result).isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    assertThat(((WorldsCommandHandler.RealmBrowseResult.Success) result).output().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
    Mockito.verify(accountClient).issueDirectTextConnectScope(Mockito.any(), Mockito.any());
    Mockito.verify(accountClient, Mockito.never())
        .getTenantMembershipForRuntime(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
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
            entityManagementClient,
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
            entityManagementClient,
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
            catalog,
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

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
    Mockito.verifyNoInteractions(entityManagementClient);
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
            entityManagementClient,
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
  void crossTenantWorldSlugCollisionCannotHideTenantWidePublicRealmAmbiguity() {
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
            catalog,
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);

    pointerSnapshot.set(
        List.of(
            authorityPointer("demo", "production", 22L, 1L, true),
            authorityPointer("DEMO", "production", 33L, 2L, true),
            authorityPointer("other", "production", 22L, 3L, true)));

    assertThat(catalog.publicProductionRealmCardinality(22L))
        .isEqualTo(GameplayWorldCatalog.PublicProductionRealmCardinality.MULTIPLE);
    assertThat(catalog.readDiscoverySnapshot().output().worlds())
        .extracting(WorldsViewOutput.WorldEntry::slug)
        .containsExactly("DEMO");
    Mockito.clearInvocations(accountClient);

    assertThat(localHandler.browseRealms(authenticatedSession(), "other"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient);

    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
    Mockito.verifyNoInteractions(entityManagementClient);
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
                throw new IllegalStateException("pointer authority unavailable");
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
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isInstanceOf(WorldsCommandHandler.RealmBrowseResult.Success.class);
    Mockito.clearInvocations(accountClient, entityManagementClient);
    unavailable.set(true);

    assertThat(localHandler.browseRealms(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.failure("AUTH_UNAVAILABLE"));
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "production"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.failure("AUTH_UNAVAILABLE"));
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);

    Mockito.doReturn(null).when(authorityService).listPointers();
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  private WorldsCommandHandler authenticatedHandler(
      GameplayCatalogProperties properties, AccountClient accountClient) {
    return new WorldsCommandHandler(
        TestGameplayWorldCatalogs.fromProperties(properties),
        entityManagementClient,
        accountClient,
        DirectTextConnectScopeSessionStore.inMemoryForTest());
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

  private void assertPublicMembershipFailsClosed(GetTenantMembershipForRuntimeResponse membership) {
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(membership);
    WorldsCommandHandler localHandler =
        authenticatedHandler(publicProductionProperties(), accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "demo", "production");

    assertThat(result)
        .isEqualTo(new WorldsCommandHandler.CharacterBrowseResult.Failure("AUTH_UNAVAILABLE"));
    Mockito.verifyNoInteractions(entityManagementClient);
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

  private GetRealmAccessGrantForRuntimeResponse grant(boolean granted) {
    return grant("preview", granted);
  }

  private GetRealmAccessGrantForRuntimeResponse grant(String worldSlug, boolean granted) {
    return grant(worldSlug, "production", granted);
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

  private void stubActivePrivateAuthorization(AccountClient accountClient, String worldSlug) {
    Mockito.when(
            accountClient.getTenantMembershipForRuntime(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(activeMembership());
    Mockito.when(
            accountClient.getRealmAccessGrantForRuntime(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq(worldSlug),
                Mockito.anyString(),
                Mockito.anyString()))
        .thenReturn(grant(worldSlug, true));
    Mockito.when(
            accountClient.getTenantEntitlementsForRuntime(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt(Instant.now().toString())
                .build());
  }

  private List<String> invalidEvaluationTimes() {
    return List.of(
        Instant.now().minusSeconds(16).toString(),
        Instant.now().plusSeconds(30).toString(),
        "not-an-instant");
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
}
