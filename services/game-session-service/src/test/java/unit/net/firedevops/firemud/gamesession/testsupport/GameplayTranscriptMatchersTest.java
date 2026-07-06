package net.firedevops.firemud.gamesession.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GameplayTranscriptMatchersTest {

  @Test
  void canonicalLookMatcherDoesNotStripTrailingMultilineTranscriptContent() {
    String response =
        GameplayTranscriptMatchers.canonicalLook()
            + "\n\nA carved sign points deeper into the hall.\nA warning placard >";

    assertThat(GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt().test(response))
        .isFalse();
  }
}
