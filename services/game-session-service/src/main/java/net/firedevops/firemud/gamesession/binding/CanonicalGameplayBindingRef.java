package net.firedevops.firemud.gamesession.binding;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Exact {@code bindingRef/v1} bytes used by the canonical issuer-partition contract. */
public final class CanonicalGameplayBindingRef {
  private static final byte[] DOMAIN = "bindingRef/v1".getBytes(StandardCharsets.US_ASCII);

  private final byte[] bytes;

  private CanonicalGameplayBindingRef(byte[] bytes) {
    this.bytes = bytes.clone();
  }

  public static CanonicalGameplayBindingRef of(
      UUID tenantId, UUID gameInstanceId, String sessionId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(gameInstanceId, "gameInstanceId");
    return of(tenantId.toString(), gameInstanceId.toString(), sessionId);
  }

  public static CanonicalGameplayBindingRef of(
      String tenantId, String gameInstanceId, String sessionId) {
    byte[] tenantBytes = encodeCanonicalIdentifier(tenantId, "tenantId");
    byte[] instanceBytes = encodeCanonicalIdentifier(gameInstanceId, "gameInstanceId");
    byte[] sessionBytes = encodeCanonicalIdentifier(sessionId, "sessionId");

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    appendSegment(output, DOMAIN);
    appendSegment(output, tenantBytes);
    appendSegment(output, instanceBytes);
    appendSegment(output, sessionBytes);
    return new CanonicalGameplayBindingRef(output.toByteArray());
  }

  /** Rehydrates an exact persisted carrier without translating or normalizing its segments. */
  public static CanonicalGameplayBindingRef fromExactBytes(byte[] exactBytes) {
    Objects.requireNonNull(exactBytes, "exactBytes");
    Cursor cursor = new Cursor(exactBytes);
    byte[] domain = cursor.readSegment();
    if (!Arrays.equals(domain, DOMAIN)) {
      throw new IllegalArgumentException("bindingRef has an unsupported version prefix");
    }
    validateIdentifierSegment(cursor.readSegment(), "tenantId");
    validateIdentifierSegment(cursor.readSegment(), "gameInstanceId");
    validateIdentifierSegment(cursor.readSegment(), "sessionId");
    if (!cursor.atEnd()) {
      throw new IllegalArgumentException("bindingRef contains trailing bytes");
    }
    return new CanonicalGameplayBindingRef(exactBytes);
  }

  public byte[] bytes() {
    return bytes.clone();
  }

  /** Applies the canonical unsigned-big-endian SHA-256 partition function. */
  public int partitionId(int partitionCount) {
    if (partitionCount <= 0) {
      throw new IllegalArgumentException("partitionCount must be positive");
    }
    return partitionId(BigInteger.valueOf(partitionCount)).intValueExact();
  }

  /** Applies the canonical partition function without narrowing the configured count. */
  public BigInteger partitionId(BigInteger partitionCount) {
    Objects.requireNonNull(partitionCount, "partitionCount");
    if (partitionCount.signum() <= 0) {
      throw new IllegalArgumentException("partitionCount must be positive");
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      return new BigInteger(1, digest).mod(partitionCount);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private static byte[] encodeCanonicalIdentifier(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isEmpty() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(name + " must be a non-empty canonical identifier");
    }
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      if (Character.isISOControl(codePoint)) {
        throw new IllegalArgumentException(name + " must not contain control characters");
      }
      offset += Character.charCount(codePoint);
    }
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
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(name + " must be valid UTF-8", malformed);
    }
  }

  private static void appendSegment(ByteArrayOutputStream output, byte[] bytes) {
    byte[] length = Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII);
    output.writeBytes(length);
    output.write(':');
    output.writeBytes(bytes);
  }

  private static void validateIdentifierSegment(byte[] bytes, String name) {
    try {
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (!Arrays.equals(bytes, encodeCanonicalIdentifier(value, name))) {
        throw new IllegalArgumentException(name + " does not retain its exact UTF-8 bytes");
      }
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(name + " must be valid UTF-8", malformed);
    }
  }

  private static final class Cursor {
    private final byte[] bytes;
    private int offset;

    private Cursor(byte[] bytes) {
      this.bytes = bytes.clone();
    }

    private byte[] readSegment() {
      int start = offset;
      while (offset < bytes.length && bytes[offset] >= '0' && bytes[offset] <= '9') {
        offset++;
      }
      if (start == offset || offset >= bytes.length || bytes[offset] != ':') {
        throw new IllegalArgumentException("bindingRef segment has an invalid byte length");
      }
      if (offset - start > 1 && bytes[start] == '0') {
        throw new IllegalArgumentException("bindingRef segment length has a leading zero");
      }
      int length;
      try {
        length =
            Integer.parseInt(new String(bytes, start, offset - start, StandardCharsets.US_ASCII));
      } catch (NumberFormatException malformedLength) {
        throw new IllegalArgumentException("bindingRef segment length is invalid", malformedLength);
      }
      offset++;
      if (length < 0 || bytes.length - offset < length) {
        throw new IllegalArgumentException("bindingRef segment is truncated");
      }
      byte[] segment = Arrays.copyOfRange(bytes, offset, offset + length);
      offset += length;
      return segment;
    }

    private boolean atEnd() {
      return offset == bytes.length;
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CanonicalGameplayBindingRef ref && Arrays.equals(bytes, ref.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }
}
