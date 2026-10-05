package net.firedevops.firemud.worldmanagement.tenant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, owner-private capture of the six authored World template families. */
public record WorldAuthoredGraphSnapshot(
    UUID snapshotId,
    String targetNamespace,
    UUID canonicalTenantId,
    UUID canonicalVersionId,
    UUID versionIdentityOperationId,
    String worldSlug,
    long gameDesignVersionId,
    long localVersionKey,
    long localTenantKey,
    short ownerBindingSchemaVersion,
    UUID intakeOperationId,
    UUID intakeRequestId,
    String intakeRequestDigest,
    UUID sourceOperationId,
    String sourceEvidenceDigest,
    String intakeReceiptDigest,
    UUID publicationFence,
    String publicationRequestId,
    String requestDigest,
    long versionStateEpoch,
    String publishWorkflowId,
    String appliedCommitId,
    String contentDigest,
    int digestSchemaVersion,
    String captureRequestDigest,
    String suppliedOwnedAffectedTuplesJson,
    String ownerRevisionEvidenceJson,
    byte[] graphBytes,
    String graphSha256,
    OwnerCommitProofStatus ownerCommitProofStatus) {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public WorldAuthoredGraphSnapshot {
    requireNonNil(snapshotId, "snapshotId");
    requireText(targetNamespace, "targetNamespace");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    requireNonNil(versionIdentityOperationId, "versionIdentityOperationId");
    requireText(worldSlug, "worldSlug");
    requirePositive(gameDesignVersionId, "gameDesignVersionId");
    requirePositive(localVersionKey, "localVersionKey");
    requirePositive(localTenantKey, "localTenantKey");
    if (ownerBindingSchemaVersion != 1) {
      throw new IllegalArgumentException(
          "World snapshot requires exact V27 owner binding schema 1");
    }
    requireNonNil(intakeOperationId, "intakeOperationId");
    requireNonNil(intakeRequestId, "intakeRequestId");
    requirePrefixedSha256(intakeRequestDigest, "intakeRequestDigest");
    requireNonNil(sourceOperationId, "sourceOperationId");
    requirePrefixedSha256(sourceEvidenceDigest, "sourceEvidenceDigest");
    requirePrefixedSha256(intakeReceiptDigest, "intakeReceiptDigest");
    requireNonNil(publicationFence, "publicationFence");
    requireText(publicationRequestId, "publicationRequestId");
    requireSha256(requestDigest, "requestDigest");
    requirePositive(versionStateEpoch, "versionStateEpoch");
    requireText(publishWorkflowId, "publishWorkflowId");
    requireText(appliedCommitId, "appliedCommitId");
    requireSha256(contentDigest, "contentDigest");
    if (digestSchemaVersion != 2 && digestSchemaVersion != 3) {
      throw new IllegalArgumentException(
          "World graph snapshot requires retained schema 2 or current schema 3");
    }
    requireSha256(captureRequestDigest, "captureRequestDigest");
    requireText(suppliedOwnedAffectedTuplesJson, "suppliedOwnedAffectedTuplesJson");
    requireText(ownerRevisionEvidenceJson, "ownerRevisionEvidenceJson");
    graphBytes = Objects.requireNonNull(graphBytes, "graphBytes").clone();
    if (graphBytes.length == 0) {
      throw new IllegalArgumentException("graphBytes must contain a complete graph representation");
    }
    requireSha256(graphSha256, "graphSha256");
    Objects.requireNonNull(ownerCommitProofStatus, "ownerCommitProofStatus");
    if (ownerCommitProofStatus != OwnerCommitProofStatus.CAPTURED_UNVERIFIED) {
      throw new IllegalArgumentException(
          "This component cannot claim complete owner commit application proof");
    }
  }

  @Override
  public byte[] graphBytes() {
    return graphBytes.clone();
  }

  /** Proof state is deliberately non-eligible until the complete World commit fence is wired. */
  public enum OwnerCommitProofStatus {
    CAPTURED_UNVERIFIED
  }

  /** One supplied World-owned affected tuple, retained as input evidence without validating it. */
  public record OwnedAffectedTuple(
      String owner,
      String aggregateType,
      String aggregateId,
      String scopeType,
      String scopeId,
      String expectedEpoch) {
    public OwnedAffectedTuple {
      if (!"WORLD_MANAGEMENT".equals(owner)) {
        throw new IllegalArgumentException("World snapshot tuples must name WORLD_MANAGEMENT");
      }
      requireText(aggregateType, "aggregateType");
      requireText(aggregateId, "aggregateId");
      Objects.requireNonNull(scopeType, "scopeType");
      Objects.requireNonNull(scopeId, "scopeId");
      if (scopeType.isEmpty() != scopeId.isEmpty()) {
        throw new IllegalArgumentException(
            "scopeType and scopeId must be both present or both absent");
      }
      if (expectedEpoch == null
          || !expectedEpoch.matches("0|[1-9][0-9]*")
          || new java.math.BigInteger(expectedEpoch)
                  .compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))
              > 0) {
        throw new IllegalArgumentException("expectedEpoch must be canonical non-negative decimal");
      }
    }
  }

  /** Exact external binding. All private World keys are resolved from immutable owner records. */
  public record CaptureRequest(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      UUID intakeRequestId,
      UUID publicationFence,
      String publicationRequestId,
      String requestDigest,
      long versionStateEpoch,
      String publishWorkflowId,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion,
      List<OwnedAffectedTuple> suppliedOwnedAffectedTuples) {
    public CaptureRequest {
      requireText(targetNamespace, "targetNamespace");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requireNonNil(intakeRequestId, "intakeRequestId");
      requireNonNil(publicationFence, "publicationFence");
      requireText(publicationRequestId, "publicationRequestId");
      requireSha256(requestDigest, "requestDigest");
      requirePositive(versionStateEpoch, "versionStateEpoch");
      requireText(publishWorkflowId, "publishWorkflowId");
      requireText(appliedCommitId, "appliedCommitId");
      requireSha256(contentDigest, "contentDigest");
      if (digestSchemaVersion != 2 && digestSchemaVersion != 3) {
        throw new IllegalArgumentException(
            "World graph snapshot requires retained schema 2 or current schema 3");
      }
      Objects.requireNonNull(suppliedOwnedAffectedTuples, "suppliedOwnedAffectedTuples");
      List<OwnedAffectedTuple> ordered = new ArrayList<>(suppliedOwnedAffectedTuples);
      ordered.sort(
          Comparator.comparing(OwnedAffectedTuple::owner)
              .thenComparing(OwnedAffectedTuple::aggregateType)
              .thenComparing(OwnedAffectedTuple::aggregateId)
              .thenComparing(OwnedAffectedTuple::scopeType)
              .thenComparing(OwnedAffectedTuple::scopeId)
              .thenComparing(OwnedAffectedTuple::expectedEpoch));
      for (int index = 1; index < ordered.size(); index++) {
        if (ordered.get(index - 1).equals(ordered.get(index))) {
          throw new IllegalArgumentException("duplicate World affected tuple");
        }
      }
      suppliedOwnedAffectedTuples = List.copyOf(ordered);
    }
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0L) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requireSha256(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be 64 lowercase hexadecimal digits");
    }
  }

  private static void requirePrefixedSha256(String value, String label) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a canonical prefixed SHA-256 digest");
    }
  }
}
