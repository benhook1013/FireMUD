package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.util.Objects;
import javax.crypto.SecretKey;

/** Account-owned source for the current and retained keyed credential-request digest keys. */
public interface AccountGameplayCredentialRequestDigestKeySource {
  CredentialDigestKey currentKey();

  CredentialDigestKey requireKey(String keyId);

  /** Protected key material plus the horizon through which its identifier remains resolvable. */
  final class CredentialDigestKey {
    private final String keyId;
    private final SecretKey key;
    private final Instant retainedUntil;

    public CredentialDigestKey(String keyId, SecretKey key, Instant retainedUntil) {
      if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,32}")) {
        throw new IllegalArgumentException("Account credential digest key ID is malformed");
      }
      this.keyId = keyId;
      this.key = Objects.requireNonNull(key, "Account credential digest key is required");
      if (!"HmacSHA256".equalsIgnoreCase(key.getAlgorithm())) {
        throw new IllegalArgumentException("Account credential digest key must use HmacSHA256");
      }
      this.retainedUntil =
          Objects.requireNonNull(retainedUntil, "Account credential digest retention is required");
    }

    public String keyId() {
      return keyId;
    }

    public SecretKey key() {
      return key;
    }

    public Instant retainedUntil() {
      return retainedUntil;
    }

    @Override
    public String toString() {
      return "CredentialDigestKey[redacted]";
    }
  }
}
