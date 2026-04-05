package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpeechPresentationNormalizerTest {

  @Test
  void normalizesLowercaseSpeechIntoSentenceCase() {
    assertThat(SpeechPresentationNormalizer.normalize(" hello travelers "))
        .isEqualTo("Hello travelers.");
  }

  @Test
  void preservesExistingTerminalPunctuation() {
    assertThat(SpeechPresentationNormalizer.normalize(" hello travelers! "))
        .isEqualTo("Hello travelers!");
  }

  @Test
  void preservesQuotedSentencePunctuation() {
    assertThat(SpeechPresentationNormalizer.normalize("  \"hello there.\"  "))
        .isEqualTo("\"Hello there.\"");
  }
}
