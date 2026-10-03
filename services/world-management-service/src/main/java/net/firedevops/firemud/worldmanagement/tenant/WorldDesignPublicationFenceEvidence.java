package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;

/** Exact caller binding and owner-produced checkpoint for one World publication-freeze attempt. */
public record WorldDesignPublicationFenceEvidence(
    String targetNamespace,
    UUID canonicalTenantId,
    long versionId,
    UUID intakeRequestId,
    UUID intakeOperationId,
    UUID sourceOperationId,
    String sourceEvidenceDigest,
    String publicationRequestId,
    String requestDigest,
    long versionStateEpoch,
    String publishWorkflowId) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SOURCE_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern REQUEST_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern CONTENT_DIGEST = Pattern.compile("[0-9a-f]{64}");

  // requestDigest is retained as the supplied full publication-request digest. The existing
  // PublicationDigestRequestBinding is used only for workflow identity derivation here; its
  // publicationDigestRequest/v1 read-binding digest is not the full freeze-request digest.

  public WorldDesignPublicationFenceEvidence {
    new OwnerBinding(
        targetNamespace,
        canonicalTenantId,
        versionId,
        intakeRequestId,
        intakeOperationId,
        sourceOperationId,
        sourceEvidenceDigest);
    requirePositive(versionStateEpoch, "versionStateEpoch");
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(
            canonicalTenantId.toString(), Long.toString(versionId), publicationRequestId);
    if (requestDigest == null || !REQUEST_DIGEST.matcher(requestDigest).matches()) {
      throw new IllegalArgumentException("requestDigest must be 64 lowercase hexadecimal digits");
    }
    if (!binding.derivedWorkflowIdentity().equals(publishWorkflowId)) {
      throw new IllegalArgumentException(
          "publishWorkflowId does not match the canonical full-version workflow identity");
    }
  }

  /** The common owner scope and complete source binding used by ordinary writers and freezes. */
  public OwnerBinding ownerBinding() {
    return new OwnerBinding(
        targetNamespace,
        canonicalTenantId,
        versionId,
        intakeRequestId,
        intakeOperationId,
        sourceOperationId,
        sourceEvidenceDigest);
  }

  /**
   * Scope/source identity needed to serialize an ordinary OPEN writer. It carries no publication
   * request, digest, epoch, workflow, fence, or checkpoint.
   */
  public record OwnerBinding(
      String targetNamespace,
      UUID canonicalTenantId,
      long versionId,
      UUID intakeRequestId,
      UUID intakeOperationId,
      UUID sourceOperationId,
      String sourceEvidenceDigest) {
    public OwnerBinding {
      if (targetNamespace == null || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
      }
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requirePositive(versionId, "versionId");
      requireNonNil(intakeRequestId, "intakeRequestId");
      requireNonNil(intakeOperationId, "intakeOperationId");
      requireNonNil(sourceOperationId, "sourceOperationId");
      if (sourceEvidenceDigest == null || !SOURCE_DIGEST.matcher(sourceEvidenceDigest).matches()) {
        throw new IllegalArgumentException(
            "sourceEvidenceDigest must be a canonical prefixed SHA-256 digest");
      }
    }
  }

  /**
   * Returns the full-version request identity binding used to derive the canonical workflow ID. Its
   * separate publicationDigestRequest/v1 digest is not this record's supplied requestDigest.
   */
  public PublicationDigestRequestBinding publicationBinding() {
    return PublicationDigestRequestBinding.full(
        canonicalTenantId.toString(), Long.toString(versionId), publicationRequestId);
  }

  /** Owner-local complete digest checkpoint captured only after the shared version lock is held. */
  public record Checkpoint(String appliedCommitId, String contentDigest, int digestSchemaVersion) {
    public Checkpoint {
      requireText(appliedCommitId, "appliedCommitId");
      if (appliedCommitId.isBlank()) {
        throw new IllegalArgumentException("appliedCommitId must not be blank");
      }
      if (!CONTENT_DIGEST
          .matcher(Objects.requireNonNull(contentDigest, "contentDigest"))
          .matches()) {
        throw new IllegalArgumentException("contentDigest must be 64 lowercase hexadecimal digits");
      }
      if (digestSchemaVersion <= 0) {
        throw new IllegalArgumentException("digestSchemaVersion must be positive");
      }
    }

    private static void requireText(String value, String label) {
      if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 256) {
        throw new IllegalArgumentException(
            label + " must be non-empty and at most 256 UTF-8 bytes");
      }
      for (int index = 0; index < value.length(); index++) {
        char current = value.charAt(index);
        if (Character.isHighSurrogate(current)) {
          if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
            throw new IllegalArgumentException(label + " contains malformed Unicode");
          }
          index++;
        } else if (Character.isLowSurrogate(current)) {
          throw new IllegalArgumentException(label + " contains malformed Unicode");
        }
      }
    }
  }

  /** Stored freeze result. The UUID is opaque and may only be compared for equality. */
  public record FrozenAttempt(
      WorldDesignPublicationFenceEvidence request, UUID publicationFence, Checkpoint checkpoint) {
    public FrozenAttempt {
      Objects.requireNonNull(request, "request");
      requireNonNil(publicationFence, "publicationFence");
      Objects.requireNonNull(checkpoint, "checkpoint");
    }
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0L) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
