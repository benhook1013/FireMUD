package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/**
 * Immutable SQL-issued preparation for one ordinary NEW issuer member after the initial installed
 * anchor. This operation remains pending physical proof and is not an issuer-wide acknowledgement
 * or admission capability.
 */
final class CanonicalGameplayIssuerBindingLocalOperation {
  enum Status {
    PENDING_PHYSICAL_PROOF
  }

  /** Caller correlation and expected candidate identity; it carries no reservation authority. */
  record Request(
      UUID requestId,
      UUID transitionId,
      CanonicalGameplayBindingIdentity candidate,
      CanonicalGameplayBindingRef expectedPriorBindingRef,
      BigInteger expectedPriorBindingGeneration) {
    Request {
      requireNonNil(requestId, "requestId");
      requireNonNil(transitionId, "transitionId");
      if (requestId.equals(transitionId)) {
        throw new IllegalArgumentException("requestId and transitionId must be distinct");
      }
      Objects.requireNonNull(candidate, "candidate");
      if ((expectedPriorBindingRef == null) != (expectedPriorBindingGeneration == null)) {
        throw new IllegalArgumentException(
            "Expected prior reference and generation must be paired");
      }
      if (expectedPriorBindingGeneration != null && expectedPriorBindingGeneration.signum() <= 0) {
        throw new IllegalArgumentException("Expected prior generation must be positive");
      }
    }
  }

  private final Request request;
  private final UUID candidateAccountIndexFence;
  private final CanonicalGameplayBindingTransitionSnapshot preparedTransition;
  private final BigInteger localInventorySnapshotRevision;
  private final CanonicalIssuerPartitionReservation reservation;
  private final CanonicalGameplayIssuerInstalledAnchor anchor;
  private final CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
      baselinePartitionProof;
  private final CanonicalGameplayIssuerMemberV2Value memberValue;
  private final UUID operationId;
  private final BigInteger operationFence;
  private final BigInteger localMemberGeneration;
  private final byte[] exactMemberValueBytes;
  private final String memberValueDigest;
  private final Status status;

  CanonicalGameplayIssuerBindingLocalOperation(
      Request request,
      UUID candidateAccountIndexFence,
      CanonicalGameplayBindingTransitionSnapshot preparedTransition,
      BigInteger localInventorySnapshotRevision,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
          baselinePartitionProof,
      CanonicalGameplayIssuerMemberV2Value memberValue,
      UUID operationId,
      BigInteger operationFence,
      BigInteger localMemberGeneration,
      byte[] exactMemberValueBytes,
      String memberValueDigest,
      Status status) {
    this.request = Objects.requireNonNull(request, "request");
    this.candidateAccountIndexFence =
        requireNonNil(candidateAccountIndexFence, "candidateAccountIndexFence");
    this.preparedTransition = Objects.requireNonNull(preparedTransition, "preparedTransition");
    this.localInventorySnapshotRevision =
        requirePositive(localInventorySnapshotRevision, "localInventorySnapshotRevision");
    this.reservation = Objects.requireNonNull(reservation, "reservation");
    this.anchor = Objects.requireNonNull(anchor, "anchor");
    this.baselinePartitionProof =
        Objects.requireNonNull(baselinePartitionProof, "baselinePartitionProof");
    this.memberValue = Objects.requireNonNull(memberValue, "memberValue");
    this.operationId = requireNonNil(operationId, "operationId");
    this.operationFence = requirePositive(operationFence, "operationFence");
    this.localMemberGeneration = requirePositive(localMemberGeneration, "localMemberGeneration");
    this.exactMemberValueBytes =
        Objects.requireNonNull(exactMemberValueBytes, "exactMemberValueBytes").clone();
    if (this.exactMemberValueBytes.length == 0
        || this.exactMemberValueBytes.length
            > CanonicalGameplayIssuerMemberV2Codec.MAX_VALUE_BYTES) {
      throw new IllegalArgumentException("Exact local member bytes are outside their bound");
    }
    this.memberValueDigest = requireDigest(memberValueDigest, "memberValueDigest");
    this.status = Objects.requireNonNull(status, "status");

    if (request.expectedPriorBindingRef() != null
        || request.expectedPriorBindingGeneration() != null
        || !preparedTransition.transitionId().equals(request.transitionId())
        || !preparedTransition.candidate().equals(request.candidate())
        || !preparedTransition.bindingRef().equals(request.candidate().bindingRef())
        || !reservation.owner().equals(request.candidate())
        || !reservation.transitionId().equals(request.transitionId())
        || !reservation.inventoryRevision().equals(localInventorySnapshotRevision)
        || !reservation.bindingGeneration().equals(preparedTransition.bindingGeneration())
        || !reservation.reservationId().equals(preparedTransition.issuerReservationId())
        || !anchor.issuerId().equals(request.candidate().issuerId())
        || !anchor.layoutVersion().equals(request.candidate().issuerIndexLayoutVersion())
        || !anchor.partitionCount().equals(request.candidate().issuerIndexPartitionCount())
        || !anchor.partitionCapacity().equals(request.candidate().issuerIndexPartitionCapacity())
        || !baselinePartitionProof
            .partitionId()
            .equals(request.candidate().bindingRef().partitionId(anchor.partitionCount()))
        || !operationId.equals(memberValue.memberOperation().operationId())
        || !operationFence.equals(memberValue.memberOperation().operationFence())
        || !localMemberGeneration.equals(memberValue.memberOperation().coverageGeneration())
        || !memberValue.owner().equals(request.candidate())
        || !memberValue.bindingGeneration().equals(preparedTransition.bindingGeneration())
        || !memberValue.reservation().equals(reservation)
        || !memberValue
            .anchor()
            .issuerCoverageOperationId()
            .equals(anchor.issuerCoverageOperationId())
        || !memberValue.anchor().operationFence().equals(anchor.operationFence())
        || !memberValue.anchor().coverageFence().equals(anchor.coverageFence())
        || !memberValue
            .anchor()
            .issuerInventorySnapshotRevision()
            .equals(anchor.issuerInventorySnapshotRevision())
        || !Arrays.equals(
            this.exactMemberValueBytes, CanonicalGameplayIssuerMemberV2Codec.encode(memberValue))
        || !memberValueDigest.equals(
            CanonicalGameplayIssuerMemberV2Codec.sha256(this.exactMemberValueBytes))
        || !memberValue.equals(
            CanonicalGameplayIssuerMemberV2Codec.decode(this.exactMemberValueBytes))
        || status != Status.PENDING_PHYSICAL_PROOF) {
      throw new IllegalArgumentException(
          "Local issuer operation differs from its exact prepared NEW tuple");
    }
  }

  Request request() {
    return request;
  }

  UUID candidateAccountIndexFence() {
    return candidateAccountIndexFence;
  }

  CanonicalGameplayBindingTransitionSnapshot preparedTransition() {
    return preparedTransition;
  }

  BigInteger localInventorySnapshotRevision() {
    return localInventorySnapshotRevision;
  }

  CanonicalIssuerPartitionReservation reservation() {
    return reservation;
  }

  CanonicalGameplayIssuerInstalledAnchor anchor() {
    return anchor;
  }

  CanonicalGameplayIssuerBootstrapCoverageRepository.InstalledAnchorPartitionProof
      baselinePartitionProof() {
    return baselinePartitionProof;
  }

  CanonicalGameplayIssuerMemberV2Value memberValue() {
    return memberValue;
  }

  UUID operationId() {
    return operationId;
  }

  BigInteger operationFence() {
    return operationFence;
  }

  BigInteger localMemberGeneration() {
    return localMemberGeneration;
  }

  byte[] exactMemberValueBytes() {
    return exactMemberValueBytes.clone();
  }

  String memberValueDigest() {
    return memberValueDigest;
  }

  Status status() {
    return status;
  }

  /** The exact binding and capacity fields are absent before this pending operation is applied. */
  String expectedOldMemberState() {
    return "ABSENT";
  }

  String expectedOldCapacityState() {
    return "ABSENT";
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must not be nil");
    return value;
  }

  private static BigInteger requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static String requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalArgumentException(name + " must be canonical SHA-256");
    return value;
  }
}
