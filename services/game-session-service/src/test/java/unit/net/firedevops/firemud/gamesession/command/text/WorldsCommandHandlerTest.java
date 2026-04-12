package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.presentation.CharacterBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.RealmBrowseViewOutput;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldsCommandHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(
          new GameplayWorldCatalog(new GameSessionProperties()), entityManagementClient);

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
  }

  @Test
  void browseCharactersReturnsStructuredCharacterList() {
    Mockito.when(entityManagementClient.listCharactersByAccount("22", "123"))
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
    assertThat(output.characters()).hasSize(1);
    assertThat(output.characters().get(0).characterName()).isEqualTo("Emberline");
  }
}
