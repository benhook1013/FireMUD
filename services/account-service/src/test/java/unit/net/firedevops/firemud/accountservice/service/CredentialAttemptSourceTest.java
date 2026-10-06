package net.firedevops.firemud.accountservice.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CredentialAttemptSourceTest {
  @Test
  void independentlyConstructedSourcesUseCanonicalAddressAndModeValueEquality() {
    CredentialAttemptSource first =
        source("203.0.113.8", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB);
    CredentialAttemptSource equal =
        source("203.0.113.8", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB);

    assertEquals(first, equal);
    assertEquals(first.hashCode(), equal.hashCode());
  }

  @Test
  void differentCanonicalAddressOrConnectionModeIsNotEqual() {
    CredentialAttemptSource baseline =
        source("203.0.113.8", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB);

    assertNotEquals(
        baseline, source("203.0.113.9", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB));
    assertNotEquals(
        baseline, source("203.0.113.8", CredentialAttemptSource.ConnectionMode.TRUSTED_TCP_PROXY));
  }

  @Test
  void addressAccessorReturnsDefensiveCopy() {
    CredentialAttemptSource source =
        source("203.0.113.8", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB);
    byte[] returned = source.canonicalClientAddress();
    returned[0] = 0;

    assertArrayEquals(new byte[] {(byte) 203, 0, 113, 8}, source.canonicalClientAddress());
  }

  @Test
  void stringRepresentationRedactsAddressAndRetainsMode() {
    CredentialAttemptSource source =
        source("203.0.113.8", CredentialAttemptSource.ConnectionMode.FIRST_PARTY_WEB);

    assertTrue(source.toString().contains("clientAddress=redacted"));
    assertTrue(source.toString().contains("connectionMode=FIRST_PARTY_WEB"));
    assertFalse(source.toString().contains("203.0.113.8"));
  }

  private static CredentialAttemptSource source(
      String address, CredentialAttemptSource.ConnectionMode mode) {
    return new CredentialAttemptSource(address, mode);
  }
}
