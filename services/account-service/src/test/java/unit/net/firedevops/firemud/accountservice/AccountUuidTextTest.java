package net.firedevops.firemud.accountservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountUuidTextTest {
  @Test
  void acceptsCanonicalNonNilUuidText() {
    String value = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";

    assertEquals(UUID.fromString(value), AccountUuidText.parseOrNull(value));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        " ",
        "42",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "4CAE05E8-7A6B-4B14-9D44-665E3EEC450B",
        "1-1-1-1-1",
        " 4cae05e8-7a6b-4b14-9d44-665e3eec450b",
        "4cae05e8-7a6b-4b14-9d44-665e3eec450b "
      })
  void rejectsMissingNumericMalformedNilAndNoncanonicalText(String value) {
    assertNull(AccountUuidText.parseOrNull(value));
  }
}
