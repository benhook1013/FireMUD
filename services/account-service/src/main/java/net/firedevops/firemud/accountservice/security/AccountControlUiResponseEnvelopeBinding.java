package net.firedevops.firemud.accountservice.security;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable targetless binding for the exact original control-UI issuance response. The authority
 * capture and issuance fence digests identify complete retained evidence; neither digest alone
 * grants authority or proves currentness.
 */
public final class AccountControlUiResponseEnvelopeBinding {
  public static final String CONTROL_UI_PROFILE = "control-ui";
  public static final String CONTROL_UI_AUDIENCE = "control-ui";

  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

  private final String accountId;
  private final String operationId;
  private final String requestId;
  private final byte[] requestDigest;
  private final String tokenHash;
  private final byte[] responseDigest;
  private final byte[] authorityCaptureDigest;
  private final byte[] issuanceFenceDigest;
  private final Instant issuedAt;
  private final Instant expiresAt;

  public AccountControlUiResponseEnvelopeBinding(
      String accountId,
      String operationId,
      String requestId,
      byte[] requestDigest,
      String tokenHash,
      byte[] responseDigest,
      byte[] authorityCaptureDigest,
      byte[] issuanceFenceDigest,
      Instant issuedAt,
      Instant expiresAt) {
    this.accountId = canonicalNonNilUuid(accountId, "accountId");
    this.operationId = canonicalNonNilUuid(operationId, "operationId");
    this.requestId = canonicalNonNilUuid(requestId, "requestId");
    this.requestDigest = copyDigest(requestDigest, "requestDigest");
    this.tokenHash = requireTokenHash(tokenHash);
    this.responseDigest = copyDigest(responseDigest, "responseDigest");
    this.authorityCaptureDigest = copyDigest(authorityCaptureDigest, "authorityCaptureDigest");
    this.issuanceFenceDigest = copyDigest(issuanceFenceDigest, "issuanceFenceDigest");
    this.issuedAt = requirePositiveEpochSecond(issuedAt, "issuedAt");
    this.expiresAt = requirePositiveEpochSecond(expiresAt, "expiresAt");
    if (!this.issuedAt.isBefore(this.expiresAt)) {
      throw new IllegalArgumentException("expiresAt must follow issuedAt");
    }
  }

  public String accountId() {
    return accountId;
  }

  public String operationId() {
    return operationId;
  }

  public String requestId() {
    return requestId;
  }

  public String profile() {
    return CONTROL_UI_PROFILE;
  }

  public String audience() {
    return CONTROL_UI_AUDIENCE;
  }

  public byte[] requestDigest() {
    return requestDigest.clone();
  }

  public String tokenHash() {
    return tokenHash;
  }

  public byte[] responseDigest() {
    return responseDigest.clone();
  }

  public byte[] authorityCaptureDigest() {
    return authorityCaptureDigest.clone();
  }

  public byte[] issuanceFenceDigest() {
    return issuanceFenceDigest.clone();
  }

  public Instant issuedAt() {
    return issuedAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AccountControlUiResponseEnvelopeBinding that)) {
      return false;
    }
    return accountId.equals(that.accountId)
        && operationId.equals(that.operationId)
        && requestId.equals(that.requestId)
        && Arrays.equals(requestDigest, that.requestDigest)
        && tokenHash.equals(that.tokenHash)
        && Arrays.equals(responseDigest, that.responseDigest)
        && Arrays.equals(authorityCaptureDigest, that.authorityCaptureDigest)
        && Arrays.equals(issuanceFenceDigest, that.issuanceFenceDigest)
        && issuedAt.equals(that.issuedAt)
        && expiresAt.equals(that.expiresAt);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(accountId, operationId, requestId, tokenHash, issuedAt, expiresAt);
    result = 31 * result + Arrays.hashCode(requestDigest);
    result = 31 * result + Arrays.hashCode(responseDigest);
    result = 31 * result + Arrays.hashCode(authorityCaptureDigest);
    result = 31 * result + Arrays.hashCode(issuanceFenceDigest);
    return result;
  }

  @Override
  public String toString() {
    return "AccountControlUiResponseEnvelopeBinding{evidence=<redacted>}";
  }

  private static String canonicalNonNilUuid(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)
          || (parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L)) {
        throw new IllegalArgumentException("Invalid " + fieldName);
      }
      return value;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid " + fieldName, exception);
    }
  }

  private static byte[] copyDigest(byte[] value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException("Invalid " + fieldName);
    }
    return value.clone();
  }

  private static String requireTokenHash(String value) {
    Objects.requireNonNull(value, "tokenHash");
    if (!TOKEN_HASH_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid tokenHash");
    }
    return value;
  }

  private static Instant requirePositiveEpochSecond(Instant value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.getEpochSecond() <= 0L || value.getNano() != 0) {
      throw new IllegalArgumentException("Invalid " + fieldName);
    }
    return value;
  }
}
