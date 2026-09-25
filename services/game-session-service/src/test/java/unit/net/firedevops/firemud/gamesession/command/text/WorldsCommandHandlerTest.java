package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsCommandHandlerTest {
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
    WorldsViewOutput response = handler.browseView();

    assertThat(response.worlds()).hasSize(2);
    assertThat(response.worlds().get(0).slug()).isEqualTo("demo");
    assertThat(response.worlds().get(0).displayName()).isEqualTo("Demo World");
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
