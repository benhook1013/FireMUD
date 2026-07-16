package net.firedevops.firemud.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResumeTranscriptCanonicalizerTest {
  @Test
  void sortsObjectKeysByTheirNfcNormalizedForm() {
    String canonicalJson =
        ResumeTranscriptCanonicalizer.canonicalJson(
            1L,
            2L,
            3L,
            4L,
            0L,
            "VIEW",
            "REPLAY",
            "FULL",
            "view",
            "{\"f\":1,\"e\\u0301\":2}",
            "text");

    assertThat(canonicalJson).contains("\"payload\":{\"f\":1,\"é\":2}");
  }

  @Test
  void rejectsObjectKeysThatCollapseAfterNfcNormalization() {
    assertThatThrownBy(
            () ->
                ResumeTranscriptCanonicalizer.canonicalJson(
                    1L,
                    2L,
                    3L,
                    4L,
                    0L,
                    "VIEW",
                    "REPLAY",
                    "FULL",
                    "view",
                    "{\"e\\u0301\":1,\"é\":2}",
                    "text"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate keys after NFC normalization");
  }

  @Test
  void rejectsExactDuplicateJsonKeysBeforeCanonicalization() {
    assertThatThrownBy(
            () ->
                ResumeTranscriptCanonicalizer.canonicalJson(
                    1L,
                    2L,
                    3L,
                    4L,
                    0L,
                    "VIEW",
                    "REPLAY",
                    "FULL",
                    "view",
                    "{\"room\":\"R-1\",\"room\":\"R-2\"}",
                    "text"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate keys");
  }

  @Test
  void preservesNonJsonLegacyPayloadAsAString() {
    String canonicalJson =
        ResumeTranscriptCanonicalizer.canonicalJson(
            1L, 2L, 3L, 4L, 0L, "VIEW", "REPLAY", "FULL", "legacy", "not-json", "text");

    assertThat(canonicalJson).contains("\"payload\":\"not-json\"");
  }
}
