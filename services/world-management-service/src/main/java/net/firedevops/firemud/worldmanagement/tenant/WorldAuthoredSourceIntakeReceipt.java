package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;

/** Complete immutable World-owned receipt for one fresh Game Design source intake. */
public record WorldAuthoredSourceIntakeReceipt(
    int schemaVersion,
    String targetNamespace,
    UUID intakeRequestId,
    UUID operationId,
    UUID canonicalTenantId,
    String worldSlug,
    UUID sourceOperationId,
    String sourceEvidenceDigest,
    String requestDigest,
    String receiptDigest,
    long localTenantKey,
    AuthoredWorldSourceEvidence source) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public WorldAuthoredSourceIntakeReceipt {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported World authored-source receipt version");
    }
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    requireNonNil(intakeRequestId, "intakeRequestId");
    requireNonNil(operationId, "operationId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    requireNonNil(sourceOperationId, "sourceOperationId");
    Objects.requireNonNull(sourceEvidenceDigest, "sourceEvidenceDigest");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(receiptDigest, "receiptDigest");
    Objects.requireNonNull(source, "source");
    if (localTenantKey <= 0) {
      throw new IllegalArgumentException("localTenantKey must be positive");
    }
    WorldAuthoredSourceIntakeDigest.requireFreshSource(targetNamespace, source);
    if (!targetNamespace.equals(source.targetNamespace())
        || !canonicalTenantId.equals(source.canonicalTenantId())
        || !worldSlug.equals(source.worldSlug())
        || !sourceOperationId.equals(source.operationId())
        || !sourceEvidenceDigest.equals(source.evidenceDigest())
        || !requestDigest.equals(
            WorldAuthoredSourceIntakeDigest.requestDigest(targetNamespace, intakeRequestId, source))
        || !receiptDigest.equals(
            WorldAuthoredSourceIntakeDigest.receiptDigest(
                targetNamespace, operationId, requestDigest, source, localTenantKey))) {
      throw new IllegalArgumentException("World authored-source receipt fields or digests differ");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
