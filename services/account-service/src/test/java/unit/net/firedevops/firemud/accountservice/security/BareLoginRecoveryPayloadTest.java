package unit.net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.BareLoginRecoveryPayload;
import org.junit.jupiter.api.Test;

class BareLoginRecoveryPayloadTest {
  private static final String GATEWAY_CONTEXT = "protected.payload.signature";
  private static final byte[] RESULT_BYTES = new byte[] {0, 1, (byte) 0xff, '\n', 42};

  @Test
  void roundTripsExactContextAndOpaqueResultBytes() {
    BareLoginRecoveryPayload original = BareLoginRecoveryPayload.of(GATEWAY_CONTEXT, RESULT_BYTES);
    byte[] encoded = original.encode();

    BareLoginRecoveryPayload recovered = BareLoginRecoveryPayload.decode(encoded);

    assertEquals(GATEWAY_CONTEXT, recovered.originalGatewayContext());
    assertArrayEquals(
        GATEWAY_CONTEXT.getBytes(StandardCharsets.UTF_8), recovered.originalGatewayContextUtf8());
    assertArrayEquals(RESULT_BYTES, recovered.originalResultBytes());
    assertEquals(
        16 + GATEWAY_CONTEXT.getBytes(StandardCharsets.UTF_8).length + RESULT_BYTES.length,
        encoded.length);
    assertFalse(recovered.toString().contains(GATEWAY_CONTEXT));
    assertFalse(recovered.toString().contains("signature"));
  }

  @Test
  void rejectsUnknownVersionTruncationTrailingBytesAndUnknownHeader() {
    byte[] valid = BareLoginRecoveryPayload.of(GATEWAY_CONTEXT, RESULT_BYTES).encode();

    byte[] unknownVersion = valid.clone();
    unknownVersion[7] = 2;
    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.decode(unknownVersion));

    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.decode(Arrays.copyOf(valid, valid.length - 1)));

    byte[] trailingByte = Arrays.copyOf(valid, valid.length + 1);
    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.decode(trailingByte));

    byte[] unknownHeader = valid.clone();
    unknownHeader[0] ^= 1;
    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.decode(unknownHeader));
  }

  @Test
  void rejectsNegativeAndHugeDeclaredContextAndResultLengths() {
    byte[] context = GATEWAY_CONTEXT.getBytes(StandardCharsets.UTF_8);
    byte[] valid = rawFrame(context, RESULT_BYTES);
    int resultLengthOffset = 12 + context.length;

    byte[] negativeContextLength = valid.clone();
    putInt(negativeContextLength, 8, -1);
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.decode(negativeContextLength));

    byte[] hugeContextLength = valid.clone();
    putInt(hugeContextLength, 8, Integer.MAX_VALUE);
    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.decode(hugeContextLength));

    byte[] negativeResultLength = valid.clone();
    putInt(negativeResultLength, resultLengthOffset, -1);
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.decode(negativeResultLength));

    byte[] hugeResultLength = valid.clone();
    putInt(hugeResultLength, resultLengthOffset, Integer.MAX_VALUE);
    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.decode(hugeResultLength));
  }

  @Test
  void rejectsContextOnlyFrameTruncatedAtResultLengthBoundary() {
    byte[] context = GATEWAY_CONTEXT.getBytes(StandardCharsets.UTF_8);
    byte[] contextOnly = contextOnlyFrame(context);

    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.decode(contextOnly));
  }

  @Test
  void rejectsEmptyInvalidUtf8AndNonCompactFields() {
    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.of("", RESULT_BYTES));
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.of(GATEWAY_CONTEXT, new byte[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.of(".payload.signature", RESULT_BYTES));
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.of("header..signature", RESULT_BYTES));
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.of("header.payload.", RESULT_BYTES));
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.of("header.payload.signature.extra", RESULT_BYTES));
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.of("header.päyload.signature", RESULT_BYTES));

    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.decode(rawFrame(new byte[0], RESULT_BYTES)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BareLoginRecoveryPayload.decode(
                rawFrame(GATEWAY_CONTEXT.getBytes(StandardCharsets.UTF_8), new byte[0])));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BareLoginRecoveryPayload.decode(
                rawFrame(new byte[] {(byte) 0xc3, '.', 'b', '.', 'c'}, RESULT_BYTES)));
  }

  @Test
  void rejectsFramesBeyondAccountEnvelopePlaintextBound() {
    byte[] oversizedResult = new byte[AccountEncryptedEnvelope.MAX_PLAINTEXT_LENGTH_BYTES];
    assertThrows(
        IllegalArgumentException.class,
        () -> BareLoginRecoveryPayload.of(GATEWAY_CONTEXT, oversizedResult));

    byte[] oversizedFrame = new byte[AccountEncryptedEnvelope.MAX_PLAINTEXT_LENGTH_BYTES + 1];
    assertThrows(
        IllegalArgumentException.class, () -> BareLoginRecoveryPayload.decode(oversizedFrame));
  }

  @Test
  void acceptsFrameAtExactAccountEnvelopePlaintextBound() {
    int maxFrameLength = AccountEncryptedEnvelope.MAX_PLAINTEXT_LENGTH_BYTES;
    int contextLength = maxFrameLength - 16 - 1;
    String context = "a".repeat(contextLength - 4) + ".b.c";
    byte[] result = new byte[] {(byte) 0xff};

    byte[] encoded = BareLoginRecoveryPayload.of(context, result).encode();

    assertEquals(maxFrameLength, encoded.length);
    BareLoginRecoveryPayload recovered = BareLoginRecoveryPayload.decode(encoded);
    assertEquals(context, recovered.originalGatewayContext());
    assertArrayEquals(result, recovered.originalResultBytes());
  }

  @Test
  void defensivelyCopiesInputsAndOutputs() {
    byte[] result = RESULT_BYTES.clone();
    BareLoginRecoveryPayload payload = BareLoginRecoveryPayload.of(GATEWAY_CONTEXT, result);
    Arrays.fill(result, (byte) 0);

    byte[] exposedContext = payload.originalGatewayContextUtf8();
    byte[] exposedResult = payload.originalResultBytes();
    byte[] exposedFrame = payload.encode();
    Arrays.fill(exposedContext, (byte) 0);
    Arrays.fill(exposedResult, (byte) 0);
    Arrays.fill(exposedFrame, (byte) 0);

    assertArrayEquals(RESULT_BYTES, payload.originalResultBytes());
    assertEquals(GATEWAY_CONTEXT, payload.originalGatewayContext());
    assertArrayEquals(
        RESULT_BYTES, BareLoginRecoveryPayload.decode(payload.encode()).originalResultBytes());
  }

  private static byte[] rawFrame(byte[] context, byte[] result) {
    ByteBuffer frame =
        ByteBuffer.allocate(16 + context.length + result.length).order(ByteOrder.BIG_ENDIAN);
    frame.put(new byte[] {'F', 'M', 'B', 'R'});
    frame.putInt(1);
    frame.putInt(context.length);
    frame.put(context);
    frame.putInt(result.length);
    frame.put(result);
    return frame.array();
  }

  private static byte[] contextOnlyFrame(byte[] context) {
    ByteBuffer frame = ByteBuffer.allocate(12 + context.length).order(ByteOrder.BIG_ENDIAN);
    frame.put(new byte[] {'F', 'M', 'B', 'R'});
    frame.putInt(1);
    frame.putInt(context.length);
    frame.put(context);
    return frame.array();
  }

  private static void putInt(byte[] frame, int offset, int value) {
    ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
  }
}
