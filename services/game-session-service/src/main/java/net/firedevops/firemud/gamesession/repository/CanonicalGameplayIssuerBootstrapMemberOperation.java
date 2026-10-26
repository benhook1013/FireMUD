package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/**
 * Immutable SQL-issued identity and exact v2 member value for one initial-bootstrap work item.
 *
 * <p>{@link Status#PENDING_PHYSICAL_PROOF} is preparation for the next physical owner. The local
 * member generation in this record is not an issuer-wide coverage acknowledgement or proof that a
 * Redis member was written.
 */
final class CanonicalGameplayIssuerBootstrapMemberOperation {
  enum Status {
    PENDING_PHYSICAL_PROOF
  }

  private final UUID operationId;
  private final java.math.BigInteger operationFence;
  private final java.math.BigInteger localMemberGeneration;
  private final UUID bootstrapOperationId;
  private final UUID bootstrapRequestId;
  private final UUID buildOperationId;
  private final String buildPlanDigest;
  private final long workOrdinal;
  private final UUID preparationOperationId;
  private final UUID preparationFenceId;
  private final long reservationSourceRowOrdinal;
  private final String reservationSourceRowDigest;
  private final CanonicalGameplayIssuerMemberV2Value memberValue;
  private final CanonicalGameplayBindingInventoryEntry.Lifecycle bindingLifecycle;
  private final boolean expectedBindingPresent;
  private final boolean countsTowardCapacity;
  private final byte[] exactMemberValueBytes;
  private final String memberValueDigest;
  private final Status status;

  CanonicalGameplayIssuerBootstrapMemberOperation(
      UUID operationId,
      java.math.BigInteger operationFence,
      java.math.BigInteger localMemberGeneration,
      UUID bootstrapOperationId,
      UUID bootstrapRequestId,
      UUID buildOperationId,
      String buildPlanDigest,
      long workOrdinal,
      UUID preparationOperationId,
      UUID preparationFenceId,
      long reservationSourceRowOrdinal,
      String reservationSourceRowDigest,
      CanonicalGameplayIssuerMemberV2Value memberValue,
      CanonicalGameplayBindingInventoryEntry.Lifecycle bindingLifecycle,
      boolean expectedBindingPresent,
      boolean countsTowardCapacity,
      byte[] exactMemberValueBytes,
      String memberValueDigest,
      Status status) {
    this.operationId = requireNonNil(operationId, "operationId");
    this.operationFence = requirePositive(operationFence, "operationFence");
    this.localMemberGeneration = requirePositive(localMemberGeneration, "localMemberGeneration");
    this.bootstrapOperationId = requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    this.bootstrapRequestId = requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    this.buildOperationId = requireNonNil(buildOperationId, "buildOperationId");
    this.buildPlanDigest = requireDigest(buildPlanDigest, "buildPlanDigest");
    this.workOrdinal = requireNonNegative(workOrdinal, "workOrdinal");
    this.preparationOperationId = requireNonNil(preparationOperationId, "preparationOperationId");
    this.preparationFenceId = requireNonNil(preparationFenceId, "preparationFenceId");
    this.reservationSourceRowOrdinal =
        requireNonNegative(reservationSourceRowOrdinal, "reservationSourceRowOrdinal");
    this.reservationSourceRowDigest =
        requireDigest(reservationSourceRowDigest, "reservationSourceRowDigest");
    this.memberValue = Objects.requireNonNull(memberValue, "memberValue");
    this.bindingLifecycle = bindingLifecycle;
    this.expectedBindingPresent = expectedBindingPresent;
    this.countsTowardCapacity = countsTowardCapacity;
    this.exactMemberValueBytes =
        Objects.requireNonNull(exactMemberValueBytes, "exactMemberValueBytes").clone();
    if (this.exactMemberValueBytes.length == 0
        || this.exactMemberValueBytes.length
            > CanonicalGameplayIssuerMemberV2Codec.MAX_VALUE_BYTES) {
      throw new IllegalArgumentException(
          "Exact issuer member value bytes are outside the local limit");
    }
    this.memberValueDigest = requireDigest(memberValueDigest, "memberValueDigest");
    this.status = Objects.requireNonNull(status, "status");
    if (bootstrapOperationId.equals(bootstrapRequestId)
        || buildOperationId.equals(bootstrapOperationId)
        || buildOperationId.equals(bootstrapRequestId)
        || operationId.equals(bootstrapOperationId)
        || operationId.equals(bootstrapRequestId)
        || operationId.equals(buildOperationId)
        || operationId.equals(preparationOperationId)
        || operationId.equals(preparationFenceId)
        || localMemberGeneration.compareTo(operationFence) <= 0) {
      throw new IllegalArgumentException(
          "Issued local member identity must be distinct from preparation");
    }
    if (!operationId.equals(memberValue.memberOperation().operationId())
        || !operationFence.equals(memberValue.memberOperation().operationFence())
        || !localMemberGeneration.equals(memberValue.memberOperation().coverageGeneration())
        || !bootstrapOperationId.equals(memberValue.anchor().issuerCoverageOperationId())
        || !Arrays.equals(
            this.exactMemberValueBytes, CanonicalGameplayIssuerMemberV2Codec.encode(memberValue))
        || !memberValueDigest.equals(
            CanonicalGameplayIssuerMemberV2Codec.sha256(this.exactMemberValueBytes))) {
      throw new IllegalArgumentException(
          "Issued operation fields differ from its exact schema-v2 member value");
    }
    CanonicalIssuerPartitionReservation originalReservation = memberValue.reservation();
    boolean lifecycleCounts =
        originalReservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED;
    boolean lifecycleHasBinding =
        bindingLifecycle == CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE
            || bindingLifecycle == CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL;
    if (countsTowardCapacity != lifecycleCounts
        || expectedBindingPresent != lifecycleHasBinding
        || (expectedBindingPresent && bindingLifecycle == null)) {
      throw new IllegalArgumentException(
          "Physical binding and capacity actions must match the frozen SQL source lifecycle");
    }
    CanonicalGameplayIssuerMemberV2Value decoded =
        CanonicalGameplayIssuerMemberV2Codec.decode(this.exactMemberValueBytes);
    if (!memberValue.equals(decoded)) {
      throw new IllegalArgumentException(
          "Exact schema-v2 member bytes decode to a different value");
    }
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

  UUID bootstrapOperationId() {
    return bootstrapOperationId;
  }

  UUID bootstrapRequestId() {
    return bootstrapRequestId;
  }

  UUID buildOperationId() {
    return buildOperationId;
  }

  String buildPlanDigest() {
    return buildPlanDigest;
  }

  long workOrdinal() {
    return workOrdinal;
  }

  UUID preparationOperationId() {
    return preparationOperationId;
  }

  UUID preparationFenceId() {
    return preparationFenceId;
  }

  long reservationSourceRowOrdinal() {
    return reservationSourceRowOrdinal;
  }

  String reservationSourceRowDigest() {
    return reservationSourceRowDigest;
  }

  CanonicalGameplayIssuerMemberV2Value memberValue() {
    return memberValue;
  }

  /** Full closed v2 value the physical owner must compare; not a coverage acknowledgement. */
  CanonicalGameplayIssuerMemberV2Value expectedMemberValue() {
    return memberValue;
  }

  CanonicalGameplayBindingInventoryEntry.Lifecycle bindingLifecycle() {
    return bindingLifecycle;
  }

  boolean expectedBindingPresent() {
    return expectedBindingPresent;
  }

  boolean countsTowardCapacity() {
    return countsTowardCapacity;
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

  private static long requireNonNegative(long value, String name) {
    if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    return value;
  }

  private static String requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be canonical SHA-256");
    }
    return value;
  }
}
