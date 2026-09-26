package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import net.firedevops.firemud.common.security.GatewayConnectContextSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayConnectContextSignatureTest {
  private static final String CURRENT_KID = "gateway-2026-current";
  private static final String PREVIOUS_KID = "gateway-2026-previous";

  private KeyPair currentKey;
  private KeyPair previousKey;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    currentKey = generator.generateKeyPair();
    previousKey = generator.generateKeyPair();
  }

  @Test
  void signsAndVerifiesExactPayloadBytes() {
    byte[] payload = {0, 1, (byte) 0xff, 2, 3};

    String envelope =
        GatewayConnectContextSignature.sign(payload, CURRENT_KID, currentKey.getPrivate());
    GatewayConnectContextSignature.VerifiedContext verified =
        GatewayConnectContextSignature.verify(
            envelope, Map.of(CURRENT_KID, currentKey.getPublic()));

    assertArrayEquals(payload, verified.payload());
    assertEquals(CURRENT_KID, verified.kid());
  }

  @Test
  void acceptsCurrentAndPreviousKeysDuringRotation() {
    byte[] payload = "rotation-safe context".getBytes(StandardCharsets.UTF_8);
    String envelope =
        GatewayConnectContextSignature.sign(payload, PREVIOUS_KID, previousKey.getPrivate());

    GatewayConnectContextSignature.VerifiedContext verified =
        GatewayConnectContextSignature.verify(
            envelope,
            Map.of(
                CURRENT_KID, currentKey.getPublic(),
                PREVIOUS_KID, previousKey.getPublic()));

    assertArrayEquals(payload, verified.payload());
    assertEquals(PREVIOUS_KID, verified.kid());
  }

  @Test
  void rejectsAlteredPayloadAndSignature() {
    byte[] payload = "signed context".getBytes(StandardCharsets.UTF_8);
    String envelope =
        GatewayConnectContextSignature.sign(payload, CURRENT_KID, currentKey.getPrivate());
    String[] segments = envelope.split("\\.", -1);
    String alteredPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("altered".getBytes(StandardCharsets.UTF_8));
    String alteredSignature = flipLastCharacter(segments[2]);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.verify(
                segments[0] + "." + alteredPayload + "." + segments[2],
                Map.of(CURRENT_KID, currentKey.getPublic())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.verify(
                segments[0] + "." + segments[1] + "." + alteredSignature,
                Map.of(CURRENT_KID, currentKey.getPublic())));
  }

  @Test
  void rejectsMalformedOrNonCanonicalProtectedHeaders() {
    byte[] payload = "context".getBytes(StandardCharsets.UTF_8);
    String envelope =
        GatewayConnectContextSignature.sign(payload, CURRENT_KID, currentKey.getPrivate());
    String[] segments = envelope.split("\\.", -1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifyWithHeader(
                segments[1],
                segments[2],
                "{\"alg\":\"HS256\",\"kid\":\""
                    + CURRENT_KID
                    + "\",\"typ\":\""
                    + GatewayConnectContextSignature.TYPE
                    + "\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifyWithHeader(
                segments[1],
                segments[2],
                "{\"alg\":\"EdDSA\",\"kid\":\"" + CURRENT_KID + "\",\"typ\":\"wrong\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifyWithHeader(
                segments[1],
                segments[2],
                "{\"alg\":\"EdDSA\",\"kid\":\""
                    + CURRENT_KID
                    + "\",\"extra\":true,\"typ\":\""
                    + GatewayConnectContextSignature.TYPE
                    + "\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifyWithHeader(
                segments[1],
                segments[2],
                "{ \"alg\": \"EdDSA\", \"kid\": \""
                    + CURRENT_KID
                    + "\", \"typ\": \""
                    + GatewayConnectContextSignature.TYPE
                    + "\" }"));
  }

  @Test
  void rejectsMalformedBase64UnknownKidAndUnavailableOrOversizedKeySets() throws Exception {
    byte[] payload = "context".getBytes(StandardCharsets.UTF_8);
    String envelope =
        GatewayConnectContextSignature.sign(payload, CURRENT_KID, currentKey.getPrivate());
    String[] segments = envelope.split("\\.", -1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.verify(
                "!" + segments[0].substring(1) + "." + segments[1] + "." + segments[2],
                Map.of(CURRENT_KID, currentKey.getPublic())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.verify(
                envelope, Map.of("unknown", currentKey.getPublic())));
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayConnectContextSignature.verify(envelope, Map.of()));

    KeyPair thirdKey = keyPair();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.verify(
                envelope,
                Map.of(
                    CURRENT_KID,
                    currentKey.getPublic(),
                    PREVIOUS_KID,
                    previousKey.getPublic(),
                    "gateway-2026-third",
                    thirdKey.getPublic())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.sign(
                new byte[GatewayConnectContextSignature.MAX_PAYLOAD_BYTES + 1],
                CURRENT_KID,
                currentKey.getPrivate()));
  }

  @Test
  void rejectsInvalidKidsAndNonEd25519Keys() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayConnectContextSignature.sign(new byte[] {1}, " ", currentKey.getPrivate()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.sign(
                new byte[] {1}, "gateway-\\\"quoted", currentKey.getPrivate()));

    KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
    KeyPair rsaKey = rsaGenerator.generateKeyPair();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConnectContextSignature.sign(new byte[] {1}, CURRENT_KID, rsaKey.getPrivate()));
  }

  private void verifyWithHeader(String payloadSegment, String signatureSegment, String header) {
    String protectedSegment =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(header.getBytes(StandardCharsets.US_ASCII));
    GatewayConnectContextSignature.verify(
        protectedSegment + "." + payloadSegment + "." + signatureSegment,
        Map.of(CURRENT_KID, currentKey.getPublic()));
  }

  private static String flipLastCharacter(String value) {
    char last = value.charAt(value.length() - 1);
    return value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');
  }

  private static KeyPair keyPair() throws Exception {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }
}
