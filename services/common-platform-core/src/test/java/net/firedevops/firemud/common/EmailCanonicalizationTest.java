package net.firedevops.firemud.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailCanonicalizationTest {
  @Test
  void normalizeRejectsNull() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EmailCanonicalization.normalize(null))
        .withMessage("email must not be null");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " \t "})
  void normalizeRejectsBlankValues(String email) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EmailCanonicalization.normalize(email))
        .withMessage("email must not be blank");
  }

  @Test
  void normalizeTrimsAndLowercasesValues() {
    assertThat(EmailCanonicalization.normalize("  DEMO@EXAMPLE.COM "))
        .isEqualTo("demo@example.com");
  }
}
