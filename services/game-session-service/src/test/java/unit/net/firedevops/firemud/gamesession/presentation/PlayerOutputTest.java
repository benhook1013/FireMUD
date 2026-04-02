package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlayerOutputTest {

  @Test
  void screenBufferEligibilityFollowsReplayPolicyAndOutputKind() {
    assertThat(PlayerOutput.message("hello").screenBufferEligible()).isTrue();
    assertThat(PlayerOutput.view("look").screenBufferEligible()).isTrue();
    assertThat(PlayerOutput.protocolView("OK LOOK\nlook\n\n").screenBufferEligible()).isTrue();
    assertThat(PlayerOutput.prompt("demo> ").screenBufferEligible()).isFalse();
    assertThat(PlayerOutput.notice("notice").screenBufferEligible()).isFalse();
    assertThat(PlayerOutput.error("LOGIN_REQUIRED", "Use LOGIN").screenBufferEligible()).isFalse();
  }
}
