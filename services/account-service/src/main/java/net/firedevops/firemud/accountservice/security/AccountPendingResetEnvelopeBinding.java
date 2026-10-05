package net.firedevops.firemud.accountservice.security;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Exact immutable source evidence authenticated with a pending password-reset verifier. */
public record AccountPendingResetEnvelopeBinding(
    UUID accountId,
    String tokenHash,
    LocalDateTime tokenExpiresAt,
    String requestId,
    byte[] requestDigest,
    byte[] sourceCaptureDigest,
    byte[] targetVerifierDigest) {

  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final int MAX_TEXT_LENGTH_BYTES = 4096;

  public AccountPendingResetEnvelopeBinding {
    Objects.requireNonNull(accountId, "accountId");
    if (accountId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Invalid accountId");
    }
    tokenHash = requireTokenHash(tokenHash);
    Objects.requireNonNull(tokenExpiresAt, "tokenExpiresAt");
    requestId = requireText(requestId, "requestId");
    requestDigest = copyDigest(requestDigest, "requestDigest");
    sourceCaptureDigest = copyDigest(sourceCaptureDigest, "sourceCaptureDigest");
    targetVerifierDigest = copyDigest(targetVerifierDigest, "targetVerifierDigest");
  }

  @Override
  public byte[] requestDigest() {
    return requestDigest.clone();
  }

  @Override
  public byte[] sourceCaptureDigest() {
    return sourceCaptureDigest.clone();
  }

  @Override
  public byte[] targetVerifierDigest() {
    return targetVerifierDigest.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AccountPendingResetEnvelopeBinding that)) {
      return false;
    }
    return accountId.equals(that.accountId)
        && tokenHash.equals(that.tokenHash)
        && tokenExpiresAt.equals(that.tokenExpiresAt)
        && requestId.equals(that.requestId)
        && Arrays.equals(requestDigest, that.requestDigest)
        && Arrays.equals(sourceCaptureDigest, that.sourceCaptureDigest)
        && Arrays.equals(targetVerifierDigest, that.targetVerifierDigest);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(accountId, tokenHash, tokenExpiresAt, requestId);
    result = 31 * result + Arrays.hashCode(requestDigest);
    result = 31 * result + Arrays.hashCode(sourceCaptureDigest);
    result = 31 * result + Arrays.hashCode(targetVerifierDigest);
    return result;
  }

  @Override
  public String toString() {
    return "AccountPendingResetEnvelopeBinding{evidence=<redacted>}";
  }

  private static String requireTokenHash(String value) {
    Objects.requireNonNull(value, "tokenHash");
    if (value.length() != 64) {
      throw new IllegalArgumentException("Invalid tokenHash");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
        throw new IllegalArgumentException("Invalid tokenHash");
      }
    }
    return value;
  }

  private static String requireText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()
        || hasUnpairedSurrogate(value)
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_LENGTH_BYTES) {
      throw new IllegalArgumentException("Invalid " + fieldName);
    }
    return value;
  }

  private static byte[] copyDigest(byte[] value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException(fieldName + " must be 32 bytes");
    }
    return Arrays.copyOf(value, value.length);
  }

  private static boolean hasUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }
}
