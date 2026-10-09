package net.firedevops.firemud.common.authoring;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;

/**
 * Public committed association evidence; private World numeric identities never cross this
 * boundary.
 */
public final class WorldAuthoredVersionIdentityEvidence {
  private WorldAuthoredVersionIdentityEvidence() {}

  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      UUID expectedCanonicalVersionId,
      long gameDesignVersionId,
      UUID readRequestId) {
    public Request {
      new AuthoredWorldVersionStateEvidence.Request(
          schemaVersion,
          targetNamespace,
          readRequestId,
          canonicalTenantId,
          worldSlug,
          sourceOperationId,
          sourceEvidenceDigest,
          gameDesignVersionId);
      requireNonNil(expectedCanonicalVersionId);
      if (expectedCanonicalVersionId.equals(readRequestId)) {
        throw new IllegalArgumentException(
            "Version read correlation must differ from Version identity");
      }
    }

    public AuthoredWorldVersionStateEvidence.Request versionReadRequest() {
      return new AuthoredWorldVersionStateEvidence.Request(
          schemaVersion,
          targetNamespace,
          readRequestId,
          canonicalTenantId,
          worldSlug,
          sourceOperationId,
          sourceEvidenceDigest,
          gameDesignVersionId);
    }
  }

  public record Result(
      Request request,
      UUID operationId,
      WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt sourceIntakeReceipt,
      AuthoredWorldVersionStateEvidence versionStateEvidence) {
    public Result {
      Objects.requireNonNull(request, "request");
      requireNonNil(operationId);
      Objects.requireNonNull(sourceIntakeReceipt, "sourceIntakeReceipt");
      Objects.requireNonNull(versionStateEvidence, "versionStateEvidence");
      versionStateEvidence.requireValid();
      var original = versionStateEvidence.request();
      var source = versionStateEvidence.sourceEvidence();
      if (sourceIntakeReceipt.schemaVersion() != request.schemaVersion()
          || !sourceIntakeReceipt.targetNamespace().equals(request.targetNamespace())
          || !sourceIntakeReceipt.canonicalTenantId().equals(request.canonicalTenantId())
          || !sourceIntakeReceipt.worldSlug().equals(request.worldSlug())
          || !sourceIntakeReceipt.sourceOperationId().equals(request.sourceOperationId())
          || !sourceIntakeReceipt.sourceEvidenceDigest().equals(request.sourceEvidenceDigest())
          || original.schemaVersion() != request.schemaVersion()
          || !original.targetNamespace().equals(request.targetNamespace())
          || !original.canonicalTenantId().equals(request.canonicalTenantId())
          || !original.worldSlug().equals(request.worldSlug())
          || !original.sourceOperationId().equals(request.sourceOperationId())
          || !original.expectedSourceEvidenceDigest().equals(request.sourceEvidenceDigest())
          || original.versionId() != request.gameDesignVersionId()
          || !versionStateEvidence.canonicalVersionId().equals(request.expectedCanonicalVersionId())
          || !"NEW_GAME_ROW".equals(source.provenanceKind())) {
        throw new IllegalArgumentException(
            "Association must retain the exact fresh source and Version");
      }
      var state = versionStateEvidence.versionState();
      if (state != VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT
          && state != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
          && state != VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE) {
        throw new IllegalArgumentException(
            "Association requires eligible original Version evidence");
      }
      requireReadCorrelation(
          original.readRequestId(),
          request,
          operationId,
          sourceIntakeReceipt,
          source.registrationRequestId());
      requireReadCorrelation(
          request.readRequestId(),
          request,
          null,
          sourceIntakeReceipt,
          source.registrationRequestId());
    }
  }

  private static void requireReadCorrelation(
      UUID readId,
      Request request,
      UUID operationId,
      WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt intake,
      UUID registrationId) {
    if (readId.equals(request.expectedCanonicalVersionId())
        || readId.equals(operationId)
        || readId.equals(intake.operationId())
        || readId.equals(intake.intakeRequestId())
        || readId.equals(intake.sourceOperationId())
        || readId.equals(registrationId)) {
      throw new IllegalArgumentException(
          "Version read correlation overlaps owner/source identities");
    }
  }

  static UUID parseUuid(String text) {
    UUID value = UUID.fromString(text);
    requireNonNil(value);
    if (!value.toString().equals(text)) {
      throw new IllegalArgumentException("Canonical UUID required");
    }
    return value;
  }

  private static void requireNonNil(UUID value) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Non-nil UUID required");
    }
  }
}
