package net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AuthenticationResultTest {
  @Test
  void acceptsCanonicalNonNilAccountUuid() {
    AuthenticationResult result =
        new AuthenticationResult("4cae05e8-7a6b-4b14-9d44-665e3eec450b", "token");

    assertEquals("4cae05e8-7a6b-4b14-9d44-665e3eec450b", result.accountId());
  }

  @Test
  void rejectsNonCanonicalOrNilAccountUuid() {
    assertThrows(IllegalArgumentException.class, () -> new AuthenticationResult(null, "token"));
    assertThrows(IllegalArgumentException.class, () -> new AuthenticationResult("12", "token"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticationResult("00000000-0000-0000-0000-000000000000", "token"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticationResult("4CAE05E8-7A6B-4B14-9D44-665E3EEC450B", "token"));
  }
}
