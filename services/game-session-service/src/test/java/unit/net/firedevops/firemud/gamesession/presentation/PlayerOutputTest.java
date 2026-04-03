package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlayerOutputTest {

  @Test
  void screenBufferEligibilityFollowsReplayPolicyAndOutputKind() {
    assertThat(PlayerOutput.message("hello").screenBufferEligible()).isTrue();
    assertThat(PlayerOutput.view("look").screenBufferEligible()).isTrue();
    assertThat(PlayerOutput.prompt("demo> ").screenBufferEligible()).isFalse();
    assertThat(PlayerOutput.notice("notice").screenBufferEligible()).isFalse();
    assertThat(PlayerOutput.error("LOGIN_REQUIRED", "Use LOGIN").screenBufferEligible()).isFalse();
  }

  @Test
  void promptFactoryPreservesStructuredFields() {
    PlayerOutput output =
        PlayerOutput.prompt(
            "Sora> ",
            java.util.List.of(
                new PromptField("characterId", "123"), new PromptField("gameInstanceId", "9")));

    assertThat(((PromptOutput) output.payload()).fields())
        .containsExactly(
            new PromptField("characterId", "123"), new PromptField("gameInstanceId", "9"));
  }
}
