package net.firedevops.firemud.accountservice.client;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/** Exact Game Design owner-approved mapping evidence returned over the future Account-only read. */
public record OwnerApprovedAccountTenantAssociation(
    long legacyAccountTenantId,
    UUID canonicalTenantId,
    String sourceLegacyGameTenantId,
    long sourceGameRowId,
    String accountEvidenceDigest,
    Instant sourceCapturedAt,
    UUID operationId,
    String manifestDigest,
    String manifestSignature,
    String targetNamespace,
    String signerKeyId,
    String approvedBy,
    String approvalReference,
    String signedAt,
    int operationEntryCount,
    int manifestSchemaVersion) {
  private static final UUID NIL = new UUID(0L, 0L);

  public OwnerApprovedAccountTenantAssociation {
    if (legacyAccountTenantId <= 0
        || canonicalTenantId == null
        || NIL.equals(canonicalTenantId)
        || sourceLegacyGameTenantId == null
        || sourceLegacyGameTenantId.isBlank()
        || sourceLegacyGameTenantId.length() > 36
        || sourceGameRowId <= 0
        || !digest(accountEvidenceDigest)
        || sourceCapturedAt == null
        || !sourceCapturedAt.equals(sourceCapturedAt.truncatedTo(ChronoUnit.MICROS))
        || operationId == null
        || NIL.equals(operationId)
        || !digest(manifestDigest)
        || !signature(manifestSignature)
        || blankOrOversized(targetNamespace, 128)
        || blankOrOversized(signerKeyId, 128)
        || blankOrOversized(approvedBy, 256)
        || blankOrOversized(approvalReference, 512)
        || signedAt == null
        || signedAt.isBlank()
        || operationEntryCount <= 0
        || manifestSchemaVersion != 1) {
      throw new IllegalArgumentException("Game Design Account tenant evidence is incomplete");
    }
    try {
      Instant.parse(signedAt);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Game Design Account tenant signed_at is invalid", ex);
    }
  }

  private static boolean digest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private static boolean signature(String value) {
    if (value == null) {
      return false;
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      return decoded.length == 64 && Base64.getEncoder().encodeToString(decoded).equals(value);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static boolean blankOrOversized(String value, int maxLength) {
    return value == null
        || value.isBlank()
        || value.length() > maxLength
        || value.chars().anyMatch(Character::isISOControl);
  }
}
