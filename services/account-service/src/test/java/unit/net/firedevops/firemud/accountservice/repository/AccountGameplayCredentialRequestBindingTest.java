package net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AccountGameplayCredentialRequestBindingTest {
  private static final String KEY_ID = "test-account-login-key";
  private static final String DIGEST = "ab".repeat(32);

  @Test
  void acceptsVersionedLowercaseSha256BindingAndRedactsItsContents() {
    AccountGameplayCredentialRequestBinding binding =
        new AccountGameplayCredentialRequestBinding(1, KEY_ID, DIGEST);

    assertEquals(1, binding.digestSchemaVersion());
    assertEquals(KEY_ID, binding.digestKeyId());
    assertEquals(DIGEST, binding.credentialRequestDigest());
    assertEquals("AccountGameplayCredentialRequestBinding[redacted]", binding.toString());
    assertFalse(binding.toString().contains(KEY_ID));
    assertFalse(binding.toString().contains(DIGEST));
  }

  @Test
  void rejectsUnsupportedSchemaAndMalformedKeyIdentifiers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AccountGameplayCredentialRequestBinding(2, KEY_ID, DIGEST));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AccountGameplayCredentialRequestBinding(1, "", DIGEST));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AccountGameplayCredentialRequestBinding(1, "key with spaces", DIGEST));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AccountGameplayCredentialRequestBinding(1, "k".repeat(33), DIGEST));
  }

  @Test
  void rejectsMissingOrMalformedRequestDigests() {
    assertThrows(
        NullPointerException.class,
        () -> new AccountGameplayCredentialRequestBinding(1, KEY_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AccountGameplayCredentialRequestBinding(1, KEY_ID, "ab".repeat(31)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AccountGameplayCredentialRequestBinding(1, KEY_ID, "AB".repeat(32)));
  }
}
