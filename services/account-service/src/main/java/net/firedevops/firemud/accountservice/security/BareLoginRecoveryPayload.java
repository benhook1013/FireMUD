package net.firedevops.firemud.accountservice.security;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded storage framing for the exact original bare-LOGIN Gateway assertion and result bytes.
 *
 * <p>This payload is intended only for encryption with {@link
 * AccountEnvelopePurpose#BARE_LOGIN_RESPONSE} and the matching durable operation binding. It does
 * not verify either signature, establish current authorization, or determine recovery eligibility.
 */
public final class BareLoginRecoveryPayload {
  private static final byte[] MAGIC = new byte[] {'F', 'M', 'B', 'R'};
  private static final int VERSION = 1;
  private static final int HEADER_BYTES = Integer.BYTES * 4;

  private final byte[] originalGatewayContextUtf8;
  private final byte[] originalResultBytes;

  private BareLoginRecoveryPayload(byte[] originalGatewayContextUtf8, byte[] originalResultBytes) {
    this.originalGatewayContextUtf8 =
        Objects.requireNonNull(originalGatewayContextUtf8, "original Gateway context is required")
            .clone();
    this.originalResultBytes =
        Objects.requireNonNull(originalResultBytes, "original result is required").clone();
    validateContext(this.originalGatewayContextUtf8);
    validateResult(this.originalResultBytes);
    requireFrameWithinLimit(
        this.originalGatewayContextUtf8.length, this.originalResultBytes.length);
  }

  /** Creates a frame from the exact compact Gateway envelope and exact opaque result bytes. */
  public static BareLoginRecoveryPayload of(
      String exactOriginalGatewayContext, byte[] exactOriginalResultBytes) {
    Objects.requireNonNull(exactOriginalGatewayContext, "original Gateway context is required");
    if (exactOriginalGatewayContext.isEmpty()
        || exactOriginalGatewayContext.chars().anyMatch(character -> character > 0x7f)) {
      throw invalid("Original Gateway context must be non-empty ASCII compact data");
    }
    return new BareLoginRecoveryPayload(
        exactOriginalGatewayContext.getBytes(StandardCharsets.UTF_8), exactOriginalResultBytes);
  }

  /** Decodes one complete version-1 frame, rejecting truncation and trailing data. */
  public static BareLoginRecoveryPayload decode(byte[] encodedFrame) {
    Objects.requireNonNull(encodedFrame, "encoded frame is required");
    if (encodedFrame.length < HEADER_BYTES || encodedFrame.length > maxFrameBytes()) {
      throw invalid("Bare LOGIN recovery payload length is invalid");
    }

    ByteBuffer input = ByteBuffer.wrap(encodedFrame).order(ByteOrder.BIG_ENDIAN);
    byte[] magic = new byte[MAGIC.length];
    input.get(magic);
    if (!Arrays.equals(MAGIC, magic)) {
      throw invalid("Bare LOGIN recovery payload header is invalid");
    }
    if (input.getInt() != VERSION) {
      throw invalid("Bare LOGIN recovery payload version is unsupported");
    }

    int contextLength = input.getInt();
    if (contextLength <= 0 || contextLength > input.remaining() - Integer.BYTES) {
      throw invalid("Bare LOGIN recovery payload context length is invalid");
    }
    byte[] context = new byte[contextLength];
    input.get(context);

    int resultLength = input.getInt();
    if (resultLength <= 0 || resultLength != input.remaining()) {
      throw invalid("Bare LOGIN recovery payload result length is invalid");
    }
    byte[] result = new byte[resultLength];
    input.get(result);
    return new BareLoginRecoveryPayload(context, result);
  }

  /** Returns a newly encoded, versioned frame suitable for Account envelope encryption. */
  public byte[] encode() {
    int frameLength =
        Math.addExact(
            HEADER_BYTES,
            Math.addExact(originalGatewayContextUtf8.length, originalResultBytes.length));
    requireFrameWithinLimit(originalGatewayContextUtf8.length, originalResultBytes.length);
    ByteBuffer output = ByteBuffer.allocate(frameLength).order(ByteOrder.BIG_ENDIAN);
    output.put(MAGIC);
    output.putInt(VERSION);
    output.putInt(originalGatewayContextUtf8.length);
    output.put(originalGatewayContextUtf8);
    output.putInt(originalResultBytes.length);
    output.put(originalResultBytes);
    return output.array();
  }

  /** Returns the exact original ASCII compact envelope as UTF-8 bytes. */
  public byte[] originalGatewayContextUtf8() {
    return originalGatewayContextUtf8.clone();
  }

  /** Returns the exact compact envelope text, without parsing or verifying its JWS. */
  public String originalGatewayContext() {
    return new String(originalGatewayContextUtf8, StandardCharsets.UTF_8);
  }

  /** Returns the exact opaque result bytes without text decoding or normalization. */
  public byte[] originalResultBytes() {
    return originalResultBytes.clone();
  }

  @Override
  public String toString() {
    return "BareLoginRecoveryPayload[version="
        + VERSION
        + ", gatewayContextBytes="
        + originalGatewayContextUtf8.length
        + ", resultBytes="
        + originalResultBytes.length
        + ", contents=<redacted>]";
  }

  private static void validateContext(byte[] context) {
    final String decoded;
    try {
      decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(context))
              .toString();
    } catch (CharacterCodingException exception) {
      throw invalid("Original Gateway context is not valid UTF-8");
    }
    if (decoded.chars().anyMatch(character -> character > 0x7f)) {
      throw invalid("Original Gateway context must be ASCII compact data");
    }
    int firstDot = decoded.indexOf('.');
    int secondDot = firstDot < 0 ? -1 : decoded.indexOf('.', firstDot + 1);
    if (firstDot <= 0
        || secondDot <= firstDot + 1
        || secondDot == decoded.length() - 1
        || decoded.indexOf('.', secondDot + 1) >= 0) {
      throw invalid("Original Gateway context must contain three non-empty compact segments");
    }
  }

  private static void validateResult(byte[] result) {
    if (result.length == 0) {
      throw invalid("Original bare LOGIN result must not be empty");
    }
  }

  private static void requireFrameWithinLimit(int contextLength, int resultLength) {
    long frameLength = (long) HEADER_BYTES + contextLength + resultLength;
    if (contextLength <= 0
        || resultLength <= 0
        || frameLength > AccountEncryptedEnvelope.MAX_PLAINTEXT_LENGTH_BYTES) {
      throw invalid("Bare LOGIN recovery payload exceeds its storage bound");
    }
  }

  private static int maxFrameBytes() {
    return AccountEncryptedEnvelope.MAX_PLAINTEXT_LENGTH_BYTES;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
