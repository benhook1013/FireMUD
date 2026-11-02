package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GameTenantCreationDigestTest {
  @Test
  void acceptsOwnerLimitsAndPreservesAbsentOrEmptyDescription() {
    assertThatCode(
            () ->
                GameTenantCreationDigest.validateCandidateInputs(
                    "😀".repeat(36), "😀".repeat(100), "😀".repeat(255)))
        .doesNotThrowAnyException();
    assertThatCode(() -> GameTenantCreationDigest.validateCandidateInputs("source", "", null))
        .doesNotThrowAnyException();
    assertThatCode(() -> GameTenantCreationDigest.validateCandidateInputs("source", "", ""))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsOversizedAndMalformedUnicodeBeforeDispatch() {
    assertInvalid("a".repeat(37), "name", null);
    assertInvalid("source", "a".repeat(101), null);
    assertInvalid("source", "name", "a".repeat(256));
    assertInvalid("source", "name", "😀".repeat(256));
    assertInvalid("  ", "name", null);
    assertInvalid(null, "name", null);
    assertInvalid("source", null, null);
    assertInvalid("source" + (char) 0xD800, "name", null);
    assertInvalid("source", "name" + (char) 0xDC00, null);
    assertInvalid("source", "name", "description" + (char) 0xD800);
  }

  private static void assertInvalid(String source, String name, String description) {
    assertThatThrownBy(
            () -> GameTenantCreationDigest.validateCandidateInputs(source, name, description))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
