package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/** SQL-issued exact terminal successor for one retained first-NEW issuer reservation. */
public final class CanonicalGameplayIssuerBindingLocalReleaseOperation {
  public enum Status {
    PENDING_PHYSICAL_PROOF,
    RELEASED
  }

  private final CanonicalGameplayAdmissionAbortSource abortSource;
  private final CanonicalGameplayIssuerBindingLocalOperation originalOperation;
  private final UUID operationId;
  private final BigInteger operationFence;
  private final BigInteger localMemberGeneration;
  private final BigInteger sourceInventoryRevision;
  private final BigInteger completionInventoryRevision;
  private final CanonicalGameplayIssuerMemberV2Value releasedMemberValue;
  private final byte[] exactOriginalMemberValueBytes;
  private final byte[] exactReleasedMemberValueBytes;
  private final byte[] exactPhysicalReadbackFieldSetBytes;
  private final String physicalReadbackDigest;
  private final Status status;

  CanonicalGameplayIssuerBindingLocalReleaseOperation(
      CanonicalGameplayAdmissionAbortSource abortSource,
      CanonicalGameplayIssuerBindingLocalOperation originalOperation,
      UUID operationId,
      BigInteger operationFence,
      BigInteger localMemberGeneration,
      BigInteger sourceInventoryRevision,
      BigInteger completionInventoryRevision,
      CanonicalGameplayIssuerMemberV2Value releasedMemberValue,
      byte[] exactOriginalMemberValueBytes,
      byte[] exactReleasedMemberValueBytes,
      byte[] exactPhysicalReadbackFieldSetBytes,
      String physicalReadbackDigest,
      Status status) {
    this.abortSource = Objects.requireNonNull(abortSource, "Account-aborted source required");
    this.originalOperation = Objects.requireNonNull(originalOperation, "Original SQL operation required");
    this.operationId = requireNonNil(operationId, "operationId");
    this.operationFence = requirePositive(operationFence, "operationFence");
    this.localMemberGeneration = requirePositive(localMemberGeneration, "localMemberGeneration");
    this.sourceInventoryRevision = requirePositive(sourceInventoryRevision, "sourceInventoryRevision");
    this.completionInventoryRevision =
        completionInventoryRevision == null
            ? null
            : requirePositive(completionInventoryRevision, "completionInventoryRevision");
    this.releasedMemberValue = Objects.requireNonNull(releasedMemberValue, "releasedMemberValue");
    this.exactOriginalMemberValueBytes = copyBounded(exactOriginalMemberValueBytes, "original bytes");
    this.exactReleasedMemberValueBytes = copyBounded(exactReleasedMemberValueBytes, "released bytes");
    this.exactPhysicalReadbackFieldSetBytes =
        exactPhysicalReadbackFieldSetBytes == null
            ? null
            : exactPhysicalReadbackFieldSetBytes.clone();
    this.physicalReadbackDigest = physicalReadbackDigest;
    this.status = Objects.requireNonNull(status, "status");

    CanonicalGameplayIssuerMemberV2Value original = originalOperation.memberValue();
    CanonicalIssuerPartitionReservation sourceReservation = abortSource.reservation();
    CanonicalIssuerPartitionReservation releasedReservation = releasedMemberValue.reservation();
    CanonicalGameplayIssuerMemberV2Value expectedReleased =
        new CanonicalGameplayIssuerMemberV2Value(
            original.owner(),
            original.bindingGeneration(),
            new CanonicalIssuerPartitionReservation(
                original.reservation().reservationId(),
                original.reservation().owner(),
                original.reservation().bindingGeneration(),
                original.reservation().transitionId(),
                original.reservation().partitionId(),
                CanonicalIssuerPartitionReservation.Lifecycle.RELEASED,
                original.reservation().reservationFence(),
                original.reservation().issuerCoverageOperationId(),
                original.reservation().issuerCoverageOperationFence(),
                original.reservation().coverageFence(),
                original.reservation().inventorySnapshotRevision(),
                sourceInventoryRevision),
            original.anchor(),
            new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
                operationId, operationFence, localMemberGeneration),
            original.issuerControlEpoch(),
            original.layoutDescriptor(),
            original.sourceCheckpoint(),
            original.incarnation());

    if (!abortSource.isAccountAborted()
        || abortSource.transition().status()
            != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED
        || abortSource.candidate().lifecycle()
            != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED
        || !abortSource.transition().transitionId().equals(originalOperation.request().transitionId())
        || !sourceReservation.reservationId().equals(original.reservation().reservationId())
        || !sourceReservation.transitionId().equals(original.reservation().transitionId())
        || !sourceReservation.owner().equals(original.owner())
        || !sourceReservation.bindingGeneration().equals(original.bindingGeneration())
        || !sourceReservation.reservationFence().equals(original.reservation().reservationFence())
        || !sourceReservation.issuerCoverageOperationId().equals(original.reservation().issuerCoverageOperationId())
        || !sourceReservation.issuerCoverageOperationFence().equals(original.reservation().issuerCoverageOperationFence())
        || !sourceReservation.coverageFence().equals(original.reservation().coverageFence())
        || !sourceReservation.inventorySnapshotRevision().equals(original.reservation().inventorySnapshotRevision())
        || (status == Status.PENDING_PHYSICAL_PROOF
            && (sourceReservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
                || !sourceReservation.inventoryRevision().equals(sourceInventoryRevision)
                || this.completionInventoryRevision != null))
        || (status == Status.RELEASED
            && (sourceReservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED
                || this.completionInventoryRevision == null
                || !sourceReservation.inventoryRevision().equals(this.completionInventoryRevision)
                || this.completionInventoryRevision.compareTo(sourceInventoryRevision) <= 0))
        || !operationId.equals(releasedMemberValue.memberOperation().operationId())
        || !operationFence.equals(releasedMemberValue.memberOperation().operationFence())
        || !localMemberGeneration.equals(releasedMemberValue.memberOperation().coverageGeneration())
        || !expectedReleased.equals(releasedMemberValue)
        || !Arrays.equals(
            this.exactOriginalMemberValueBytes,
            originalOperation.exactMemberValueBytes())
        || !Arrays.equals(
            this.exactOriginalMemberValueBytes,
            CanonicalGameplayIssuerMemberV2Codec.encode(original))
        || !Arrays.equals(
            this.exactReleasedMemberValueBytes,
            CanonicalGameplayIssuerMemberV2Codec.encode(expectedReleased))
        || operationId.equals(originalOperation.operationId())
        || operationFence.compareTo(originalOperation.operationFence()) <= 0
        || localMemberGeneration.compareTo(originalOperation.localMemberGeneration()) <= 0
        || !operationFence.add(BigInteger.ONE).equals(localMemberGeneration)) {
      throw new IllegalArgumentException("Release operation differs from its exact Account-aborted source");
    }

    if (status == Status.PENDING_PHYSICAL_PROOF) {
      if (this.exactPhysicalReadbackFieldSetBytes != null || physicalReadbackDigest != null) {
        throw new IllegalArgumentException("Pending release cannot contain physical completion proof");
      }
    } else {
      if (this.exactPhysicalReadbackFieldSetBytes == null
          || this.exactPhysicalReadbackFieldSetBytes.length == 0
          || this.exactPhysicalReadbackFieldSetBytes.length > 17_825_792
          || physicalReadbackDigest == null
          || !physicalReadbackDigest.equals(
              CanonicalGameplayIssuerMemberV2Codec.sha256(this.exactPhysicalReadbackFieldSetBytes))) {
        throw new IllegalArgumentException("Released operation lacks its exact physical readback proof");
      }
      Map<String, byte[]> readback =
          CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
              this.exactPhysicalReadbackFieldSetBytes);
      String capacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              releasedReservation.reservationId());
      String bindingField =
          new String(releasedMemberValue.owner().bindingRef().bytes(), StandardCharsets.UTF_8);
      if (!Arrays.equals(readback.get(capacityField), this.exactReleasedMemberValueBytes)
          || readback.containsKey(bindingField)) {
        throw new IllegalArgumentException("Released proof does not contain its exact terminal capacity postimage");
      }
    }
  }

  public CanonicalGameplayAdmissionAbortSource abortSource() {
    return abortSource;
  }

  CanonicalGameplayIssuerBindingLocalOperation originalOperation() {
    return originalOperation;
  }

  public UUID originalOperationId() {
    return originalOperation.operationId();
  }

  public UUID operationId() {
    return operationId;
  }

  public BigInteger operationFence() {
    return operationFence;
  }

  public BigInteger localMemberGeneration() {
    return localMemberGeneration;
  }

  public BigInteger sourceInventoryRevision() {
    return sourceInventoryRevision;
  }

  public BigInteger completionInventoryRevision() {
    return completionInventoryRevision;
  }

  public CanonicalGameplayIssuerMemberV2Value releasedMemberValue() {
    return releasedMemberValue;
  }

  public byte[] exactOriginalMemberValueBytes() {
    return exactOriginalMemberValueBytes.clone();
  }

  public byte[] exactReleasedMemberValueBytes() {
    return exactReleasedMemberValueBytes.clone();
  }

  public byte[] exactPhysicalReadbackFieldSetBytes() {
    return exactPhysicalReadbackFieldSetBytes == null
        ? null
        : exactPhysicalReadbackFieldSetBytes.clone();
  }

  public String physicalReadbackDigest() {
    return physicalReadbackDigest;
  }

  public Status status() {
    return status;
  }

  private static byte[] copyBounded(byte[] value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length == 0 || value.length > CanonicalGameplayIssuerMemberV2Codec.MAX_VALUE_BYTES) {
      throw new IllegalArgumentException("Exact " + name + " are outside the schema-v2 bound");
    }
    return value.clone();
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) throw new IllegalArgumentException(name + " must not be nil");
    return value;
  }

  private static BigInteger requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }
}
