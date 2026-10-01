package net.firedevops.firemud.common.tenant;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Immutable, length-framed evidence for an audited retained Game Session tenant association. */
public record GameSessionTenantAssociationEvidence(
    int schemaVersion,
    UUID operationId,
    String targetNamespace,
    String signerKeyId,
    String approvedBy,
    String approvalReference,
    String signedAt,
    String legacyGameSessionTenantId,
    UUID canonicalTenantId,
    String sourceGameRowId,
    String sourceGameTenantKey,
    String provenanceKind,
    String gameSessionEvidenceDigest) {
  private static final String DOMAIN = "game-design/game-session-retained-tenant-association/v1";
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public GameSessionTenantAssociationEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported Game Session tenant association evidence schema version");
    }
    requireNonNil(operationId, "operationId");
    requireNamespace(targetNamespace);
    requireLabel(signerKeyId, 128, "signerKeyId");
    requireLabel(approvedBy, 256, "approvedBy");
    requireLabel(approvalReference, 512, "approvalReference");
    requireCanonicalInstant(signedAt);
    requirePositiveBigintText(legacyGameSessionTenantId, "legacyGameSessionTenantId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requirePositiveBigintText(sourceGameRowId, "sourceGameRowId");
    requireSourceGameTenantKey(sourceGameTenantKey);
    if (!"NEW_GAME_ROW".equals(provenanceKind) && !"RETAINED_GAME_V30".equals(provenanceKind)) {
      throw new IllegalArgumentException("Game Design tenant provenance kind is not recognized");
    }
    if (!GameTenantCreationDigest.isDigest(gameSessionEvidenceDigest)) {
      throw new IllegalArgumentException(
          "gameSessionEvidenceDigest must be a lowercase SHA-256 digest");
    }
  }

  /** Returns the exact shared bytes signed by Game Design and hashed by every consumer. */
  public byte[] preimage() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeSegment(output, DOMAIN);
    writeSegment(output, Integer.toString(schemaVersion));
    writeSegment(output, operationId.toString());
    writeSegment(output, targetNamespace);
    writeSegment(output, signerKeyId);
    writeSegment(output, approvedBy);
    writeSegment(output, approvalReference);
    writeSegment(output, signedAt);
    writeSegment(output, legacyGameSessionTenantId);
    writeSegment(output, canonicalTenantId.toString());
    writeSegment(output, sourceGameRowId);
    writeSegment(output, sourceGameTenantKey);
    writeSegment(output, provenanceKind);
    writeSegment(output, gameSessionEvidenceDigest);
    return output.toByteArray();
  }

  /** SHA-256 of {@link #preimage()}, excluding the signature and this derived digest. */
  public String manifestDigest() {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(preimage());
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Returns the retained Game Session tenant key as its exact positive PostgreSQL BIGINT value. */
  public long legacyGameSessionTenantIdValue() {
    return Long.parseLong(legacyGameSessionTenantId);
  }

  /** Returns the Game Design source row key as its exact positive PostgreSQL BIGINT value. */
  public long sourceGameRowIdValue() {
    return Long.parseLong(sourceGameRowId);
  }

  private static void writeSegment(ByteArrayOutputStream output, String value) {
    int byteLength = GameTenantCreationDigest.utf8ByteLength(value);
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length != byteLength) {
      throw new IllegalStateException("UTF-8 segment length changed after validation");
    }
    output.writeBytes(Integer.toString(byteLength).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(encoded);
  }

  private static void requireNamespace(String value) {
    Objects.requireNonNull(value, "targetNamespace");
    if (!GrpcPeerIdentity.isValidNamespace(value)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    GameTenantCreationDigest.utf8ByteLength(value);
  }

  private static void requireLabel(String value, int maxUtf8Bytes, String label) {
    Objects.requireNonNull(value, label);
    int byteLength = GameTenantCreationDigest.utf8ByteLength(value);
    if (value.isBlank()
        || byteLength > maxUtf8Bytes
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + " is outside the owner evidence bounds");
    }
  }

  private static void requireCanonicalInstant(String value) {
    Objects.requireNonNull(value, "signedAt");
    try {
      if (!Instant.parse(value).toString().equals(value)) {
        throw new IllegalArgumentException("signedAt must be canonical UTC Instant text");
      }
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("signedAt must be canonical UTC Instant text", exception);
    }
  }

  private static void requirePositiveBigintText(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.length() > 19 || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be canonical positive BIGINT text");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(label + " must be canonical positive BIGINT text");
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          label + " must fit a positive PostgreSQL BIGINT", exception);
    }
  }

  private static void requireSourceGameTenantKey(String value) {
    Objects.requireNonNull(value, "sourceGameTenantKey");
    GameTenantCreationDigest.utf8ByteLength(value);
    if (value.isBlank() || value.length() > 72 || value.codePointCount(0, value.length()) > 36) {
      throw new IllegalArgumentException("sourceGameTenantKey is outside the owner key bounds");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
