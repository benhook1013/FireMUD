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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsCommandHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final GameplayCatalogProperties gameplayCatalogProperties =
      new GameplayCatalogProperties();
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(
          new GameplayWorldCatalog(gameplayCatalogProperties), entityManagementClient);

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
  void browseCharactersReturnsStructuredCharacterList() {
    gameplayCatalogProperties.setWorlds(
        List.of(world("demo", 22L, 1L, false), world("sandbox", 22L, 2L, true)));
    Mockito.when(
            entityManagementClient.listCharactersByAccount(
                "22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("7001")
                        .setName("Emberline")
                        .setLevel(12)
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
                        .setName("Forkline")
                        .setLevel(5)
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
}
