package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only output of the local SQL owner for one exact partition in a prepared initial bootstrap.
 *
 * <p>Its members are SQL-issued operations still pending physical proof. An empty partition has an
 * empty member list and does not receive a fabricated member generation.
 */
final class CanonicalGameplayIssuerBootstrapPartitionWork {
  private final CanonicalGameplayIssuerPartitionBuildPlan buildPlan;
  private final BigInteger partitionId;
  private final UUID initialPhysicalRevision;
  private final List<CanonicalGameplayIssuerBootstrapMemberOperation> memberOperations;
  private final CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation;

  CanonicalGameplayIssuerBootstrapPartitionWork(
      CanonicalGameplayIssuerPartitionBuildPlan buildPlan,
      BigInteger partitionId,
      UUID initialPhysicalRevision,
      List<CanonicalGameplayIssuerBootstrapMemberOperation> memberOperations,
      CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    this.buildPlan = Objects.requireNonNull(buildPlan, "buildPlan");
    if (buildPlan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED) {
      throw new IllegalArgumentException("Partition work requires the exact PREPARED build plan");
    }
    this.partitionId = Objects.requireNonNull(partitionId, "partitionId");
    this.initialPhysicalRevision =
        Objects.requireNonNull(initialPhysicalRevision, "initialPhysicalRevision");
    if (initialPhysicalRevision.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("initialPhysicalRevision must not be nil");
    }
    if (partitionId.signum() < 0
        || partitionId.compareTo(buildPlan.bootstrapIntent().layoutDescriptor().partitionCount())
            >= 0) {
      throw new IllegalArgumentException("partitionId is outside the exact bootstrap layout");
    }
    this.memberOperations =
        List.copyOf(Objects.requireNonNull(memberOperations, "memberOperations"));
    if (this.memberOperations.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("memberOperations must not contain null");
    }
    this.incarnation = Objects.requireNonNull(incarnation, "incarnation");
    List<CanonicalGameplayIssuerPartitionBuildPlan.MemberWork> expectedWork =
        buildPlan.memberWorks().stream()
            .filter(work -> work.plannedPartitionId().equals(partitionId))
            .toList();
    if (expectedWork.size() != this.memberOperations.size()) {
      throw new IllegalArgumentException(
          "Partition work must contain every exact prepared member operation for its partition");
    }
    for (int index = 0; index < expectedWork.size(); index++) {
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork work = expectedWork.get(index);
      CanonicalGameplayIssuerBootstrapMemberOperation operation = this.memberOperations.get(index);
      CanonicalGameplayIssuerMemberV2Value value = operation.expectedMemberValue();
      if (operation.status()
              != CanonicalGameplayIssuerBootstrapMemberOperation.Status.PENDING_PHYSICAL_PROOF
          || operation.workOrdinal() != work.workOrdinal()
          || !operation.buildOperationId().equals(buildPlan.buildOperationId())
          || !operation.buildPlanDigest().equals(buildPlan.planDigest())
          || !operation.preparationOperationId().equals(work.memberOperationId())
          || !operation.preparationFenceId().equals(work.memberPreparationFenceId())
          || operation.reservationSourceRowOrdinal() != work.reservationSourceRowOrdinal()
          || !operation.reservationSourceRowDigest().equals(work.reservationSourceRowDigest())
          || !operation.bootstrapOperationId().equals(buildPlan.bootstrapIntent().operationId())
          || !operation.bootstrapRequestId().equals(buildPlan.bootstrapIntent().requestId())
          || !value.reservation().reservationId().equals(work.reservationId())
          || !value.reservation().transitionId().equals(work.transitionId())
          || !value.reservation().partitionId().equals(partitionId)
          || !value
              .anchor()
              .issuerCoverageOperationId()
              .equals(buildPlan.bootstrapIntent().operationId())
          || !value.anchor().operationFence().equals(buildPlan.bootstrapIntent().operationFence())
          || !value.anchor().coverageFence().equals(buildPlan.bootstrapIntent().coverageFence())
          || !value
              .anchor()
              .issuerInventorySnapshotRevision()
              .equals(buildPlan.bootstrapIntent().inventorySnapshotRevision())
          || !incarnation.equals(value.incarnation())) {
        throw new IllegalArgumentException(
            "Partition operation differs from its exact planned source or incarnation");
      }
    }
  }

  CanonicalGameplayIssuerPartitionBuildPlan buildPlan() {
    return buildPlan;
  }

  BigInteger partitionId() {
    return partitionId;
  }

  UUID initialPhysicalRevision() {
    return initialPhysicalRevision;
  }

  List<CanonicalGameplayIssuerBootstrapMemberOperation> memberOperations() {
    return memberOperations;
  }

  CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation() {
    return incarnation;
  }
}
