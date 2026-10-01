package net.firedevops.firemud.common.tenant;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Canonical length-framed digests for fresh Game Design tenant creation evidence. */
public final class GameTenantCreationDigest {
  private static final String REQUEST_DOMAIN = "game-design-tenant-creation-request/v1";
  private static final String RECEIPT_DOMAIN = "game-design-tenant-creation-receipt/v1";
  private static final String DIGEST_PREFIX = "sha256:";

  private GameTenantCreationDigest() {}

  public static String requestDigest(
      String targetNamespace,
      UUID creationRequestId,
      String sourceGameTenantKey,
      String name,
      String description) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(creationRequestId, "creationRequestId");
    Objects.requireNonNull(sourceGameTenantKey, "sourceGameTenantKey");
    Objects.requireNonNull(name, "name");
    return digest(
        REQUEST_DOMAIN,
        targetNamespace,
        creationRequestId.toString(),
        sourceGameTenantKey,
        name,
        description == null ? "absent" : "present",
        description == null ? "" : description);
  }

  public static String evidenceDigest(
      String targetNamespace,
      UUID creationRequestId,
      UUID operationId,
      String requestDigest,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(creationRequestId, "creationRequestId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(sourceGameTenantKey, "sourceGameTenantKey");
    if (sourceGameRowId <= 0) {
      throw new IllegalArgumentException("sourceGameRowId must be positive");
    }
    if (!"NEW_GAME_ROW".equals(provenanceKind)) {
      throw new IllegalArgumentException("Fresh creation provenance must be NEW_GAME_ROW");
    }
    if (!isDigest(requestDigest)) {
      throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256 digest");
    }
    return digest(
        RECEIPT_DOMAIN,
        targetNamespace,
        creationRequestId.toString(),
        operationId.toString(),
        requestDigest,
        canonicalTenantId.toString(),
        Long.toString(sourceGameRowId),
        sourceGameTenantKey,
        provenanceKind);
  }

  public static int utf8ByteLength(String value) {
    Objects.requireNonNull(value, "value");
    int byteLength = 0;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException("Creation inputs must contain valid Unicode");
        }
        byteLength += 4;
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("Creation inputs must contain valid Unicode");
      } else if (current <= 0x7f) {
        byteLength++;
      } else if (current <= 0x7ff) {
        byteLength += 2;
      } else {
        byteLength += 3;
      }
    }
    return byteLength;
  }

  public static boolean isDigest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  static String digest(String... segments) {
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    for (String segment : segments) {
      byte[] bytes = encodeUtf8(segment);
      byte[] length = Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8);
      preimage.writeBytes(length);
      preimage.write(':');
      preimage.writeBytes(bytes);
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray());
      return DIGEST_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] encodeUtf8(String value) {
    Objects.requireNonNull(value, "value");
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
      throw new IllegalArgumentException("Creation inputs must contain valid Unicode", exception);
    }
  }
}
