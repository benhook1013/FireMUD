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

/** Canonical digest for the distinct Account-initiator qualification on fresh tenant creation. */
public final class FreshTenantCreatorDigest {
  private static final int SCHEMA_VERSION = 1;
  private static final String QUALIFICATION_DOMAIN = "game-design-tenant-creator-qualification/v1";
  private static final String DIGEST_PREFIX = "sha256:";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private FreshTenantCreatorDigest() {}

  /**
   * Digests every original creation-evidence field, then the initiating Account and Account-owned
   * authorization identity/digest, using decimal-byte-length-prefixed UTF-8 segments.
   */
  public static String evidenceDigest(
      int schemaVersion,
      FreshTenantCreationEvidence creationEvidence,
      UUID initiatingAccountId,
      UUID accountAuthorizationOperationId,
      String accountAuthorizationDigest) {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported fresh tenant creator evidence version");
    }
    Objects.requireNonNull(creationEvidence, "creationEvidence");
    requireNonNil(initiatingAccountId, "initiatingAccountId");
    requireNonNil(accountAuthorizationOperationId, "accountAuthorizationOperationId");
    if (!GameTenantCreationDigest.isDigest(accountAuthorizationDigest)) {
      throw new IllegalArgumentException(
          "accountAuthorizationDigest must be a lowercase SHA-256 digest");
    }

    return digest(
        QUALIFICATION_DOMAIN,
        Integer.toString(schemaVersion),
        Integer.toString(creationEvidence.schemaVersion()),
        creationEvidence.targetNamespace(),
        creationEvidence.creationRequestId().toString(),
        creationEvidence.operationId().toString(),
        creationEvidence.requestDigest(),
        creationEvidence.canonicalTenantId().toString(),
        Long.toString(creationEvidence.sourceGameRowId()),
        creationEvidence.sourceGameTenantKey(),
        creationEvidence.provenanceKind(),
        creationEvidence.evidenceDigest(),
        initiatingAccountId.toString(),
        accountAuthorizationOperationId.toString(),
        accountAuthorizationDigest);
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }

  private static String digest(String... segments) {
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
    Objects.requireNonNull(value, "digest segment");
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
      throw new IllegalArgumentException("Creator evidence must contain valid Unicode", exception);
    }
  }
}
