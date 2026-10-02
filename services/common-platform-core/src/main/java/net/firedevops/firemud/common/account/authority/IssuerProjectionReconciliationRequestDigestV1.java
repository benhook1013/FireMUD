package net.firedevops.firemud.common.account.authority;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Computes the versioned Account issuer projection reconciliation request digest. */
public final class IssuerProjectionReconciliationRequestDigestV1 {
  public static final int VERSION = 1;

  private static final String SCHEMA_VERSION = "issuer-projection-reconciliation-request/v1";
  private static final String OPERATION = "ISSUER_PROJECTION_SNAPSHOT_CAPTURE";
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private IssuerProjectionReconciliationRequestDigestV1() {}

  /** Returns the lowercase SHA-256 digest of the exact six-field UTF-8 byte-framed request. */
  public static String digest(
      String issuerId, String callerIdentity, String projectionKey, UUID requestId) {
    requireText(issuerId, "issuer ID", 512);
    requireText(callerIdentity, "caller workload identity", 512);
    requireText(projectionKey, "projection key", 2048);
    if (!projectionKey.equals(PROJECTION_KEY_PREFIX + issuerId)) {
      throw new IllegalArgumentException(
          "projection key must be the exact derived issuer projection key");
    }
    if (requestId == null || NIL_UUID.equals(requestId)) {
      throw new IllegalArgumentException("request ID must be a canonical non-nil UUID");
    }

    String[] fields = {
      SCHEMA_VERSION, OPERATION, issuerId, callerIdentity, projectionKey, requestId.toString()
    };
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
      framed.write(':');
      framed.writeBytes(bytes);
    }
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static void requireText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          field + " must contain 1 to " + maximumLength + " characters");
    }
  }
}
