package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/**
 * Immutable shape-only carrier for the complete schema-2 issuer partition member value.
 *
 * <p>Construction and decoding validate retained evidence and tuple consistency. They do not
 * establish trusted ownership, currentness, issuer coverage, or admission authority.
 */
final class CanonicalGameplayIssuerMemberV2Value {
  static final int SCHEMA_VERSION = 2;

  private final CanonicalGameplayBindingIdentity owner;
  private final BigInteger bindingGeneration;
  private final CanonicalIssuerPartitionReservation reservation;
  private final Anchor anchor;
  private final LocalMemberOperation memberOperation;
  private final BigInteger issuerControlEpoch;
  private final CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layoutDescriptor;
  private final CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint
      sourceCheckpoint;
  private final Incarnation incarnation;

  CanonicalGameplayIssuerMemberV2Value(
      CanonicalGameplayBindingIdentity owner,
      BigInteger bindingGeneration,
      CanonicalIssuerPartitionReservation reservation,
      Anchor anchor,
      LocalMemberOperation memberOperation,
      BigInteger issuerControlEpoch,
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layoutDescriptor,
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint sourceCheckpoint,
      Incarnation incarnation) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.bindingGeneration = requirePositive(bindingGeneration, "bindingGeneration");
    this.reservation = Objects.requireNonNull(reservation, "reservation");
    this.anchor = Objects.requireNonNull(anchor, "anchor");
    this.memberOperation = Objects.requireNonNull(memberOperation, "memberOperation");
    this.issuerControlEpoch = requirePositive(issuerControlEpoch, "issuerControlEpoch");
    this.layoutDescriptor = Objects.requireNonNull(layoutDescriptor, "layoutDescriptor");
    this.sourceCheckpoint = Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
    this.incarnation = Objects.requireNonNull(incarnation, "incarnation");

    if (!owner.issuerId().equals(layoutDescriptor.issuerId())
        || !owner.issuerId().equals(sourceCheckpoint.issuerId())) {
      throw new IllegalArgumentException("Issuer member, layout, and source scopes must match");
    }
    if (!owner.issuerIndexLayoutVersion().equals(layoutDescriptor.layoutVersion())
        || !owner.issuerIndexPartitionCount().equals(layoutDescriptor.partitionCount())
        || !owner.issuerIndexPartitionCapacity().equals(layoutDescriptor.partitionCapacity())) {
      throw new IllegalArgumentException("Issuer member must retain its exact pinned layout");
    }
    if (!owner.issuerAuthGeneration().equals(sourceCheckpoint.issuerAuthGeneration())) {
      throw new IllegalArgumentException(
          "Account issuer source generation must match binding issuerAuthGeneration");
    }
    if (!reservation.owner().equals(owner)
        || !reservation.bindingGeneration().equals(bindingGeneration)
        || !reservation
            .partitionId()
            .equals(owner.bindingRef().partitionId(owner.issuerIndexPartitionCount()))) {
      throw new IllegalArgumentException(
          "Issuer reservation must retain the exact binding owner, generation, and partition");
    }
    // The reservation's original coverage tuple is independent from the newly pinned anchor.
    requireDigestMatches(
        layoutDescriptor.exactDescriptorBytes(), layoutDescriptor.descriptorDigest(), "descriptor");
    requireDigestMatches(
        sourceCheckpoint.exactCheckpointBytes(),
        sourceCheckpoint.checkpointDigest(),
        "source checkpoint");
    byte[] sourceEventBytes = sourceCheckpoint.latestIssuerEventBytes();
    if (sourceEventBytes != null) {
      requireDigestMatches(
          sourceEventBytes, sourceCheckpoint.latestIssuerEventDigest(), "source event");
    }
  }

  CanonicalGameplayBindingIdentity owner() {
    return owner;
  }

  BigInteger bindingGeneration() {
    return bindingGeneration;
  }

  CanonicalIssuerPartitionReservation reservation() {
    return reservation;
  }

  Anchor anchor() {
    return anchor;
  }

  LocalMemberOperation memberOperation() {
    return memberOperation;
  }

  BigInteger issuerControlEpoch() {
    return issuerControlEpoch;
  }

  CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layoutDescriptor() {
    return layoutDescriptor;
  }

  CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint sourceCheckpoint() {
    return sourceCheckpoint;
  }

  Incarnation incarnation() {
    return incarnation;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof CanonicalGameplayIssuerMemberV2Value value)) {
      return false;
    }
    return owner.equals(value.owner)
        && bindingGeneration.equals(value.bindingGeneration)
        && reservation.equals(value.reservation)
        && anchor.equals(value.anchor)
        && memberOperation.equals(value.memberOperation)
        && issuerControlEpoch.equals(value.issuerControlEpoch)
        && sameLayout(layoutDescriptor, value.layoutDescriptor)
        && sameSourceCheckpoint(sourceCheckpoint, value.sourceCheckpoint)
        && incarnation.equals(value.incarnation);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            owner, bindingGeneration, reservation, anchor, memberOperation, issuerControlEpoch);
    result = 31 * result + layoutHashCode(layoutDescriptor);
    result = 31 * result + sourceCheckpointHashCode(sourceCheckpoint);
    return 31 * result + incarnation.hashCode();
  }

  private static boolean sameLayout(
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor left,
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor right) {
    return left.issuerId().equals(right.issuerId())
        && left.layoutVersion().equals(right.layoutVersion())
        && left.partitionCount().equals(right.partitionCount())
        && left.partitionCapacity().equals(right.partitionCapacity())
        && left.mappingIdentity().equals(right.mappingIdentity())
        && Arrays.equals(left.exactDescriptorBytes(), right.exactDescriptorBytes())
        && left.descriptorDigest().equals(right.descriptorDigest());
  }

  private static int layoutHashCode(
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor layout) {
    int result =
        Objects.hash(
            layout.issuerId(),
            layout.layoutVersion(),
            layout.partitionCount(),
            layout.partitionCapacity(),
            layout.mappingIdentity(),
            layout.descriptorDigest());
    return 31 * result + Arrays.hashCode(layout.exactDescriptorBytes());
  }

  private static boolean sameSourceCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint left,
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint right) {
    return left.issuerId().equals(right.issuerId())
        && left.issuerAuthGeneration().equals(right.issuerAuthGeneration())
        && Arrays.equals(left.exactCheckpointBytes(), right.exactCheckpointBytes())
        && left.checkpointDigest().equals(right.checkpointDigest())
        && Arrays.equals(left.latestIssuerEventBytes(), right.latestIssuerEventBytes())
        && Objects.equals(left.latestIssuerEventDigest(), right.latestIssuerEventDigest());
  }

  private static int sourceCheckpointHashCode(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint source) {
    int result =
        Objects.hash(
            source.issuerId(),
            source.issuerAuthGeneration(),
            source.checkpointDigest(),
            source.latestIssuerEventDigest());
    result = 31 * result + Arrays.hashCode(source.exactCheckpointBytes());
    return 31 * result + Arrays.hashCode(source.latestIssuerEventBytes());
  }

  record Anchor(
      UUID issuerCoverageOperationId,
      BigInteger operationFence,
      BigInteger coverageFence,
      BigInteger issuerInventorySnapshotRevision) {
    Anchor {
      requireNonNil(issuerCoverageOperationId, "issuerCoverageOperationId");
      requirePositive(operationFence, "operationFence");
      requirePositive(coverageFence, "coverageFence");
      requirePositive(issuerInventorySnapshotRevision, "issuerInventorySnapshotRevision");
      if (operationFence.equals(coverageFence)) {
        throw new IllegalArgumentException("Issuer operationFence and coverageFence are distinct");
      }
    }
  }

  record LocalMemberOperation(
      UUID operationId, BigInteger operationFence, BigInteger coverageGeneration) {
    LocalMemberOperation {
      requireNonNil(operationId, "operationId");
      requirePositive(operationFence, "member operationFence");
      requirePositive(coverageGeneration, "coverageGeneration");
    }
  }

  record Incarnation(
      String attestorBinding,
      String acceptedAttestationDigest,
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
          targetDeploymentEvidence,
      String coordOpsRoAclDigest,
      String coordRedisConfigObservedDigest) {
    Incarnation {
      requireNonBlank(attestorBinding, "attestorBinding");
      requireDigest(acceptedAttestationDigest, "acceptedAttestationDigest");
      Objects.requireNonNull(targetDeploymentEvidence, "targetDeploymentEvidence");
      requireDigest(coordOpsRoAclDigest, "coordOpsRoAclDigest");
      requireDigest(coordRedisConfigObservedDigest, "coordRedisConfigObservedDigest");
    }
  }

  private static BigInteger requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be canonical SHA-256");
    }
  }

  private static void requireDigestMatches(byte[] bytes, String digest, String name) {
    requireDigest(digest, name + " digest");
    if (!CanonicalGameplayIssuerMemberV2Codec.sha256(bytes).equals(digest)) {
      throw new IllegalArgumentException(name + " digest does not match its exact bytes");
    }
  }
}
