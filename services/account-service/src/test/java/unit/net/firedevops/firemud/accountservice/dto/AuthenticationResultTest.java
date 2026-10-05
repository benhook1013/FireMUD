package net.firedevops.firemud.accountservice.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AuthenticationResultTest {
  private static final String ACCOUNT_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72001";

  @Test
  void acceptsCanonicalNonNilAccountUuid() {
    AuthenticationResult result = new AuthenticationResult(ACCOUNT_UUID, "jwt");

    assertEquals(ACCOUNT_UUID, result.accountId());
  }

  @Test
  void rejectsMissingMalformedNoncanonicalAndNilAccountUuid() {
    assertThrows(IllegalArgumentException.class, () -> new AuthenticationResult(null, "jwt"));
    assertThrows(IllegalArgumentException.class, () -> new AuthenticationResult("", "jwt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticationResult("04ef66b4-c0ad-3d5b-b3b2-0e8510e7201", "jwt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticationResult("04EF66B4-C0AD-3D5B-B3B2-0E8510E72001", "jwt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthenticationResult("00000000-0000-0000-0000-000000000000", "jwt"));
  }
}
