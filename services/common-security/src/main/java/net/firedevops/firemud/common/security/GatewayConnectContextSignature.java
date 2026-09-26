package net.firedevops.firemud.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/**
 * Signs and verifies the Gateway-to-Game Session connect-context envelope.
 *
 * <p>This is a compact JWS envelope, not an Account JWT. The payload is opaque to this class:
 * callers supply and receive the exact UTF-8 payload bytes, while receiving services validate the
 * payload's semantic fields.
 */
public final class GatewayConnectContextSignature {
  public static final String ALGORITHM = "EdDSA";
  public static final String KEY_ALGORITHM = "Ed25519";
  public static final String TYPE = "firemud-gateway-connect-context+jws";

  /** Maximum payload size accepted by this bounded transport envelope. */
  public static final int MAX_PAYLOAD_BYTES = 16 * 1024;

  /** Maximum compact envelope size in UTF-8 bytes. */
  public static final int MAX_ENVELOPE_BYTES = 32 * 1024;

  /** Maximum key identifier length. */
  public static final int MAX_KID_LENGTH = 128;

  /** Gateway key rotation permits one current and one previous verification key. */
  public static final int MAX_VERIFICATION_KEYS = 2;

  private static final String HEADER_PREFIX = "{\"alg\":\"" + ALGORITHM + "\",\"kid\":\"";
  private static final String HEADER_SUFFIX = "\",\"typ\":\"" + TYPE + "\"}";

  private GatewayConnectContextSignature() {}

  /**
   * Signs the exact payload bytes and returns a canonical compact JWS envelope.
   *
   * @param payload exact payload bytes (normally the UTF-8 serialization produced by the caller)
   * @param kid bounded, non-blank Gateway signing-key identifier
   * @param signingKey Ed25519 private key from the Gateway signing-key namespace
   */
  public static String sign(byte[] payload, String kid, PrivateKey signingKey) {
    requirePayload(payload);
    requireKid(kid);
    requireKey(signingKey, "signing key", PrivateKey.class);

    String protectedHeader = canonicalProtectedHeader(kid);
    String protectedSegment = encodeBase64Url(protectedHeader.getBytes(StandardCharsets.US_ASCII));
    String payloadSegment = encodeBase64Url(payload);
    byte[] signingInput =
        (protectedSegment + "." + payloadSegment).getBytes(StandardCharsets.US_ASCII);
    byte[] signature = signInput(signingInput, signingKey);
    String envelope = protectedSegment + "." + payloadSegment + "." + encodeBase64Url(signature);
    requireEnvelopeSize(envelope);
    return envelope;
  }

  /**
   * Verifies a compact JWS envelope against an explicitly supplied Gateway key set.
   *
   * <p>The key set is deliberately bounded to one or two keys so rotation cannot silently become an
   * unbounded trust expansion. This method performs envelope/signature checks only; it does not
   * interpret issuer, audience, recipient, expiry, target, or any other payload field.
   */
  public static VerifiedContext verify(
      String envelope, Map<String, ? extends PublicKey> verificationKeys) {
    requireVerificationKeys(verificationKeys);
    requireEnvelope(envelope);

    String[] segments = splitCompactSerialization(envelope);
    byte[] protectedHeaderBytes = decodeCanonicalBase64Url(segments[0], "protected header");
    String protectedHeader = decodeAscii(protectedHeaderBytes, "protected header");
    String kid = parseCanonicalProtectedHeader(protectedHeader);

    PublicKey verificationKey = verificationKeys.get(kid);
    if (verificationKey == null) {
      throw invalid("unknown Gateway verification key");
    }
    requireKey(verificationKey, "verification key", PublicKey.class);

    byte[] payload = decodeCanonicalBase64Url(segments[1], "payload");
    requirePayload(payload);
    byte[] signature = decodeCanonicalBase64Url(segments[2], "signature");
    if (signature.length != 64) {
      throw invalid("invalid Ed25519 signature length");
    }

    byte[] signingInput = (segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII);
    if (!verifyInput(signingInput, signature, verificationKey)) {
      throw invalid("invalid Gateway context signature");
    }
    return new VerifiedContext(payload, kid);
  }

  private static String[] splitCompactSerialization(String envelope) {
    int firstDot = envelope.indexOf('.');
    int secondDot = firstDot < 0 ? -1 : envelope.indexOf('.', firstDot + 1);
    if (firstDot <= 0 || secondDot <= firstDot + 1 || secondDot == envelope.length() - 1) {
      throw invalid("malformed compact JWS envelope");
    }
    if (envelope.indexOf('.', secondDot + 1) >= 0) {
      throw invalid("malformed compact JWS envelope");
    }
    return new String[] {
      envelope.substring(0, firstDot),
      envelope.substring(firstDot + 1, secondDot),
      envelope.substring(secondDot + 1)
    };
  }

  private static String parseCanonicalProtectedHeader(String header) {
    if (!header.startsWith(HEADER_PREFIX) || !header.endsWith(HEADER_SUFFIX)) {
      throw invalid("unsupported or non-canonical protected header");
    }
    int kidStart = HEADER_PREFIX.length();
    int kidEnd = header.length() - HEADER_SUFFIX.length();
    if (kidEnd <= kidStart) {
      throw invalid("missing Gateway key identifier");
    }
    String kid = header.substring(kidStart, kidEnd);
    requireKid(kid);
    if (!header.equals(canonicalProtectedHeader(kid))) {
      throw invalid("unsupported or non-canonical protected header");
    }
    return kid;
  }

  private static String canonicalProtectedHeader(String kid) {
    return HEADER_PREFIX + kid + HEADER_SUFFIX;
  }

  private static String encodeBase64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] decodeCanonicalBase64Url(String value, String component) {
    if (value.isEmpty() || value.length() > MAX_ENVELOPE_BYTES) {
      throw invalid("invalid " + component + " encoding");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!isBase64UrlCharacter(character)) {
        throw invalid("invalid " + component + " encoding");
      }
    }
    final byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException ex) {
      throw invalid("invalid " + component + " encoding");
    }
    if (!value.equals(encodeBase64Url(decoded))) {
      throw invalid("non-canonical " + component + " encoding");
    }
    return decoded;
  }

  private static boolean isBase64UrlCharacter(char character) {
    return character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z'
        || character >= '0' && character <= '9'
        || character == '-'
        || character == '_';
  }

  private static String decodeAscii(byte[] value, String component) {
    for (byte character : value) {
      if (character < 0x21 || character > 0x7e) {
        throw invalid("invalid " + component + " encoding");
      }
    }
    return new String(value, StandardCharsets.US_ASCII);
  }

  private static byte[] signInput(byte[] signingInput, PrivateKey signingKey) {
    try {
      Signature signature = Signature.getInstance(KEY_ALGORITHM);
      signature.initSign(signingKey);
      signature.update(signingInput);
      return signature.sign();
    } catch (GeneralSecurityException ex) {
      throw invalid("Gateway Ed25519 signing failed");
    }
  }

  private static boolean verifyInput(
      byte[] signingInput, byte[] signatureBytes, PublicKey verificationKey) {
    try {
      Signature signature = Signature.getInstance(KEY_ALGORITHM);
      signature.initVerify(verificationKey);
      signature.update(signingInput);
      return signature.verify(signatureBytes);
    } catch (GeneralSecurityException ex) {
      throw invalid("Gateway Ed25519 verification failed");
    }
  }

  private static void requirePayload(byte[] payload) {
    if (payload == null) {
      throw new IllegalArgumentException("Gateway context payload is required");
    }
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("Gateway context payload exceeds the bounded limit");
    }
  }

  private static void requireEnvelope(String envelope) {
    if (envelope == null || envelope.isEmpty()) {
      throw invalid("Gateway context envelope is required");
    }
    if (envelope.length() > MAX_ENVELOPE_BYTES) {
      throw invalid("Gateway context envelope exceeds the bounded limit");
    }
    for (int index = 0; index < envelope.length(); index++) {
      if (envelope.charAt(index) > 0x7e || envelope.charAt(index) < 0x21) {
        throw invalid("Gateway context envelope is not ASCII compact JWS");
      }
    }
  }

  private static void requireEnvelopeSize(String envelope) {
    if (envelope.getBytes(StandardCharsets.US_ASCII).length > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("Gateway context envelope exceeds the bounded limit");
    }
  }

  private static void requireKid(String kid) {
    if (kid == null || kid.isEmpty() || kid.length() > MAX_KID_LENGTH) {
      throw new IllegalArgumentException("Gateway key identifier must be non-blank and bounded");
    }
    for (int index = 0; index < kid.length(); index++) {
      char character = kid.charAt(index);
      if (character < 0x21 || character > 0x7e || character == '"' || character == '\\') {
        throw new IllegalArgumentException(
            "Gateway key identifier contains an unsupported character");
      }
    }
  }

  private static void requireVerificationKeys(Map<String, ? extends PublicKey> verificationKeys) {
    if (verificationKeys == null || verificationKeys.isEmpty()) {
      throw invalid("Gateway verification keys are unavailable");
    }
    if (verificationKeys.size() > MAX_VERIFICATION_KEYS) {
      throw invalid("Gateway verification key set exceeds the bounded rotation window");
    }
    for (Map.Entry<String, ? extends PublicKey> entry : verificationKeys.entrySet()) {
      requireKid(entry.getKey());
      requireKey(entry.getValue(), "verification key", PublicKey.class);
    }
  }

  private static void requireKey(Object key, String description, Class<?> expectedType) {
    if (key == null || !expectedType.isInstance(key)) {
      throw new IllegalArgumentException(description + " is required");
    }
    String algorithm =
        key instanceof PrivateKey privateKey
            ? privateKey.getAlgorithm()
            : ((PublicKey) key).getAlgorithm();
    if (!isEd25519Algorithm(algorithm)) {
      throw new IllegalArgumentException(description + " must use Ed25519");
    }
  }

  private static boolean isEd25519Algorithm(String algorithm) {
    return KEY_ALGORITHM.equalsIgnoreCase(algorithm) || "EdDSA".equalsIgnoreCase(algorithm);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  /** The only data returned after successful signature verification. */
  public record VerifiedContext(byte[] payload, String kid) {
    public VerifiedContext {
      if (payload == null) {
        throw new IllegalArgumentException("verified payload is required");
      }
      payload = payload.clone();
      requireKid(kid);
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }
}
