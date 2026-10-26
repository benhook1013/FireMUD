package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed local owner for finite initial issuer bootstrap completion. It is not
 * registered in operational composition and issues no tenant barrier or admission authority.
 */
public final class CanonicalGameplayIssuerBootstrapCoverageOwner {
  private final DSLContext dsl;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner partitionRedisOwner;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentStateSource;

  public CanonicalGameplayIssuerBootstrapCoverageOwner(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner partitionRedisOwner,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          currentStateSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.partitionRedisOwner = Objects.requireNonNull(partitionRedisOwner, "partitionRedisOwner");
    this.currentStateSource = Objects.requireNonNull(currentStateSource, "currentStateSource");
  }

  /**
   * Completes one initial installation or returns the retained result for its exact original retry.
   * An installed result is historical installation evidence, not fresh physical coverage.
   */
  public CanonicalGameplayIssuerInstalledAnchor completeInitialBootstrap(
      UUID bootstrapOperationId, UUID bootstrapRequestId) {
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    requireNoAmbientTransaction();

    CanonicalGameplayIssuerBootstrapCoverageRepository coverageRepository =
        new CanonicalGameplayIssuerBootstrapCoverageRepository(dsl);
    Optional<CanonicalGameplayIssuerInstalledAnchor> retainedInstallation =
        coverageRepository.readInstalledAnchor(
            bootstrapOperationId, bootstrapRequestId, readFreshCurrentState());
    if (retainedInstallation.isPresent()) return retainedInstallation.get();

    CanonicalGameplayIssuerPartitionBuildPlan plan =
        new CanonicalGameplayIssuerPartitionBuildRepository(dsl)
            .prepareInitialBuildPlan(bootstrapOperationId, bootstrapRequestId);
    if (plan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED) {
      throw unavailable("Complete issuer bootstrap plan contains unresolved blockers");
    }
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState initialState =
        readAndRequireCurrentState(plan);
    Optional<CanonicalGameplayIssuerInstalledAnchor> installed =
        coverageRepository.readInstalledAnchor(
            bootstrapOperationId, bootstrapRequestId, initialState);
    if (installed.isPresent()) {
      return installed.get();
    }

    CanonicalGameplayIssuerBootstrapMemberOperationRepository memberOperationRepository =
        new CanonicalGameplayIssuerBootstrapMemberOperationRepository(dsl);
    BigInteger partitionCount = plan.bootstrapIntent().layoutDescriptor().partitionCount();
    for (BigInteger partitionId = BigInteger.ZERO;
        partitionId.compareTo(partitionCount) < 0;
        partitionId = partitionId.add(BigInteger.ONE)) {
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState before =
          readAndRequireSameCurrentState(plan, initialState);
      CanonicalGameplayIssuerBootstrapPartitionWork work =
          memberOperationRepository.preparePartitionWork(
              bootstrapOperationId,
              bootstrapRequestId,
              partitionId,
              trustedCheckpoint(before.accountCheckpoint()),
              trustedIncarnation(before.incarnation()));
      requireSamePlan(plan, work.buildPlan());
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.Readback readback =
          partitionRedisOwner.buildAndRead(work);
      readAndRequireSameCurrentState(plan, initialState);
      coverageRepository.recordPartitionProof(readback);
    }

    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState beforeInstall =
        readAndRequireSameCurrentState(plan, initialState);
    CanonicalGameplayIssuerInstalledAnchor anchor =
        coverageRepository.installInitialAnchor(
            bootstrapOperationId, bootstrapRequestId, beforeInstall);
    return anchor;
  }

  /**
   * Performs a separate read-only physical verification of the original installed baseline. This
   * gate intentionally requires the current inventory to still match that frozen baseline.
   */
  CanonicalGameplayIssuerInstalledAnchor verifyInstalledPhysicalCoverage(
      UUID bootstrapOperationId, UUID bootstrapRequestId) {
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requireNonNil(bootstrapRequestId, "bootstrapRequestId");
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerPartitionBuildPlan plan =
        new CanonicalGameplayIssuerPartitionBuildRepository(dsl)
            .prepareInitialBuildPlan(bootstrapOperationId, bootstrapRequestId);
    if (plan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED) {
      throw unavailable("Complete issuer bootstrap plan contains unresolved blockers");
    }
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState currentState =
        readAndRequireCurrentState(plan);
    CanonicalGameplayIssuerBootstrapCoverageRepository coverageRepository =
        new CanonicalGameplayIssuerBootstrapCoverageRepository(dsl);
    CanonicalGameplayIssuerInstalledAnchor installed =
        coverageRepository
            .readInstalledAnchor(bootstrapOperationId, bootstrapRequestId, currentState)
            .orElseThrow(() -> unavailable("Original issuer bootstrap has no installed anchor"));
    return readInstalledAnchorPartitions(plan, installed, coverageRepository, currentState);
  }

  private CanonicalGameplayIssuerInstalledAnchor readInstalledAnchorPartitions(
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerInstalledAnchor installed,
      CanonicalGameplayIssuerBootstrapCoverageRepository coverageRepository,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState initialState) {
    CanonicalGameplayIssuerBootstrapMemberOperationRepository memberOperationRepository =
        new CanonicalGameplayIssuerBootstrapMemberOperationRepository(dsl);
    BigInteger partitionCount = plan.bootstrapIntent().layoutDescriptor().partitionCount();
    for (BigInteger partitionId = BigInteger.ZERO;
        partitionId.compareTo(partitionCount) < 0;
        partitionId = partitionId.add(BigInteger.ONE)) {
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
          readAndRequireSameCurrentState(plan, initialState);
      CanonicalGameplayIssuerBootstrapPartitionWork work =
          memberOperationRepository.preparePartitionWork(
              plan.bootstrapIntent().operationId(),
              plan.bootstrapIntent().requestId(),
              partitionId,
              trustedCheckpoint(current.accountCheckpoint()),
              trustedIncarnation(current.incarnation()));
      requireSamePlan(plan, work.buildPlan());
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.Readback readback =
          partitionRedisOwner.readExistingAndRead(work);
      readAndRequireSameCurrentState(plan, initialState);
      coverageRepository.verifyExistingPartitionProof(readback);
    }
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState afterReadback =
        readAndRequireSameCurrentState(plan, initialState);
    CanonicalGameplayIssuerInstalledAnchor confirmed =
        coverageRepository
            .readInstalledAnchor(
                installed.bootstrapOperationId(), installed.bootstrapRequestId(), afterReadback)
            .orElseThrow(
                () -> unavailable("Installed issuer anchor disappeared during exact readback"));
    if (!Arrays.equals(installed.exactAnchorBytes(), confirmed.exactAnchorBytes())
        || !installed.exactAnchorDigest().equals(confirmed.exactAnchorDigest())) {
      throw unavailable("Installed issuer anchor changed during physical partition readback");
    }
    return confirmed;
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readAndRequireCurrentState(CanonicalGameplayIssuerPartitionBuildPlan plan) {
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readFreshCurrentState();
    requireCurrentSource(plan, current);
    return current;
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readAndRequireSameCurrentState(
          CanonicalGameplayIssuerPartitionBuildPlan plan,
          CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState expected) {
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current =
        readAndRequireCurrentState(plan);
    if (!Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation()),
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(expected.incarnation()))
        || !sameCheckpoint(current.accountCheckpoint(), expected.accountCheckpoint())) {
      throw unavailable(
          "Current Account source or stable Redis incarnation changed during bootstrap");
    }
    return current;
  }

  private CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState
      readFreshCurrentState() {
    requireNoAmbientTransaction();
    CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current;
    try {
      current = currentStateSource.readFreshCurrentState();
    } catch (RuntimeException unavailable) {
      throw unavailable("Fresh issuer source or stable incarnation is unavailable", unavailable);
    }
    requireNoAmbientTransaction();
    if (current == null) throw unavailable("Fresh issuer source or stable incarnation is missing");
    return current;
  }

  private static void requireCurrentSource(
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current) {
    var expected = plan.bootstrapIntent().sourceCheckpoint();
    if (!sameCheckpoint(current.accountCheckpoint(), expected)) {
      throw unavailable("Current Account checkpoint differs from the frozen bootstrap source");
    }
  }

  private static boolean sameCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint left,
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint right) {
    return left != null
        && right != null
        && left.issuerId().equals(right.issuerId())
        && left.issuerAuthGeneration().equals(right.issuerAuthGeneration())
        && Arrays.equals(left.exactCheckpointBytes(), right.exactCheckpointBytes())
        && left.checkpointDigest().equals(right.checkpointDigest())
        && Arrays.equals(left.latestIssuerEventBytes(), right.latestIssuerEventBytes())
        && Objects.equals(left.latestIssuerEventDigest(), right.latestIssuerEventDigest());
  }

  private static void requireSamePlan(
      CanonicalGameplayIssuerPartitionBuildPlan expected,
      CanonicalGameplayIssuerPartitionBuildPlan actual) {
    if (!expected.buildOperationId().equals(actual.buildOperationId())
        || !expected.planDigest().equals(actual.planDigest())
        || !expected.bootstrapIntent().operationId().equals(actual.bootstrapIntent().operationId())
        || !expected.bootstrapIntent().requestId().equals(actual.bootstrapIntent().requestId())) {
      throw unavailable("Member-operation owner returned work for a different persisted plan");
    }
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      trustedCheckpoint(
          CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint source) {
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
        source.issuerId(),
        source.issuerAuthGeneration(),
        source.exactCheckpointBytes(),
        source.latestIssuerEventBytes());
  }

  private static CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation
      trustedIncarnation(CanonicalGameplayIssuerMemberV2Value.Incarnation incarnation) {
    var deployment = incarnation.targetDeploymentEvidence();
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation(
        incarnation.attestorBinding(),
        incarnation.acceptedAttestationDigest(),
        deployment,
        incarnation.coordOpsRoAclDigest(),
        incarnation.coordRedisConfigObservedDigest());
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must not be nil");
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable(
          "Initial issuer bootstrap completion cannot join an ambient SQL transaction");
    }
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException(message);
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(message, cause);
  }
}
