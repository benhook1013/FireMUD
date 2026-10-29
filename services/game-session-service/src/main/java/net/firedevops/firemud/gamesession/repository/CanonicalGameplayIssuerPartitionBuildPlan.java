package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable preparation artifact for building the initial issuer partitions from the exact
 * persisted bootstrap intent and cohort.
 *
 * <p>This plan is not an installed anchor, a physical member write, a coverage acknowledgement, or
 * admission evidence. Its operation IDs and UUID fences identify preparation work only.
 */
final class CanonicalGameplayIssuerPartitionBuildPlan {
  enum State {
    PREPARED,
    BLOCKED
  }

  enum CoverageGenerationState {
    NOT_YET_ISSUED
  }

  enum BlockerCode {
    UNRESOLVED_LEGACY_BINDING,
    UNRESOLVED_LEGACY_RESERVATION,
    UNREPRESENTABLE_BINDING,
    UNREPRESENTABLE_RESERVATION,
    UNREPRESENTABLE_SOURCE,
    MISSING_RESERVATION,
    CONFLICTING_RESERVATION_LINK,
    PARTITION_CAPACITY_EXCEEDED,
    AGGREGATE_CAPACITY_EXCEEDED
  }

  private final UUID buildOperationId;
  private final UUID buildPreparationFenceId;
  private final CanonicalGameplayIssuerLayoutBootstrapIntent bootstrapIntent;
  private final List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows;
  private final List<MemberWork> memberWorks;
  private final List<Blocker> blockers;
  private final String planDigest;

  CanonicalGameplayIssuerPartitionBuildPlan(
      UUID buildOperationId,
      UUID buildPreparationFenceId,
      CanonicalGameplayIssuerLayoutBootstrapIntent bootstrapIntent,
      List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows,
      List<MemberWork> memberWorks,
      List<Blocker> blockers,
      String planDigest) {
    this.buildOperationId = requireNonNil(buildOperationId, "buildOperationId");
    this.buildPreparationFenceId =
        requireNonNil(buildPreparationFenceId, "buildPreparationFenceId");
    this.bootstrapIntent = Objects.requireNonNull(bootstrapIntent, "bootstrapIntent");
    if (buildOperationId.equals(bootstrapIntent.operationId())
        || buildOperationId.equals(buildPreparationFenceId)
        || buildOperationId.equals(bootstrapIntent.requestId())
        || buildPreparationFenceId.equals(bootstrapIntent.operationId())
        || buildPreparationFenceId.equals(bootstrapIntent.requestId())) {
      throw new IllegalArgumentException("Build preparation identity must be separate from intent");
    }
    this.cohortRows = List.copyOf(Objects.requireNonNull(cohortRows, "cohortRows"));
    this.memberWorks = List.copyOf(Objects.requireNonNull(memberWorks, "memberWorks"));
    this.blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
    if (this.cohortRows.stream().anyMatch(Objects::isNull)
        || this.memberWorks.stream().anyMatch(Objects::isNull)
        || this.blockers.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Build plan collections must not contain null");
    }
    if (this.cohortRows.size() != bootstrapIntent.cohortRows().size()) {
      throw new IllegalArgumentException("Build plan must retain the complete bootstrap cohort");
    }
    for (int index = 0; index < this.cohortRows.size(); index++) {
      requireSameCohortRow(this.cohortRows.get(index), bootstrapIntent.cohortRows().get(index));
    }
    for (int index = 0; index < this.memberWorks.size(); index++) {
      MemberWork work = this.memberWorks.get(index);
      if (work.workOrdinal() != index) {
        throw new IllegalArgumentException("Member work ordinals must be contiguous from zero");
      }
      work.requireAnchor(bootstrapIntent);
    }
    for (int index = 0; index < this.blockers.size(); index++) {
      Blocker blocker = this.blockers.get(index);
      if (blocker.blockerOrdinal() != index) {
        throw new IllegalArgumentException("Blocker ordinals must be contiguous from zero");
      }
      if (blocker.sourceRowOrdinal() >= this.cohortRows.size()
          || !blocker
              .sourceRowDigest()
              .equals(this.cohortRows.get((int) blocker.sourceRowOrdinal()).sourceRowDigest())) {
        throw new IllegalArgumentException("Build blocker must link to its exact cohort row");
      }
    }
    this.planDigest = requireDigest(planDigest, "planDigest");
  }

  UUID buildOperationId() {
    return buildOperationId;
  }

  UUID buildPreparationFenceId() {
    return buildPreparationFenceId;
  }

  CanonicalGameplayIssuerLayoutBootstrapIntent bootstrapIntent() {
    return bootstrapIntent;
  }

  List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> cohortRows() {
    return cohortRows;
  }

  List<MemberWork> memberWorks() {
    return memberWorks;
  }

  List<Blocker> blockers() {
    return blockers;
  }

  State state() {
    return blockers.isEmpty() ? State.PREPARED : State.BLOCKED;
  }

  CoverageGenerationState coverageGenerationState() {
    return CoverageGenerationState.NOT_YET_ISSUED;
  }

  /** The pending preparation stage deliberately has no owner-issued coverage generation. */
  BigInteger coverageGeneration() {
    return null;
  }

  String planDigest() {
    return planDigest;
  }

  private static void requireSameCohortRow(
      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow actual,
      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow expected) {
    if (actual.rowOrdinal() != expected.rowOrdinal()
        || actual.kind() != expected.kind()
        || !actual.sourceKey().equals(expected.sourceKey())
        || !actual.sourceInventoryRevision().equals(expected.sourceInventoryRevision())
        || !actual.sourceRowJson().equals(expected.sourceRowJson())
        || !actual.sourceRowDigest().equals(expected.sourceRowDigest())) {
      throw new IllegalArgumentException("Build plan changed a frozen bootstrap cohort row");
    }
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
    return value;
  }

  private static String requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
    return value;
  }

  record MemberWork(
      long workOrdinal,
      UUID memberOperationId,
      UUID memberPreparationFenceId,
      long reservationSourceRowOrdinal,
      String reservationSourceRowDigest,
      Long bindingSourceRowOrdinal,
      String bindingSourceRowDigest,
      UUID reservationId,
      byte[] bindingRef,
      UUID transitionId,
      String reservationLifecycle,
      UUID reservationFence,
      BigInteger originalPartitionId,
      BigInteger originalLayoutVersion,
      BigInteger originalPartitionCount,
      BigInteger originalPartitionCapacity,
      UUID originalIssuerCoverageOperationId,
      BigInteger originalIssuerCoverageOperationFence,
      BigInteger originalCoverageFence,
      BigInteger originalInventorySnapshotRevision,
      BigInteger plannedPartitionId,
      UUID plannedAnchorOperationId,
      BigInteger plannedAnchorOperationFence,
      BigInteger plannedAnchorCoverageFence,
      BigInteger plannedAnchorInventorySnapshotRevision) {
    MemberWork {
      requireNonNegative(workOrdinal, "workOrdinal");
      requireNonNil(memberOperationId, "memberOperationId");
      requireNonNil(memberPreparationFenceId, "memberPreparationFenceId");
      requireNonNegative(reservationSourceRowOrdinal, "reservationSourceRowOrdinal");
      requireDigest(reservationSourceRowDigest, "reservationSourceRowDigest");
      if ((bindingSourceRowOrdinal == null) != (bindingSourceRowDigest == null)) {
        throw new IllegalArgumentException("Binding cohort link must include its exact digest");
      }
      if (bindingSourceRowOrdinal != null) {
        requireNonNegative(bindingSourceRowOrdinal, "bindingSourceRowOrdinal");
        requireDigest(bindingSourceRowDigest, "bindingSourceRowDigest");
      }
      requireNonNil(reservationId, "reservationId");
      bindingRef = Objects.requireNonNull(bindingRef, "bindingRef").clone();
      if (bindingRef.length == 0)
        throw new IllegalArgumentException("bindingRef must not be empty");
      requireNonNil(transitionId, "transitionId");
      Objects.requireNonNull(reservationLifecycle, "reservationLifecycle");
      if (!List.of("RESERVED", "BOUND", "RELEASE_PENDING", "RELEASED")
          .contains(reservationLifecycle)) {
        throw new IllegalArgumentException("Unsupported original reservation lifecycle");
      }
      requireNonNil(reservationFence, "reservationFence");
      requireNonNegative(originalPartitionId, "originalPartitionId");
      requirePositive(originalLayoutVersion, "originalLayoutVersion");
      requirePositive(originalPartitionCount, "originalPartitionCount");
      requirePositive(originalPartitionCapacity, "originalPartitionCapacity");
      if (originalPartitionId.compareTo(originalPartitionCount) >= 0) {
        throw new IllegalArgumentException("Original partition is outside the original layout");
      }
      requireNonNil(originalIssuerCoverageOperationId, "originalIssuerCoverageOperationId");
      requirePositive(originalIssuerCoverageOperationFence, "originalIssuerCoverageOperationFence");
      requirePositive(originalCoverageFence, "originalCoverageFence");
      requirePositive(originalInventorySnapshotRevision, "originalInventorySnapshotRevision");
      requireNonNegative(plannedPartitionId, "plannedPartitionId");
      requireNonNil(plannedAnchorOperationId, "plannedAnchorOperationId");
      requirePositive(plannedAnchorOperationFence, "plannedAnchorOperationFence");
      requirePositive(plannedAnchorCoverageFence, "plannedAnchorCoverageFence");
      requirePositive(
          plannedAnchorInventorySnapshotRevision, "plannedAnchorInventorySnapshotRevision");
    }

    @Override
    public byte[] bindingRef() {
      return bindingRef.clone();
    }

    boolean countsTowardCapacity() {
      return !reservationLifecycle.equals("RELEASED");
    }

    private void requireAnchor(CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
      if (!plannedAnchorOperationId.equals(intent.operationId())
          || !plannedAnchorOperationFence.equals(intent.operationFence())
          || !plannedAnchorCoverageFence.equals(intent.coverageFence())
          || !plannedAnchorInventorySnapshotRevision.equals(intent.inventorySnapshotRevision())
          || plannedPartitionId.compareTo(intent.layoutDescriptor().partitionCount()) >= 0) {
        throw new IllegalArgumentException(
            "Planned reservation work differs from the exact pending bootstrap layout tuple");
      }
      if (memberOperationId.equals(transitionId)
          || memberOperationId.equals(memberPreparationFenceId)
          || memberPreparationFenceId.equals(reservationFence)) {
        throw new IllegalArgumentException(
            "Member preparation identity must remain separate from reservation provenance");
      }
    }

    private static void requireNonNegative(long value, String name) {
      if (value < 0L) throw new IllegalArgumentException(name + " must be non-negative");
    }

    private static void requireNonNegative(BigInteger value, String name) {
      Objects.requireNonNull(value, name);
      if (value.signum() < 0) throw new IllegalArgumentException(name + " must be non-negative");
    }

    private static void requirePositive(BigInteger value, String name) {
      Objects.requireNonNull(value, name);
      if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
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

  record Blocker(
      long blockerOrdinal, long sourceRowOrdinal, String sourceRowDigest, BlockerCode code) {
    Blocker {
      if (blockerOrdinal < 0L || sourceRowOrdinal < 0L) {
        throw new IllegalArgumentException("Blocker ordinals must be non-negative");
      }
      requireDigest(sourceRowDigest, "sourceRowDigest");
      Objects.requireNonNull(code, "code");
    }
  }
}
