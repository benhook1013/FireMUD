package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import org.junit.jupiter.api.BeforeEach;
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
            TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
            entityManagementClient);

    RealmBrowseViewOutput response = localHandler.browseRealms("demo").orElseThrow();

    assertThat(response.realms().getFirst().stateScope()).isEqualTo("UNSPECIFIED");
    assertThat(response.realms().getFirst().characterCreationPolicy()).isEqualTo("UNSPECIFIED");
  }

  @Test
  void browseCharactersReturnsStructuredCharacterList() {
    gameplayCatalogProperties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("sandbox", 23L, 2L, true)));
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

    WorldsCommandHandler.CharacterBrowseResult result =
        handler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

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
  void browseCharactersUsesIsolatedStateRealmRoster() {
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

    WorldsCommandHandler.CharacterBrowseResult result =
        handler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

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
  void browseCharactersFallsBackToUnspecifiedRosterScopeForNullRealmScope() {
    gameplayCatalogProperties.setWorlds(List.of(world("demo", 22L, 1L, false)));
    gameplayCatalogProperties.getWorlds().getFirst().getRealms().getFirst().setStateScope(null);
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED))
        .thenReturn(ListCharactersByAccountResponse.newBuilder().build());
    WorldsCommandHandler localHandler =
        new WorldsCommandHandler(
            TestGameplayWorldCatalogs.fromProperties(gameplayCatalogProperties),
            entityManagementClient);

    WorldsCommandHandler.CharacterBrowseResult result =
        localHandler.browseCharacters(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "jwt"),
            "demo",
            null);

    assertThat(result).isInstanceOf(WorldsCommandHandler.CharacterBrowseResult.Success.class);
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
