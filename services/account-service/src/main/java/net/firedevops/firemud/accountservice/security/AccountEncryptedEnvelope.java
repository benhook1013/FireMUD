package net.firedevops.firemud.accountservice.security;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Persistable encrypted response data. Plaintext and key material are never included. */
public record AccountEncryptedEnvelope(
    int formatVersion,
    String keyId,
    AccountEnvelopePurpose purpose,
    byte[] nonce,
    byte[] ciphertext) {

  public static final int CURRENT_FORMAT_VERSION = 1;
  public static final int NONCE_LENGTH_BYTES = 12;
  public static final int AUTHENTICATION_TAG_LENGTH_BYTES = 16;
  public static final int MAX_PLAINTEXT_LENGTH_BYTES = 64 * 1024;
  public static final int MAX_CIPHERTEXT_LENGTH_BYTES =
      MAX_PLAINTEXT_LENGTH_BYTES + AUTHENTICATION_TAG_LENGTH_BYTES;

  private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  public AccountEncryptedEnvelope {
    if (formatVersion != CURRENT_FORMAT_VERSION) {
      throw new IllegalArgumentException("Unsupported envelope format version");
    }
    Objects.requireNonNull(keyId, "keyId");
    if (!KEY_ID_PATTERN.matcher(keyId).matches()) {
      throw new IllegalArgumentException("Invalid envelope key ID");
    }
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(ciphertext, "ciphertext");
    if (nonce.length != NONCE_LENGTH_BYTES
        || ciphertext.length < AUTHENTICATION_TAG_LENGTH_BYTES
        || ciphertext.length > MAX_CIPHERTEXT_LENGTH_BYTES) {
      throw new IllegalArgumentException("Invalid envelope length");
    }
    nonce = Arrays.copyOf(nonce, nonce.length);
    ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
  }

  @Override
  public byte[] nonce() {
    return nonce.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AccountEncryptedEnvelope that)) {
      return false;
    }
    return formatVersion == that.formatVersion
        && keyId.equals(that.keyId)
        && purpose == that.purpose
        && Arrays.equals(nonce, that.nonce)
        && Arrays.equals(ciphertext, that.ciphertext);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(formatVersion, keyId, purpose);
    result = 31 * result + Arrays.hashCode(nonce);
    result = 31 * result + Arrays.hashCode(ciphertext);
    return result;
  }

  @Override
  public String toString() {
    return "AccountEncryptedEnvelope{formatVersion="
        + formatVersion
        + ", keyId='"
        + keyId
        + "', purpose="
        + purpose
        + ", encryptedData=<redacted>}";
  }
}
