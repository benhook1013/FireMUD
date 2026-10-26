package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/**
 * Durable, versioned proof for one ordinary NEW issuer member and its reservation readback. Its
 * positive transition evidence generation is issued only with the accepted SQL receipt and is
 * separate from both the preparatory member-operation generation and the anchor's global coverage
 * generation. This binding-local evidence does not establish issuer-wide coverage, barrier OPEN, or
 * tenant admission.
 */
final class CanonicalGameplayIssuerBindingLocalAcknowledgement {
  static final int SCHEMA_VERSION = 1;
  static final String SCOPE = "ISSUER_BINDING_LOCAL";
  static final String GLOBAL_COVERAGE_GENERATION = "NOT_YET_ISSUED";

  private final Evidence evidence;
  private final byte[] exactBytes;
  private final String digest;

  private CanonicalGameplayIssuerBindingLocalAcknowledgement(Evidence evidence, byte[] exactBytes) {
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.exactBytes = Objects.requireNonNull(exactBytes, "exactBytes").clone();
    this.digest = CanonicalGameplayIssuerMemberV2Codec.sha256(this.exactBytes);
    if (!Arrays.equals(
        this.exactBytes,
        CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.encode(evidence))) {
      throw new IllegalArgumentException(
          "Issuer binding-local acknowledgement bytes are not canonical");
    }
  }

  static CanonicalGameplayIssuerBindingLocalAcknowledgement create(Evidence evidence) {
    return new CanonicalGameplayIssuerBindingLocalAcknowledgement(
        evidence, CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.encode(evidence));
  }

  static CanonicalGameplayIssuerBindingLocalAcknowledgement decode(byte[] exactBytes) {
    Evidence evidence = CanonicalGameplayIssuerBindingLocalAcknowledgementCodec.decode(exactBytes);
    return new CanonicalGameplayIssuerBindingLocalAcknowledgement(evidence, exactBytes);
  }

  Evidence evidence() {
    return evidence;
  }

  UUID operationId() {
    return evidence.operationId();
  }

  UUID requestId() {
    return evidence.requestId();
  }

  UUID transitionId() {
    return evidence.transitionId();
  }

  byte[] exactBytes() {
    return exactBytes.clone();
  }

  String digest() {
    return digest;
  }

  record Evidence(
      UUID operationId,
      BigInteger issuerBindingLocalAcknowledgementGeneration,
      UUID requestId,
      UUID transitionId,
      UUID candidateAccountIndexFence,
      String issuerId,
      byte[] bindingRef,
      BigInteger bindingGeneration,
      byte[] memberValueBytes,
      String memberValueDigest,
      Reservation reservation,
      Anchor anchor,
      MemberOperation memberOperation,
      CurrentSource currentSource,
      PhysicalReadback physicalReadback,
      Capacity capacity,
      String sqlCurrentnessDigest) {
    Evidence {
      requireNonNil(operationId, "operationId");
      issuerBindingLocalAcknowledgementGeneration =
          positive(
              issuerBindingLocalAcknowledgementGeneration,
              "issuerBindingLocalAcknowledgementGeneration");
      requireNonNil(requestId, "requestId");
      requireNonNil(transitionId, "transitionId");
      requireNonNil(candidateAccountIndexFence, "candidateAccountIndexFence");
      if (requestId.equals(transitionId)) {
        throw new IllegalArgumentException("Request and transition identity must be distinct");
      }
      if (!GameSessionAccountDelegationProfile.ISSUER.equals(issuerId)) {
        throw new IllegalArgumentException("Issuer is not the exact Account environment issuer");
      }
      bindingRef = nonEmpty(bindingRef, "bindingRef");
      bindingGeneration = positive(bindingGeneration, "bindingGeneration");
      memberValueBytes = nonEmpty(memberValueBytes, "memberValueBytes");
      memberValueDigest = digest(memberValueDigest, "memberValueDigest");
      reservation = Objects.requireNonNull(reservation, "reservation");
      anchor = Objects.requireNonNull(anchor, "anchor");
      memberOperation = Objects.requireNonNull(memberOperation, "memberOperation");
      currentSource = Objects.requireNonNull(currentSource, "currentSource");
      physicalReadback = Objects.requireNonNull(physicalReadback, "physicalReadback");
      capacity = Objects.requireNonNull(capacity, "capacity");
      sqlCurrentnessDigest = digest(sqlCurrentnessDigest, "sqlCurrentnessDigest");

      CanonicalGameplayIssuerMemberV2Value member =
          CanonicalGameplayIssuerMemberV2Codec.decode(memberValueBytes);
      if (!memberValueDigest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(memberValueBytes))
          || !member.owner().issuerId().equals(issuerId)
          || !Arrays.equals(member.owner().bindingRef().bytes(), bindingRef)
          || !member.bindingGeneration().equals(bindingGeneration)
          || !reservation.matches(member)
          || !anchor.matches(member)
          || !memberOperation.matches(member)
          || !currentSource.matches(member)
          || !physicalReadback.matches(memberValueBytes, bindingRef, reservation.reservationId())
          || !capacity.matches(member, physicalReadback)) {
        throw new IllegalArgumentException(
            "Issuer binding-local acknowledgement differs from its exact member and proof tuples");
      }
    }

    @Override
    public byte[] bindingRef() {
      return bindingRef.clone();
    }

    @Override
    public byte[] memberValueBytes() {
      return memberValueBytes.clone();
    }
  }

  record Reservation(
      UUID reservationId,
      UUID transitionId,
      UUID reservationFence,
      BigInteger partitionId,
      String lifecycle,
      UUID issuerCoverageOperationId,
      BigInteger issuerCoverageOperationFence,
      BigInteger coverageFence,
      BigInteger inventorySnapshotRevision,
      BigInteger inventoryRevision) {
    Reservation {
      requireNonNil(reservationId, "reservationId");
      requireNonNil(transitionId, "reservation transitionId");
      requireNonNil(reservationFence, "reservationFence");
      partitionId = nonNegative(partitionId, "partitionId");
      if (!CanonicalIssuerPartitionReservation.Lifecycle.RESERVED.name().equals(lifecycle)) {
        throw new IllegalArgumentException(
            "An issuer binding-local acknowledgement requires RESERVED");
      }
      requireNonNil(issuerCoverageOperationId, "original issuerCoverageOperationId");
      issuerCoverageOperationFence =
          positive(issuerCoverageOperationFence, "original operationFence");
      coverageFence = positive(coverageFence, "original coverageFence");
      inventorySnapshotRevision =
          positive(inventorySnapshotRevision, "original inventorySnapshotRevision");
      inventoryRevision = positive(inventoryRevision, "local inventoryRevision");
    }

    private boolean matches(CanonicalGameplayIssuerMemberV2Value member) {
      var value = member.reservation();
      return reservationId.equals(value.reservationId())
          && transitionId.equals(value.transitionId())
          && reservationFence.equals(value.reservationFence())
          && partitionId.equals(value.partitionId())
          && lifecycle.equals(value.lifecycle().name())
          && issuerCoverageOperationId.equals(value.issuerCoverageOperationId())
          && issuerCoverageOperationFence.equals(value.issuerCoverageOperationFence())
          && coverageFence.equals(value.coverageFence())
          && inventorySnapshotRevision.equals(value.inventorySnapshotRevision())
          && inventoryRevision.equals(value.inventoryRevision());
    }
  }

  record Anchor(
      UUID bootstrapOperationId,
      UUID issuerCoverageOperationId,
      BigInteger operationFence,
      BigInteger coverageFence,
      BigInteger inventorySnapshotRevision,
      BigInteger globalCoverageGeneration,
      BigInteger controlEpoch,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      String mappingIdentity,
      byte[] layoutDescriptorBytes,
      String layoutDescriptorDigest,
      byte[] exactBytes,
      String exactDigest) {
    Anchor {
      requireNonNil(bootstrapOperationId, "bootstrapOperationId");
      requireNonNil(issuerCoverageOperationId, "anchor issuerCoverageOperationId");
      operationFence = positive(operationFence, "anchor operationFence");
      coverageFence = positive(coverageFence, "anchor coverageFence");
      inventorySnapshotRevision =
          positive(inventorySnapshotRevision, "anchor inventorySnapshotRevision");
      globalCoverageGeneration =
          positive(globalCoverageGeneration, "anchor globalCoverageGeneration");
      controlEpoch = positive(controlEpoch, "issuerControlEpoch");
      layoutVersion = positive(layoutVersion, "layoutVersion");
      partitionCount = positive(partitionCount, "partitionCount");
      partitionCapacity = positive(partitionCapacity, "partitionCapacity");
      if (operationFence.equals(coverageFence) || !operationFence.equals(controlEpoch)) {
        throw new IllegalArgumentException("Installed issuer control tuple is not exact");
      }
      if (!bootstrapOperationId.equals(issuerCoverageOperationId)) {
        throw new IllegalArgumentException("Installed anchor differs from its bootstrap operation");
      }
      if (!CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY.equals(mappingIdentity)) {
        throw new IllegalArgumentException("Issuer mapping identity is unsupported");
      }
      layoutDescriptorBytes = nonEmpty(layoutDescriptorBytes, "layoutDescriptorBytes");
      layoutDescriptorDigest = digest(layoutDescriptorDigest, "layoutDescriptorDigest");
      exactBytes = nonEmpty(exactBytes, "exactAnchorBytes");
      exactDigest = digest(exactDigest, "exactAnchorDigest");
      requireMatches(layoutDescriptorBytes, layoutDescriptorDigest, "layout descriptor");
      requireMatches(exactBytes, exactDigest, "installed anchor");
    }

    @Override
    public byte[] layoutDescriptorBytes() {
      return layoutDescriptorBytes.clone();
    }

    @Override
    public byte[] exactBytes() {
      return exactBytes.clone();
    }

    private boolean matches(CanonicalGameplayIssuerMemberV2Value member) {
      var value = member.anchor();
      return issuerCoverageOperationId.equals(value.issuerCoverageOperationId())
          && operationFence.equals(value.operationFence())
          && coverageFence.equals(value.coverageFence())
          && inventorySnapshotRevision.equals(value.issuerInventorySnapshotRevision())
          && controlEpoch.equals(member.issuerControlEpoch())
          && layoutVersion.equals(member.layoutDescriptor().layoutVersion())
          && partitionCount.equals(member.layoutDescriptor().partitionCount())
          && partitionCapacity.equals(member.layoutDescriptor().partitionCapacity())
          && mappingIdentity.equals(member.layoutDescriptor().mappingIdentity())
          && Arrays.equals(layoutDescriptorBytes, member.layoutDescriptor().exactDescriptorBytes());
    }
  }

  record MemberOperation(UUID operationId, BigInteger operationFence, BigInteger generation) {
    MemberOperation {
      requireNonNil(operationId, "member operationId");
      operationFence = positive(operationFence, "member operationFence");
      generation = positive(generation, "member operation generation");
    }

    private boolean matches(CanonicalGameplayIssuerMemberV2Value member) {
      var value = member.memberOperation();
      return operationId.equals(value.operationId())
          && operationFence.equals(value.operationFence())
          && generation.equals(value.coverageGeneration());
    }
  }

  record CurrentSource(
      BigInteger issuerAuthGeneration,
      byte[] checkpointBytes,
      String checkpointDigest,
      byte[] eventBytes,
      String eventDigest,
      byte[] incarnationBytes,
      String incarnationDigest) {
    CurrentSource {
      issuerAuthGeneration = positive(issuerAuthGeneration, "issuerAuthGeneration");
      checkpointBytes = nonEmpty(checkpointBytes, "current source checkpoint bytes");
      checkpointDigest = digest(checkpointDigest, "current source checkpoint digest");
      eventBytes = eventBytes == null ? null : nonEmpty(eventBytes, "current source event bytes");
      eventDigest = eventDigest == null ? null : digest(eventDigest, "current source event digest");
      if ((eventBytes == null) != (eventDigest == null)) {
        throw new IllegalArgumentException(
            "Current issuer source event bytes and digest must be paired");
      }
      incarnationBytes = nonEmpty(incarnationBytes, "current stable incarnation bytes");
      incarnationDigest = digest(incarnationDigest, "current stable incarnation digest");
      requireMatches(checkpointBytes, checkpointDigest, "current source checkpoint");
      if (eventBytes != null) requireMatches(eventBytes, eventDigest, "current source event");
      requireMatches(incarnationBytes, incarnationDigest, "current stable incarnation");
    }

    @Override
    public byte[] checkpointBytes() {
      return checkpointBytes.clone();
    }

    @Override
    public byte[] eventBytes() {
      return eventBytes == null ? null : eventBytes.clone();
    }

    @Override
    public byte[] incarnationBytes() {
      return incarnationBytes.clone();
    }

    private boolean matches(CanonicalGameplayIssuerMemberV2Value member) {
      var source = member.sourceCheckpoint();
      return issuerAuthGeneration.equals(member.owner().issuerAuthGeneration())
          && Arrays.equals(checkpointBytes, source.exactCheckpointBytes())
          && checkpointDigest.equals(source.checkpointDigest())
          && Arrays.equals(eventBytes, source.latestIssuerEventBytes())
          && Objects.equals(eventDigest, source.latestIssuerEventDigest())
          && Arrays.equals(
              incarnationBytes,
              CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(member.incarnation()));
    }
  }

  record PhysicalReadback(
      byte[] bindingValueBytes,
      String bindingValueDigest,
      byte[] capacityValueBytes,
      String capacityValueDigest,
      String partitionFieldSetDigest,
      int scriptOutcome,
      int fieldCount,
      int reservationCount,
      int bindingCount,
      int countedCapacity) {
    PhysicalReadback {
      bindingValueBytes = nonEmpty(bindingValueBytes, "physical binding readback");
      bindingValueDigest = digest(bindingValueDigest, "physical binding readback digest");
      capacityValueBytes = nonEmpty(capacityValueBytes, "physical capacity readback");
      capacityValueDigest = digest(capacityValueDigest, "physical capacity readback digest");
      partitionFieldSetDigest = digest(partitionFieldSetDigest, "physical field-set digest");
      if (scriptOutcome < 1 || scriptOutcome > 3) {
        throw new IllegalArgumentException("Physical issuer script outcome is unsupported");
      }
      if (fieldCount < 1 || reservationCount < 1 || bindingCount < 1 || countedCapacity < 1) {
        throw new IllegalArgumentException("Physical issuer readback counts must be positive");
      }
      if (fieldCount != 1 + reservationCount + bindingCount || bindingCount > reservationCount) {
        throw new IllegalArgumentException("Physical issuer field counts are inconsistent");
      }
      requireMatches(bindingValueBytes, bindingValueDigest, "physical binding value");
      requireMatches(capacityValueBytes, capacityValueDigest, "physical capacity value");
      if (!Arrays.equals(bindingValueBytes, capacityValueBytes)) {
        throw new IllegalArgumentException("Binding and reservation readbacks differ");
      }
    }

    @Override
    public byte[] bindingValueBytes() {
      return bindingValueBytes.clone();
    }

    @Override
    public byte[] capacityValueBytes() {
      return capacityValueBytes.clone();
    }

    private boolean matches(byte[] memberBytes, byte[] bindingRef, UUID reservationId) {
      if (!Arrays.equals(bindingValueBytes, memberBytes)
          || !Arrays.equals(capacityValueBytes, memberBytes)) return false;
      var member = CanonicalGameplayIssuerMemberV2Codec.decode(memberBytes);
      return Arrays.equals(member.owner().bindingRef().bytes(), bindingRef)
          && reservationId.equals(member.reservation().reservationId());
    }
  }

  record Capacity(
      BigInteger partitionId,
      BigInteger partitionCapacity,
      BigInteger baselineCountedCapacity,
      BigInteger baselineReservationCount,
      BigInteger baselineBindingCount,
      BigInteger sqlPartitionCapacity,
      BigInteger sqlAggregateCapacity,
      BigInteger issuerAggregateBound) {
    Capacity {
      partitionId = nonNegative(partitionId, "capacity partitionId");
      partitionCapacity = positive(partitionCapacity, "capacity partitionCapacity");
      baselineCountedCapacity = nonNegative(baselineCountedCapacity, "baselineCountedCapacity");
      baselineReservationCount = nonNegative(baselineReservationCount, "baselineReservationCount");
      baselineBindingCount = nonNegative(baselineBindingCount, "baselineBindingCount");
      sqlPartitionCapacity = positive(sqlPartitionCapacity, "sqlPartitionCapacity");
      sqlAggregateCapacity = nonNegative(sqlAggregateCapacity, "sqlAggregateCapacity");
      issuerAggregateBound = positive(issuerAggregateBound, "issuerAggregateBound");
      if (baselineCountedCapacity.compareTo(partitionCapacity) > 0
          || sqlPartitionCapacity.compareTo(partitionCapacity) > 0
          || sqlAggregateCapacity.signum() == 0
          || sqlAggregateCapacity.compareTo(issuerAggregateBound) > 0) {
        throw new IllegalArgumentException("Issuer local capacity proof exceeds its pinned bound");
      }
    }

    private boolean matches(
        CanonicalGameplayIssuerMemberV2Value member, PhysicalReadback readback) {
      return partitionId.equals(member.reservation().partitionId())
          && partitionCapacity.equals(member.layoutDescriptor().partitionCapacity())
          && partitionId.compareTo(member.layoutDescriptor().partitionCount()) < 0
          && BigInteger.valueOf(readback.countedCapacity()).compareTo(partitionCapacity) <= 0
          && BigInteger.valueOf(readback.countedCapacity())
                  .compareTo(baselineCountedCapacity.add(BigInteger.ONE))
              >= 0
          && BigInteger.valueOf(readback.reservationCount())
                  .compareTo(baselineReservationCount.add(BigInteger.ONE))
              >= 0
          && BigInteger.valueOf(readback.bindingCount())
                  .compareTo(baselineBindingCount.add(BigInteger.ONE))
              >= 0
          && BigInteger.valueOf(readback.countedCapacity()).compareTo(sqlPartitionCapacity) <= 0
          && sqlPartitionCapacity.compareTo(baselineCountedCapacity.add(BigInteger.ONE)) >= 0;
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must not be nil");
  }

  private static byte[] nonEmpty(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length == 0) throw new IllegalArgumentException(name + " must not be empty");
    return value.clone();
  }

  private static BigInteger positive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static BigInteger nonNegative(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
    return value;
  }

  private static String digest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be canonical SHA-256");
    }
    return value;
  }

  private static void requireMatches(byte[] value, String expected, String name) {
    if (!CanonicalGameplayIssuerMemberV2Codec.sha256(value).equals(expected)) {
      throw new IllegalArgumentException(name + " digest does not match its exact bytes");
    }
  }
}
