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
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
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
    gameplayCatalogProperties
        .getWorlds()
        .get(1)
        .getRealms()
        .getFirst()
        .setPublicProductionRealm(false);
    WorldsViewOutput response = handler.browseView();

    assertThat(response.worlds()).hasSize(1);
    assertThat(response.worlds().get(0).slug()).isEqualTo("demo");
    assertThat(response.worlds().get(0).displayName()).isEqualTo("Demo World");
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
  void ambiguousWorldSlugAcrossTenantsCannotSelectOrJoinEitherTenant() {
    GameplayWorldCatalog.WorldView worldA = worldView("demo", "Tenant A", 22L, 1L);
    GameplayWorldCatalog.WorldView worldB = worldView("DEMO", "Tenant B", 33L, 2L);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldViews(List.of(worldA, worldB)),
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseRealms("7", authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.RealmBrowseResult.invalidSelector());
    assertThat(localHandler.joinPublicProductionMembership(authenticatedSession(), "demo"))
        .isEqualTo(WorldsCommandHandler.JoinMembershipResult.failure("CONNECT_SCOPE_MISMATCH"));
    Mockito.verifyNoInteractions(accountClient);
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
            true,
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
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            GameplayWorldCatalog.forWorldSupplier(worlds::get),
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    localHandler.browseRealms("7", authenticatedSession(), "demo");
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
  void browseCharactersFailsClosedForPublicRealmBeforeRosterPolicyProof() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        authenticatedHandler(gameplayCatalogProperties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersFailsClosedForIsolatedRealmBeforeRosterPolicyProof() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
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
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(true);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler =
        authenticatedHandler(gameplayCatalogProperties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.unavailable());
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
            null);

    assertThat(result)
        .isEqualTo(
            WorldsCommandHandler.CharacterBrowseResult.failure("ADMISSION_POINTER_UNAVAILABLE"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersRedactsPrivateOnlyWorldWithoutAccountOrEntityReads() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    properties.getWorlds().getFirst().getRealms().getFirst().setPublicProductionRealm(false);
    addPublicProductionAuthority(properties);
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
    Mockito.clearInvocations(accountClient);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "playtest"))
        .isEqualTo(unknownRealm);
    assertThat(localHandler.browseCharacters(authenticatedSession(), "demo", "playtest"))
        .isEqualTo(unknownRealm);

    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersRedactsPrivateOnlyWorldWithoutPublicProductionRealm() {
    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(world("preview", 22L, 2L, false)));
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(authenticatedSession(), "preview", "production");

    assertThat(result).isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
      Mockito.clearInvocations(accountClient);
      assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
      Mockito.verifyNoInteractions(accountClient, entityManagementClient);
    }
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
      Mockito.clearInvocations(accountClient);
      assertThat(localHandler.browseCharacters(authenticatedSession(), "preview", "production"))
          .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
      Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
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
  void privateOnlyCharacterSelectorMatchesUnknownWithoutAccountAuthority() {
    GameplayCatalogProperties properties = privateOnlyWorldProperties();
    AccountClient accountClient = Mockito.mock(AccountClient.class);
    WorldsCommandHandler localHandler = authenticatedHandler(properties, accountClient);

    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "unknown", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
    assertThat(localHandler.browseCharacters(authenticatedSession(), "private-only", "preview"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidWorld());
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
                Mockito.any(), Mockito.anyString(), Mockito.anyString()))
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
        .joinPublicProductionMembership(Mockito.any(), Mockito.anyString(), Mockito.anyString());
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
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    world.setRealms(List.of(realm));
    return world;
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
    WorldsCommandHandler localHandler = new WorldsCommandHandler(catalog, entityManagementClient);

    assertThat(localHandler.browseView().worlds())
        .extracting("slug")
        .containsExactly("mixed-world");
    assertThat(localHandler.browseRealms("mixed-world").orElseThrow().realms())
        .extracting(RealmBrowseViewOutput.RealmEntry::realmSlug)
        .containsExactly("production");
    assertThat(localHandler.browseRealms("private-world")).isEmpty();
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
    WorldsCommandHandler localHandler = new WorldsCommandHandler(catalog, entityManagementClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "private-world",
            null);

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidWorld.class);
    Mockito.verifyNoInteractions(entityManagementClient);
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
    WorldsCommandHandler localHandler = new WorldsCommandHandler(catalog, entityManagementClient);
    SessionContext context =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt");

    assertThat(localHandler.browseCharacters(context, "mixed-world", "secret"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidRealm.class);
    assertThat(localHandler.browseCharacters(context, "mixed-world", "nonexistent"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidRealm.class);
    assertThat(localHandler.browseCharacters(context, "mixed-world", null))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Unavailable.class);
    Mockito.verifyNoInteractions(entityManagementClient);
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
            catalog,
            entityManagementClient,
            accountClient,
            DirectTextConnectScopeSessionStore.inMemoryForTest());

    assertThat(localHandler.browseCharacters(authenticatedSession(), "mixed-world", "secret"))
        .isEqualTo(WorldsCommandHandler.CharacterBrowseResult.invalidRealm("mixed-world"));
    Mockito.verifyNoInteractions(accountClient, entityManagementClient);
  }

  @Test
  void browseCharactersFailsClosedForValidSelectorBeforeEntityRosterRead() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));

    WorldsCommandHandler.CharacterBrowseResult result =
        handler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Unavailable.class);
    Mockito.verifyNoInteractions(entityManagementClient);
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
