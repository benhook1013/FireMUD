package net.firedevops.firemud.common.account.authority;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Shared immutable digest for Account's issuer projection installation acknowledgment. */
public final class IssuerProjectionInstallationAcknowledgmentDigestV1 {
  public static final int VERSION = 1;
  public static final int MAX_INSTALLED_PROJECTION_UTF8_BYTES = 65_536;

  private static final String DIGEST_SCHEMA = "issuer-projection-installation-ack/v1";
  private static final String OPERATION = "ISSUER_PROJECTION_INSTALLATION_ACK";
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";

  private IssuerProjectionInstallationAcknowledgmentDigestV1() {}

  /**
   * Computes the exact ten-field, byte-length-framed SHA-256 acknowledgment request digest.
   *
   * <p>Every field is encoded as strict UTF-8 and framed as canonical ASCII decimal byte length, a
   * colon, and the exact bytes. The installed projection is bounded by the storage contract.
   */
  public static String digest(
      String issuerId,
      String callerWorkloadIdentity,
      String projectionKey,
      UUID captureOperationId,
      UUID captureRequestId,
      int captureRequestDigestVersion,
      String captureRequestDigest,
      String installedProjectionJson) {
    requireText(issuerId, "issuer ID", 512);
    requireText(callerWorkloadIdentity, "caller workload identity", 512);
    requireText(projectionKey, "projection key", 2048);
    requireUuid(captureOperationId, "capture operation ID");
    requireUuid(captureRequestId, "capture request ID");
    if (!projectionKey.equals(PROJECTION_KEY_PREFIX + issuerId)
        || captureRequestDigestVersion != VERSION
        || captureRequestDigest == null
        || !captureRequestDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "Issuer projection acknowledgment capture binding is malformed");
    }

    if (installedProjectionJson == null
        || installedProjectionJson.length() > MAX_INSTALLED_PROJECTION_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "Installed projection JSON must contain 1 to "
              + MAX_INSTALLED_PROJECTION_UTF8_BYTES
              + " UTF-8 bytes");
    }
    byte[] projectionBytes = encodeUtf8(installedProjectionJson);
    if (projectionBytes.length == 0
        || projectionBytes.length > MAX_INSTALLED_PROJECTION_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "Installed projection JSON must contain 1 to "
              + MAX_INSTALLED_PROJECTION_UTF8_BYTES
              + " UTF-8 bytes");
    }
    return digestFields(
        DIGEST_SCHEMA,
        OPERATION,
        issuerId,
        callerWorkloadIdentity,
        projectionKey,
        captureOperationId.toString(),
        captureRequestId.toString(),
        Integer.toString(captureRequestDigestVersion),
        captureRequestDigest,
        installedProjectionJson);
  }

  private static String digestFields(String... fields) {
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] bytes = encodeUtf8(field);
      framed.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
      framed.write(':');
      framed.writeBytes(bytes);
    }
    return HexFormat.of().formatHex(sha256(framed.toByteArray()));
  }

  private static byte[] encodeUtf8(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Installed projection JSON is required");
    }
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(
          "Installed projection JSON is not valid Unicode", malformed);
    }
  }

  private static void requireText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          field + " must contain 1 to " + maximumLength + " characters");
    }
  }

  private static void requireUuid(UUID value, String field) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }
}
