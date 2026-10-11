package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Account-owned HMAC identity for the exact bytes of an operator authorization reference. */
public final class AccountOperatorAuthorizationReferenceFingerprint {
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String DOMAIN = "FireMUD/authorizationReferenceFingerprint/v1";
  private static final String OUTPUT_PREFIX = "arfp/v1/";
  private static final int MAX_KEY_ID_BYTES = 64;
  private static final int MAX_KEY_BYTES = 4096;
  private static final int MAX_REFERENCE_BYTES = 4096;
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final String keyId;
  private final SecretKey key;

  /**
   * Creates a calculator with an explicitly supplied HMAC key and its bounded public identifier.
   *
   * <p>No key is generated, discovered, or loaded implicitly by this class.
   */
  public AccountOperatorAuthorizationReferenceFingerprint(String keyId, SecretKey key) {
    if (keyId == null || keyId.length() > MAX_KEY_ID_BYTES || !KEY_ID.matcher(keyId).matches()) {
      throw new IllegalArgumentException("Unsupported authorization-reference fingerprint key ID");
    }
    if (key == null || !HMAC_ALGORITHM.equalsIgnoreCase(key.getAlgorithm())) {
      throw new IllegalArgumentException("Unsupported authorization-reference fingerprint key");
    }

    byte[] encoded = key.getEncoded();
    if (encoded == null || encoded.length == 0 || encoded.length > MAX_KEY_BYTES) {
      throw new IllegalArgumentException("Unsupported authorization-reference fingerprint key");
    }

    byte[] keyCopy = encoded.clone();
    try {
      this.key = new SecretKeySpec(keyCopy, HMAC_ALGORITHM);
    } finally {
      Arrays.fill(keyCopy, (byte) 0);
    }
    this.keyId = keyId;
  }

  /** Returns the canonical fingerprint for the supplied exact opaque reference bytes. */
  public String fingerprint(ReferenceKind referenceKind, byte[] referenceBytes) {
    if (referenceKind == null) {
      throw new IllegalArgumentException("Unsupported operator authorization-reference kind");
    }
    if (referenceBytes == null
        || referenceBytes.length == 0
        || referenceBytes.length > MAX_REFERENCE_BYTES) {
      throw new IllegalArgumentException("Invalid operator authorization-reference bytes");
    }

    byte[] referenceCopy = referenceBytes.clone();
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(key);
      updateSegment(mac, DOMAIN.getBytes(StandardCharsets.UTF_8));
      updateSegment(mac, keyId.getBytes(StandardCharsets.UTF_8));
      updateSegment(mac, referenceKind.wireValue.getBytes(StandardCharsets.UTF_8));
      updateSegment(mac, referenceCopy);
      return OUTPUT_PREFIX + keyId + "/" + HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
    } finally {
      Arrays.fill(referenceCopy, (byte) 0);
    }
  }

  private static void updateSegment(Mac mac, byte[] value) {
    byte[] prefix = (Integer.toString(value.length) + ":").getBytes(StandardCharsets.US_ASCII);
    mac.update(prefix);
    mac.update(value);
  }

  @Override
  public String toString() {
    return "AccountOperatorAuthorizationReferenceFingerprint";
  }

  /** The only reference kinds accepted by ADR 0047. */
  public enum ReferenceKind {
    HUMAN_OPERATOR("human_operator"),
    AUTOMATION_OPERATOR("automation_operator");

    private final String wireValue;

    ReferenceKind(String wireValue) {
      this.wireValue = Objects.requireNonNull(wireValue);
    }
  }
}
