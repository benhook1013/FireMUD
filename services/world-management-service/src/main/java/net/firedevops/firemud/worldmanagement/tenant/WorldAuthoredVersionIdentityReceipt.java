package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;

/**
 * Immutable World-local identity association for one fresh authored Version.
 *
 * <p>This retains source-qualified identity evidence and allocates a private World version key. It
 * does not materialize content, authorize a Draft write, or permit lifecycle preparation.
 */
public record WorldAuthoredVersionIdentityReceipt(
    int schemaVersion,
    UUID operationId,
    long localVersionKey,
    WorldAuthoredSourceIntakeReceipt sourceIntakeReceipt,
    AuthoredWorldVersionStateEvidence versionStateEvidence) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public WorldAuthoredVersionIdentityReceipt {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported World authored-Version identity version");
    }
    requireNonNil(operationId, "operationId");
    if (localVersionKey <= 0) {
      throw new IllegalArgumentException("localVersionKey must be positive");
    }
    Objects.requireNonNull(sourceIntakeReceipt, "sourceIntakeReceipt");
    Objects.requireNonNull(versionStateEvidence, "versionStateEvidence");
    versionStateEvidence.requireValid();

    var source = sourceIntakeReceipt.source();
    var request = versionStateEvidence.request();
    if (!"NEW_GAME_ROW".equals(source.provenanceKind())
        || !sourceIntakeReceipt.targetNamespace().equals(request.targetNamespace())
        || !sourceIntakeReceipt.canonicalTenantId().equals(request.canonicalTenantId())
        || !sourceIntakeReceipt.worldSlug().equals(request.worldSlug())
        || !sourceIntakeReceipt.sourceOperationId().equals(request.sourceOperationId())
        || !sourceIntakeReceipt
            .sourceEvidenceDigest()
            .equals(request.expectedSourceEvidenceDigest())
        || !source.equals(versionStateEvidence.sourceEvidence())) {
      throw new IllegalArgumentException(
          "World Version identity differs from its exact fresh source intake");
    }
    UUID readRequestId = request.readRequestId();
    if (readRequestId.equals(versionStateEvidence.canonicalVersionId())
        || readRequestId.equals(operationId)
        || readRequestId.equals(sourceIntakeReceipt.operationId())
        || readRequestId.equals(sourceIntakeReceipt.intakeRequestId())
        || readRequestId.equals(sourceIntakeReceipt.sourceOperationId())
        || readRequestId.equals(source.registrationRequestId())) {
      throw new IllegalArgumentException(
          "Version-state readRequestId must be separate from source and owner identities");
    }
    if (versionStateEvidence.versionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT
        && versionStateEvidence.versionState()
            != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
        && versionStateEvidence.versionState()
            != VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE) {
      throw new IllegalArgumentException(
          "World Version identity requires DRAFT, PUBLISHED, or ACTIVE owner evidence");
    }
  }

  public UUID canonicalVersionId() {
    return versionStateEvidence.canonicalVersionId();
  }

  public long gameDesignVersionId() {
    return versionStateEvidence.request().versionId();
  }

  public String targetNamespace() {
    return sourceIntakeReceipt.targetNamespace();
  }

  public UUID canonicalTenantId() {
    return sourceIntakeReceipt.canonicalTenantId();
  }

  public String worldSlug() {
    return sourceIntakeReceipt.worldSlug();
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
