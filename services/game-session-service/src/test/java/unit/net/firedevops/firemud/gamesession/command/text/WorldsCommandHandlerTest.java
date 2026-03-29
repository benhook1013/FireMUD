package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorldsCommandHandlerTest {
  private final WorldsCommandHandler handler = new WorldsCommandHandler();

  @Test
  void describeReturnsBrowseMenu() {
    String response = handler.describe();

    assertThat(response).contains("OK WORLDS");
    assertThat(response).contains("Demo World");
    assertThat(response).contains("Builder Sandbox");
  }
}
