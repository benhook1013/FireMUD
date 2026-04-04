package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import org.junit.jupiter.api.Test;

class WorldsCommandHandlerTest {
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(new GameplayWorldCatalog(new GameSessionProperties()));

  @Test
  void browseViewReturnsStructuredWorldList() {
    WorldsViewOutput response = handler.browseView();

    assertThat(response.worlds()).hasSize(2);
    assertThat(response.worlds().get(0).slug()).isEqualTo("demo");
    assertThat(response.worlds().get(0).displayName()).isEqualTo("Demo World");
    assertThat(response.worlds().get(1).displayName()).isEqualTo("Builder Sandbox");
  }
}
