package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.gamesession.test.GameplayCommandTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldsCommandHandlerTest {
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties));

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
            TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties));

    RealmBrowseViewOutput response = localHandler.browseRealms("demo").orElseThrow();

    assertThat(response.realms().getFirst().stateScope()).isEqualTo("UNSPECIFIED");
    assertThat(response.realms().getFirst().characterCreationPolicy()).isEqualTo("UNSPECIFIED");
  }

  @Test
  void browseCharactersFailsClosedAfterValidWorldAndDefaultRealmResolution() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));

    assertThat(
            handler.browseCharacters(GameplayCommandTestFixtures.worldBrowseDemo(), "demo", null))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Unavailable.class);
  }

  @Test
  void browseCharactersPreservesWorldAndRealmSelectionDiagnostics() {
    GameplayCatalogProperties.World demo = world("demo", 22L, 1L, false);
    GameplayCatalogProperties.Realm preview = new GameplayCatalogProperties.Realm();
    preview.setSlug("preview");
    preview.setDisplayName("Preview Realm");
    preview.setTenantId(22L);
    preview.setGameInstanceId(2L);
    preview.setVisible(true);
    preview.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    preview.setCharacterCreationPolicy(GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);
    demo.setRealms(List.of(demo.getRealms().getFirst(), preview));
    gameplayCatalogProperties.setWorlds(List.of(demo));
    var actor = GameplayCommandTestFixtures.worldBrowseDemo();

    assertThat(handler.browseCharacters(actor, "missing", null))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidWorld.class);
    assertThat(handler.browseCharacters(actor, "demo", "hidden"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.InvalidRealm.class);
    assertThat(handler.browseCharacters(actor, "demo", null))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.RealmSelectionRequired.class);
    assertThat(handler.browseCharacters(actor, "demo", "preview"))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Unavailable.class);
  }

  @Test
  void charsRejectsNonCanonicalCallerBeforeCatalogSelection() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    var actor =
        new net.firedevops.firemud.gamesession.service.SessionContext(
            1L, 22L, "123", "demo@example.com", 0L, null, 0L, "jwt");
    assertThat(handler.browseCharacters(actor, "demo", null))
        .isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Unavailable.class);
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
