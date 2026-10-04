package net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthenticationResultTest {
  @Test
  void acceptsCanonicalNonNilAccountUuid() {
    AuthenticationResult result =
        new AuthenticationResult("4cae05e8-7a6b-4b14-9d44-665e3eec450b", "token");

    assertEquals("4cae05e8-7a6b-4b14-9d44-665e3eec450b", result.accountId());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        " ",
        "12",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "4CAE05E8-7A6B-4B14-9D44-665E3EEC450B",
        "1-1-1-1-1",
        " 4cae05e8-7a6b-4b14-9d44-665e3eec450b"
      })
  void rejectsNonCanonicalOrNilAccountUuid(String accountId) {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> new AuthenticationResult(accountId, "token"));

    assertEquals("accountId must be a canonical non-nil UUID", exception.getMessage());
  }
}
