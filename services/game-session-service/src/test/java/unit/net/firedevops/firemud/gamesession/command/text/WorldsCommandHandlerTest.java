package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import org.junit.jupiter.api.Test;

class WorldsCommandHandlerTest {
  private final WorldsCommandHandler handler =
      new WorldsCommandHandler(new GameplayWorldCatalog(new GameSessionProperties()));

  @Test
  void describeReturnsBrowseMenu() {
    String response = handler.describe();

    assertThat(response).contains("OK WORLDS");
    assertThat(response).contains("Demo World");
    assertThat(response).contains("Builder Sandbox");
  }
}
