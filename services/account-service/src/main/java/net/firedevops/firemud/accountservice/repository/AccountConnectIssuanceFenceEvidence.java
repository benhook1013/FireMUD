package net.firedevops.firemud.accountservice.repository;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable proof of the positive Account-local issuance fence for one pending operation. */
public record AccountConnectIssuanceFenceEvidence(
    String schemaName,
    UUID operationId,
    UUID accountUuid,
    UUID tenantUuid,
    String connectScopeHash,
    String requestId,
    byte[] requestDigest,
    long issuanceFence,
    long fenceSourceVersion,
    byte[] digest) {

  public static final String SCHEMA_NAME = "account-connect-issuance-fence/v1";
  private static final Pattern SCOPE_HASH = Pattern.compile("sha256:[0-9a-f]{64}");

  public AccountConnectIssuanceFenceEvidence {
    if (!SCHEMA_NAME.equals(schemaName)
        || isNil(operationId)
        || isNil(accountUuid)
        || isNil(tenantUuid)
        || connectScopeHash == null
        || !SCOPE_HASH.matcher(connectScopeHash).matches()
        || requestId == null
        || requestId.isBlank()
        || requestId.indexOf('\0') >= 0
        || requestId.length() > 128
        || hasUnpairedSurrogate(requestId)
        || issuanceFence <= 0L
        || fenceSourceVersion <= 0L) {
      throw new IllegalArgumentException("Account issuance-fence capture identity is malformed");
    }
    requestDigest = requireDigest(requestDigest, "request digest");
    digest = requireDigest(digest, "capture digest");
    byte[] expected =
        calculateDigest(
            schemaName,
            operationId,
            accountUuid,
            tenantUuid,
            connectScopeHash,
            requestId,
            requestDigest,
            issuanceFence,
            fenceSourceVersion);
    if (!MessageDigest.isEqual(expected, digest)) {
      throw new IllegalArgumentException("Account issuance-fence capture digest does not match");
    }
  }

  public static AccountConnectIssuanceFenceEvidence capture(
      UUID operationId,
      UUID accountUuid,
      UUID tenantUuid,
      String connectScopeHash,
      String requestId,
      byte[] requestDigest,
      long issuanceFence,
      long fenceSourceVersion) {
    String schemaName = SCHEMA_NAME;
    byte[] checkedRequestDigest = requireDigest(requestDigest, "request digest");
    byte[] digest =
        calculateDigest(
            schemaName,
            operationId,
            accountUuid,
            tenantUuid,
            connectScopeHash,
            requestId,
            checkedRequestDigest,
            issuanceFence,
            fenceSourceVersion);
    return new AccountConnectIssuanceFenceEvidence(
        schemaName,
        operationId,
        accountUuid,
        tenantUuid,
        connectScopeHash,
        requestId,
        checkedRequestDigest,
        issuanceFence,
        fenceSourceVersion,
        digest);
  }

  /** Reproduces the fixed-order ADR 0047 UTF-8 byte-length-framed digest preimage. */
  public static byte[] calculateDigest(
      String schemaName,
      UUID operationId,
      UUID accountUuid,
      UUID tenantUuid,
      String connectScopeHash,
      String requestId,
      byte[] requestDigest,
      long issuanceFence,
      long fenceSourceVersion) {
    if (!SCHEMA_NAME.equals(schemaName)
        || isNil(operationId)
        || isNil(accountUuid)
        || isNil(tenantUuid)
        || connectScopeHash == null
        || !SCOPE_HASH.matcher(connectScopeHash).matches()
        || requestId == null
        || requestId.isBlank()
        || requestId.indexOf('\0') >= 0
        || requestId.length() > 128
        || hasUnpairedSurrogate(requestId)
        || issuanceFence <= 0L
        || fenceSourceVersion <= 0L) {
      throw new IllegalArgumentException("Account issuance-fence capture preimage is malformed");
    }
    byte[] checkedRequestDigest = requireDigest(requestDigest, "request digest");
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    appendSegment(preimage, schemaName);
    appendSegment(preimage, operationId.toString());
    appendSegment(preimage, accountUuid.toString());
    appendSegment(preimage, tenantUuid.toString());
    appendSegment(preimage, connectScopeHash);
    appendSegment(preimage, requestId);
    appendSegment(preimage, HexFormat.of().formatHex(checkedRequestDigest));
    appendSegment(preimage, Long.toString(issuanceFence));
    appendSegment(preimage, Long.toString(fenceSourceVersion));
    try {
      return MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  @Override
  public byte[] requestDigest() {
    return requestDigest.clone();
  }

  @Override
  public byte[] digest() {
    return digest.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AccountConnectIssuanceFenceEvidence that)) {
      return false;
    }
    return issuanceFence == that.issuanceFence
        && fenceSourceVersion == that.fenceSourceVersion
        && Objects.equals(schemaName, that.schemaName)
        && Objects.equals(operationId, that.operationId)
        && Objects.equals(accountUuid, that.accountUuid)
        && Objects.equals(tenantUuid, that.tenantUuid)
        && Objects.equals(connectScopeHash, that.connectScopeHash)
        && Objects.equals(requestId, that.requestId)
        && Arrays.equals(requestDigest, that.requestDigest)
        && Arrays.equals(digest, that.digest);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            schemaName,
            operationId,
            accountUuid,
            tenantUuid,
            connectScopeHash,
            requestId,
            issuanceFence,
            fenceSourceVersion);
    result = 31 * result + Arrays.hashCode(requestDigest);
    result = 31 * result + Arrays.hashCode(digest);
    return result;
  }

  private static void appendSegment(ByteArrayOutputStream output, String value) {
    byte[] encoded = strictUtf8(value);
    output.writeBytes(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(encoded);
  }

  private static byte[] strictUtf8(String value) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(
          "Account issuance-fence field is not well-formed UTF-8", exception);
    }
  }

  private static byte[] requireDigest(byte[] value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.length != 32) {
      throw new IllegalArgumentException(fieldName + " must be 32 bytes");
    }
    return value.clone();
  }

  private static boolean isNil(UUID value) {
    return value == null || value.equals(new UUID(0L, 0L));
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
