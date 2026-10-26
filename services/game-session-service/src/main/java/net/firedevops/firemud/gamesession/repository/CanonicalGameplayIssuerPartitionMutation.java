package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** One immutable, owner-issued expected/successor revision for a single partition mutation. */
final class CanonicalGameplayIssuerPartitionMutation {
  enum Kind {
    ADD_CAPACITY,
    ADD_BINDING,
    RELEASE,
    RETIRE
  }

  enum Status {
    PENDING_PHYSICAL_PROOF,
    COMMITTED
  }

  private final UUID mutationId;
  private final BigInteger partitionId;
  private final Kind kind;
  private final UUID ownerOperationId;
  private final UUID expectedRevision;
  private final UUID successorRevision;
  private final byte[] exactIntendedChangeBytes;
  private final String exactIntendedChangeDigest;
  private final Status status;
  private final byte[] exactReadbackFieldSetBytes;
  private final String exactReadbackFieldSetDigest;

  CanonicalGameplayIssuerPartitionMutation(
      UUID mutationId,
      BigInteger partitionId,
      Kind kind,
      UUID ownerOperationId,
      UUID expectedRevision,
      UUID successorRevision,
      byte[] exactIntendedChangeBytes,
      String exactIntendedChangeDigest,
      Status status,
      byte[] exactReadbackFieldSetBytes,
      String exactReadbackFieldSetDigest) {
    this.mutationId = requireNonNil(mutationId, "mutationId");
    this.partitionId = Objects.requireNonNull(partitionId, "partitionId");
    if (partitionId.signum() < 0)
      throw new IllegalArgumentException("partitionId must be nonnegative");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.ownerOperationId = requireNonNil(ownerOperationId, "ownerOperationId");
    this.expectedRevision = requireNonNil(expectedRevision, "expectedRevision");
    this.successorRevision = requireNonNil(successorRevision, "successorRevision");
    if (expectedRevision.equals(successorRevision)) {
      throw new IllegalArgumentException("A partition mutation successor must be a new revision");
    }
    this.exactIntendedChangeBytes =
        Objects.requireNonNull(exactIntendedChangeBytes, "exactIntendedChangeBytes").clone();
    if (this.exactIntendedChangeBytes.length == 0
        || this.exactIntendedChangeBytes.length > 262_144) {
      throw new IllegalArgumentException("Exact intended partition change is outside its bound");
    }
    this.exactIntendedChangeDigest =
        requireDigest(exactIntendedChangeDigest, "exactIntendedChangeDigest");
    if (!this.exactIntendedChangeDigest.equals(
        CanonicalGameplayIssuerMemberV2Codec.sha256(this.exactIntendedChangeBytes))) {
      throw new IllegalArgumentException("Exact intended partition change digest differs");
    }
    this.status = Objects.requireNonNull(status, "status");
    this.exactReadbackFieldSetBytes =
        exactReadbackFieldSetBytes == null ? null : exactReadbackFieldSetBytes.clone();
    this.exactReadbackFieldSetDigest = exactReadbackFieldSetDigest;
    if (status == Status.PENDING_PHYSICAL_PROOF) {
      if (exactReadbackFieldSetBytes != null || exactReadbackFieldSetDigest != null) {
        throw new IllegalArgumentException(
            "Pending partition mutation cannot contain readback proof");
      }
    } else {
      if (this.exactReadbackFieldSetBytes == null
          || this.exactReadbackFieldSetBytes.length == 0
          || this.exactReadbackFieldSetBytes.length > 17_825_792
          || !Objects.equals(
              exactReadbackFieldSetDigest,
              CanonicalGameplayIssuerMemberV2Codec.sha256(this.exactReadbackFieldSetBytes))) {
        throw new IllegalArgumentException(
            "Committed partition mutation needs exact readback proof");
      }
    }
  }

  UUID mutationId() {
    return mutationId;
  }

  BigInteger partitionId() {
    return partitionId;
  }

  Kind kind() {
    return kind;
  }

  UUID ownerOperationId() {
    return ownerOperationId;
  }

  UUID expectedRevision() {
    return expectedRevision;
  }

  UUID successorRevision() {
    return successorRevision;
  }

  byte[] exactIntendedChangeBytes() {
    return exactIntendedChangeBytes.clone();
  }

  String exactIntendedChangeDigest() {
    return exactIntendedChangeDigest;
  }

  Status status() {
    return status;
  }

  byte[] exactReadbackFieldSetBytes() {
    return exactReadbackFieldSetBytes == null ? null : exactReadbackFieldSetBytes.clone();
  }

  String exactReadbackFieldSetDigest() {
    return exactReadbackFieldSetDigest;
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must not be nil");
    return value;
  }

  private static String requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
    return value;
  }
}
