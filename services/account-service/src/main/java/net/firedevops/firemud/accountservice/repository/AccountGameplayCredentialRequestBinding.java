package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.regex.Pattern;

/** Keyed, versioned credential-request identity persisted with one gameplay LOGIN operation. */
public record AccountGameplayCredentialRequestBinding(
    int digestSchemaVersion, String digestKeyId, String credentialRequestDigest) {
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
  private static final Pattern HMAC_SHA256 = Pattern.compile("[0-9a-f]{64}");

  public AccountGameplayCredentialRequestBinding {
    if (digestSchemaVersion != 1) {
      throw new IllegalArgumentException("Unsupported credential digest schema version");
    }
    if (digestKeyId == null || !KEY_ID.matcher(digestKeyId).matches()) {
      throw new IllegalArgumentException("Credential digest key identifier is malformed");
    }
    Objects.requireNonNull(credentialRequestDigest, "Credential request digest is required");
    if (!HMAC_SHA256.matcher(credentialRequestDigest).matches()) {
      throw new IllegalArgumentException("Credential request digest is malformed");
    }
  }

  @Override
  public String toString() {
    return "AccountGameplayCredentialRequestBinding[redacted]";
  }
}
