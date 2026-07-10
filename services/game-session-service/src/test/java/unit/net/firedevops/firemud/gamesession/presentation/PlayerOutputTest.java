package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlayerOutputTest {

  @Test
  void screenBufferEligibilityFollowsReplayPolicyAndOutputKind() {
    assertThat(PlayerOutput.message("hello").screenBufferEligible()).isTrue();
    assertThat(
            PlayerOutput.view(
                    new LookViewOutput(
                        "R-100",
                        "Look Hall",
                        "look",
                        "look detail",
                        true,
                        java.util.List.of(),
                        java.util.List.of()))
                .screenBufferEligible())
        .isTrue();
    assertThat(
            PlayerOutput.view(
                    new InventoryViewOutput(
                        InventoryViewOutput.Source.INVENTORY,
                        "Inventory:",
                        java.util.List.of("- Torch x2 (A small torch)")))
                .screenBufferEligible())
        .isTrue();
    assertThat(
            PlayerOutput.view(
                    new WorldsViewOutput(
                        java.util.List.of(
                            new WorldsViewOutput.WorldEntry(1, "demo", "Demo World", 1L, false))))
                .screenBufferEligible())
        .isFalse();
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
