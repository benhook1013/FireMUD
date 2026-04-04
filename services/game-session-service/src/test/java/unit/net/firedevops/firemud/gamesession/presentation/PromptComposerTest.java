package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class PromptComposerTest {
  private final PromptComposer composer = new PromptComposer();

  @Test
  void composeIncludesStructuredFieldsAlongsideClassicPromptText() {
    SessionContext context =
        new SessionContext(41L, 22L, 77L, "demo@example.com", 123L, "Sora", 9L, "R-1021", "jwt");

    PlayerOutput prompt = composer.compose(context).orElseThrow();

    assertThat(prompt.kind()).isEqualTo(PlayerOutputKind.PROMPT);
    PromptOutput payload = (PromptOutput) prompt.payload();
    assertThat(payload.text()).isEqualTo("Sora> ");
    assertThat(payload.fields())
        .containsExactly(
            new PromptField("characterId", "123"),
            new PromptField("gameInstanceId", "9"),
            new PromptField("roomId", "R-1021"),
            new PromptField("actorName", "Sora"));
  }

  @Test
  void composeFallsBackToBarePromptWhileStillPublishingIdentifiers() {
    SessionContext context =
        new SessionContext(41L, 22L, 77L, null, 123L, null, 9L, "R-1021", "jwt");

    PlayerOutput prompt = composer.compose(context).orElseThrow();

    PromptOutput payload = (PromptOutput) prompt.payload();
    assertThat(payload.text()).isEqualTo("> ");
    assertThat(payload.fields())
        .containsExactly(
            new PromptField("characterId", "123"),
            new PromptField("gameInstanceId", "9"),
            new PromptField("roomId", "R-1021"));
  }
}
