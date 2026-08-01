package net.firedevops.firemud.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailCanonicalizationTest {
  @Test
  void normalizeReturnsEmptyForNullAndTrimsAndLowercasesValues() {
    assertThat(EmailCanonicalization.normalize(null)).isEmpty();
    assertThat(EmailCanonicalization.normalize("  DEMO@EXAMPLE.COM "))
        .isEqualTo("demo@example.com");
  }
}
