package net.firedevops.firemud.gamesession.repository;

import io.grpc.Context;
import io.grpc.Deadline;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayActorOrigins;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed owner for an authenticated initial-placement region assignment. It installs
 * region ownership and supplies only same-installation, same-grant capabilities to the selected
 * first-NEW session-binding bridge.
 */
final class CanonicalGameplayRegionLeaseOwner {
  private final CanonicalGameplayBindingInventoryRepository bindingInventory;
  private final CanonicalGameplayRegionRuntimeStatusRepository statusRepository;
  private final CanonicalGameplayRegionLeaseRedisOwner redisOwner;
  private final CanonicalGameplayRegionIncarnationSource incarnationSource;
  private final CanonicalInitialPlayerLocationRegionResolver worldRegionResolver;
  private final RuntimeIdentity runtimeIdentity;
  private final Duration leaseTtl;
  private final Clock clock;
  private final boolean deadlineBoundInstallation;
  // Reserve before claiming Redis creation permission: never generate a proof we cannot retain.
  private final java.util.Map<UUID, RetainedNoWriteRecovery> noWriteRecoveries =
      new java.util.HashMap<>();
  private static final int MAX_NO_WRITE_RECOVERIES = 64;

  static final class RetainedNoWriteRecovery {
    private boolean inUse;
    CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof proof;
  }

  RetainedNoWriteRecovery reserveNoWriteRecovery(UUID operation) {
    synchronized (noWriteRecoveries) {
      var retained = noWriteRecoveries.get(operation);
      if (retained == null) {
        if (noWriteRecoveries.size() >= MAX_NO_WRITE_RECOVERIES) throw unavailable();
        retained = new RetainedNoWriteRecovery();
        noWriteRecoveries.put(operation, retained);
      }
      if (retained.inUse) throw unavailable();
      retained.inUse = true;
      return retained;
    }
  }

  void releaseNoWriteRecovery(UUID operation, RetainedNoWriteRecovery retained) {
    synchronized (noWriteRecoveries) {
      retained.inUse = false;
      if (retained.proof == null) {
        noWriteRecoveries.remove(operation, retained);
      }
    }
  }

  void recoverNoWrite(
      CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation,
      RetainedNoWriteRecovery retained) {
    if (retained.proof == null) return;
    io.grpc.Context previous = io.grpc.Context.ROOT.attach();
    try {
      CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
          Clock.systemUTC(),
          Long.MAX_VALUE,
          cleanup -> {
            this.statusRepository
                .forBudget(cleanup)
                .releaseInitialTimelineNoWrite(preparation, retained.proof, clock.instant());
            return null;
          });
      retained.proof = null;
    } finally {
      io.grpc.Context.ROOT.detach(previous);
    }
  }

  CanonicalGameplayRegionLeaseOwner(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock) {
    this(
        bindingInventory,
        statusRepository,
        redisOwner,
        incarnationSource,
        worldRegionResolver,
        runtimeIdentity,
        leaseTtl,
        clock,
        false);
  }

  CanonicalGameplayRegionLeaseOwner(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock,
      boolean deadlineBoundInstallation) {
    this.deadlineBoundInstallation = deadlineBoundInstallation;
    this.bindingInventory = Objects.requireNonNull(bindingInventory, "bindingInventory");
    this.statusRepository = Objects.requireNonNull(statusRepository, "statusRepository");
    this.redisOwner = Objects.requireNonNull(redisOwner, "redisOwner");
    this.incarnationSource = Objects.requireNonNull(incarnationSource, "incarnationSource");
    this.worldRegionResolver = Objects.requireNonNull(worldRegionResolver, "worldRegionResolver");
    this.runtimeIdentity = Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
    this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
    if (leaseTtl.isZero() || leaseTtl.isNegative()) {
      throw new IllegalArgumentException("leaseTtl must be positive");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Installs a new per-region durable executor fence under a live Redis grant. No bridge ACK or
   * admission capability is returned by this operation.
   */
  Installation installForTransition(
      UUID transitionId, CanonicalInitialPlayerLocationRequest worldRequest) {
    return installForTransition(null, transitionId, worldRequest);
  }

  /** Installs another binding under a still-current private region grant. */
  Installation installForTransition(
      Installation existingRegionInstallation,
      UUID transitionId,
      CanonicalInitialPlayerLocationRequest worldRequest) {
    return installForTransition(
        existingRegionInstallation, transitionId, worldRequest, null, Long.MAX_VALUE);
  }

  /**
   * Explicit normal handoff, using actual acquire/install/revalidation and transfer-purpose proof.
   */
  Installation installForTransition(
      Installation existingRegionInstallation,
      UUID transitionId,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long absoluteDeadlineMillis) {
    requireNoAmbientTransaction();
    if (!deadlineBoundInstallation || transferOwner != null) {
      return installForTransition(
          existingRegionInstallation,
          transitionId,
          worldRequest,
          transferOwner,
          absoluteDeadlineMillis,
          bindingInventory,
          statusRepository,
          redisOwner);
    }
    return CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
        clock,
        absoluteDeadlineMillis,
        budget ->
            installForTransition(
                existingRegionInstallation,
                transitionId,
                worldRequest,
                null,
                budget.absoluteDeadlineMillis(),
                bindingInventory.forBudget(budget),
                statusRepository.forBudget(budget),
                redisOwner.forInstallation(clock, budget.absoluteDeadlineMillis())));
  }

  private Installation installForTransition(
      Installation existingRegionInstallation,
      UUID transitionId,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long absoluteDeadlineMillis,
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(transitionId, "transitionId");
    Objects.requireNonNull(worldRequest, "worldRequest");
    // Freeze the existing five-second transfer budget once, not once per inventory read.
    long startedAt = clock.millis();
    long requestedDeadline = absoluteDeadlineMillis;
    if (transferOwner != null || deadlineBoundInstallation) {
      if (Context.current().isCancelled()) throw unavailable();
      if (Context.current().getDeadline() != null) {
        long remaining = Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
        if (remaining <= 0) throw unavailable();
        requestedDeadline =
            Math.min(requestedDeadline, Math.addExact(startedAt, Math.min(5000L, remaining)));
      }
    }
    final long handoffDeadline =
        transferOwner == null && !deadlineBoundInstallation
            ? requestedDeadline
            : Math.min(requestedDeadline, Math.addExact(startedAt, 5_000L));
    Deadline worldDeadline = worldDeadline(startedAt, handoffDeadline);
    // A prior advance may have retained the positive SQL result before a later observation
    // failed. Recover only its captured original, never synthetic provisional authority.
    var frozenOriginal =
        transferOwner != null && existingRegionInstallation == null
            ? transferOwner.frozenOriginal(transitionId, handoffDeadline)
            : null;
    var transferTopologyCandidate =
        frozenOriginal == null
            ? null
            : readInstallationInventory(
                    bindingInventory,
                    transferOwner,
                    handoffDeadline,
                    inventory ->
                        inventory.readRegionTransferAbortedCandidate(
                            transitionId, frozenOriginal.candidate()))
                .orElse(null);
    var retainedOriginal =
        transferOwner == null || existingRegionInstallation != null
            ? null
            : frozenOriginal == null
                ? readInstallationInventory(
                        bindingInventory,
                        transferOwner,
                        handoffDeadline,
                        inventory -> inventory.readRegionTransferCommittedSource(transitionId))
                    .orElse(null)
                : transferTopologyCandidate != null
                    ? null
                    : readInstallationInventory(
                            bindingInventory,
                            transferOwner,
                            handoffDeadline,
                            inventory ->
                                inventory.readRegionTransferCommittedSource(
                                    transitionId, frozenOriginal.candidate()))
                        .orElse(null);
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        retainedOriginal != null || transferTopologyCandidate != null
            ? null
            : readInstallationInventory(
                bindingInventory,
                transferOwner,
                handoffDeadline,
                inventory -> readCurrentProvisionalEvidence(inventory, transitionId));
    ResolvedBridgeSource initialResolved =
        retainedOriginal != null
            ? resolveExactBridgeSource(
                worldRequest, retainedOriginal.candidate().bindingIdentity(), worldDeadline)
            : transferTopologyCandidate != null
                ? resolveExactBridgeSource(
                    worldRequest, transferTopologyCandidate.bindingIdentity(), worldDeadline)
                : resolveExactBridgeSource(
                    worldRequest, evidence.candidate().identity(), worldDeadline);
    CanonicalGameplayRegionRuntimeStatusRepository.Source source = initialResolved.source;
    CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation =
        readInstallationIncarnation(transferOwner, handoffDeadline, source);
    long expectedRegionEpoch =
        exactExpectedRegionEpoch(
            retainedOriginal != null
                ? retainedOriginal.candidate().bindingIdentity().regionEpoch()
                : transferTopologyCandidate != null
                    ? transferTopologyCandidate.bindingIdentity().regionEpoch()
                    : evidence.candidate().identity().regionEpoch());
    CanonicalGameplayRegionRuntimeStatus prior =
        withTransferStatusRepository(
                statusRepository,
                transferOwner,
                handoffDeadline,
                repository -> repository.find(source))
            .orElse(null);
    // A committed source is topology evidence only. It cannot create an initial timeline or
    // establish a region owner where no predecessor owner exists.
    if (retainedOriginal != null && prior == null) throw unavailable();
    if (prior == null && expectedRegionEpoch != 1L) throw unavailable();
    if (prior != null && prior.regionEpoch() != expectedRegionEpoch) throw unavailable();
    if (retainedOriginal != null && frozenOriginal == null) {
      transferOwner.requireRetainedCommittedOriginal(retainedOriginal, prior, handoffDeadline);
    }
    if (frozenOriginal != null) {
      if (prior == null || retainedOriginal != null && retainedOriginal.finalReceipt().isEmpty()) {
        throw unavailable();
      }
      transferOwner.requireFrozenOriginal(frozenOriginal, source, prior, handoffDeadline);
    }
    CanonicalGameplayRegionLeaseRedisOwner handoffRedisOwner =
        transferOwner == null && !deadlineBoundInstallation
            ? redisOwner
            : redisOwner.forHandoffDeadline(clock, handoffDeadline);
    UUID transferId = null;
    CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource transferSource =
        retainedOriginal;
    CanonicalGameplayOriginalNewCandidate transferCandidate =
        retainedOriginal != null ? retainedOriginal.candidate() : transferTopologyCandidate;

    CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation;
    CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant;
    boolean reusingRegionGrant = false;
    boolean recoveringInitialGrant = false;
    if (prior == null) {
      if (existingRegionInstallation != null) throw unavailable();
      preparation =
          withTransferStatusRepository(
                  statusRepository,
                  transferOwner,
                  handoffDeadline,
                  repository -> repository.findTimelinePreparation(source))
              .orElse(null);
      if (preparation == null) {
        CanonicalGameplayRegionLeaseRedisOwner.TimelineAbsenceProof absenceProof =
            handoffRedisOwner.requireTimelineAbsent(source, incarnation);
        preparation =
            withTransferStatusRepository(
                statusRepository,
                transferOwner,
                handoffDeadline,
                repository ->
                    repository.prepareInitialTimeline(
                        evidence,
                        worldRequest,
                        initialResolved.resolvedRegion,
                        source,
                        incarnation,
                        absenceProof,
                        clock.instant()));
      }
      if (!statusRepository.matchesTimelinePreparation(
              preparation,
              evidence,
              worldRequest,
              initialResolved.resolvedRegion,
              source,
              incarnation)
          || preparation.phase()
              == CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparationPhase
                  .INSTALLED) {
        throw unavailable();
      }
      try (CanonicalGameplayRegionLeaseRedisOwner.PreparedTimelineInitialization session =
          handoffRedisOwner.prepareTimelineInitialization(preparation, leaseTtl, runtimeIdentity)) {
        var retained = reserveNoWriteRecovery(preparation.operationId());
        try {
          recoverNoWrite(preparation, retained);
          var initializationPreparation = preparation;
          Optional<CanonicalGameplayRegionRuntimeStatusRepository.InitialInitializationPermit>
              permit =
                  session.runWithinRetainedPermit(
                      budget -> {
                        CanonicalGameplayBindingProvisionalCasEvidence beforeClaimEvidence =
                            readInstallationInventory(
                                bindingInventory,
                                transferOwner,
                                handoffDeadline,
                                budget,
                                inventory ->
                                    readCurrentProvisionalEvidence(inventory, transitionId));
                        bindingInventory.requireSameProvisionalSource(
                            evidence, beforeClaimEvidence);
                        ResolvedBridgeSource beforeClaimWorld =
                            resolveExactBridgeSource(
                                worldRequest,
                                beforeClaimEvidence.candidate().identity(),
                                worldDeadline);
                        requireSameSource(source, beforeClaimWorld.source);
                        CanonicalGameplayRegionIncarnationSource.StableEvidence
                            beforeClaimIncarnation = readInstallationIncarnation(budget, source);
                        requireSameIncarnation(incarnation, beforeClaimIncarnation);
                        if (!statusRepository.matchesTimelinePreparation(
                            initializationPreparation,
                            beforeClaimEvidence,
                            worldRequest,
                            beforeClaimWorld.resolvedRegion,
                            source,
                            beforeClaimIncarnation)) {
                          throw unavailable();
                        }
                        return withTransferStatusRepository(
                            statusRepository,
                            transferOwner,
                            handoffDeadline,
                            budget,
                            repository ->
                                repository.beginInitialTimeline(
                                    initializationPreparation, clock.instant()));
                      });
          var claimedPreparation = preparation;
          grant =
              session.initialize(
                  permit.orElse(null),
                  proof -> {
                    retained.proof = proof;
                    recoverNoWrite(claimedPreparation, retained);
                  });
        } finally {
          releaseNoWriteRecovery(preparation.operationId(), retained);
        }
      }
      handoffRedisOwner.verifyInitializedTimeline(grant, preparation);
      requireSameIncarnation(
          incarnation, readInstallationIncarnation(transferOwner, handoffDeadline, source));
      ResolvedBridgeSource afterInitWorld =
          resolveExactBridgeSource(worldRequest, evidence.candidate().identity(), worldDeadline);
      requireSameSource(source, afterInitWorld.source);
      bindingInventory.requireSameProvisionalSource(
          evidence,
          readInstallationInventory(
              bindingInventory,
              transferOwner,
              handoffDeadline,
              inventory -> readCurrentProvisionalEvidence(inventory, transitionId)));
      var redisInitializedPreparation = preparation;
      preparation =
          withTransferStatusRepository(
              statusRepository,
              transferOwner,
              handoffDeadline,
              repository ->
                  repository.markRedisInitialized(
                      redisInitializedPreparation,
                      grant.tokenCorrelation(),
                      CanonicalGameplayRegionBindingBridgeProofs.initialMetaDigest(
                          redisInitializedPreparation.tenantRegionTag(),
                          redisInitializedPreparation.operationId(),
                          redisInitializedPreparation.sourceDigest()),
                      clock.instant()));
    } else {
      preparation =
          withTransferStatusRepository(
                  statusRepository,
                  transferOwner,
                  handoffDeadline,
                  repository -> repository.findTimelinePreparation(source))
              .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
      boolean sameInitializationAttempt =
          evidence != null && preparation.transitionId().equals(transitionId);
      boolean matchesInitialization =
          sameInitializationAttempt
              ? statusRepository.matchesTimelinePreparation(
                  preparation,
                  evidence,
                  worldRequest,
                  initialResolved.resolvedRegion,
                  source,
                  incarnation)
              : statusRepository.matchesInstalledTimelineInitialization(
                  preparation, source, incarnation);
      boolean resumablePhase =
          sameInitializationAttempt
              ? preparation.phase()
                      == CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparationPhase
                          .INSTALLED
                  || preparation.phase()
                      == CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparationPhase
                          .REDIS_INITIALIZED
              : preparation.phase()
                  == CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparationPhase
                      .INSTALLED;
      if (!matchesInitialization || !resumablePhase) {
        throw unavailable();
      }
      if (existingRegionInstallation == null
          && sameInitializationAttempt
          && expectedRegionEpoch == 1L
          && (transferOwner == null
              || preparation.phase()
                  == CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparationPhase
                      .REDIS_INITIALIZED)) {
        grant = handoffRedisOwner.readRetainedInitialGrant(preparation, prior, runtimeIdentity);
        handoffRedisOwner.requireCurrent(grant);
        handoffRedisOwner.verifyInitializedTimeline(grant, preparation);
        if (!prior.equals(
            withTransferStatusRepository(
                    statusRepository,
                    transferOwner,
                    handoffDeadline,
                    repository -> repository.find(source))
                .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable))) {
          throw unavailable();
        }
        recoveringInitialGrant = true;
      } else if (existingRegionInstallation == null) {
        // Compatibility calls may create the first owner or recover its exact retained live grant,
        // but replacing an installed owner requires the explicit frozen transfer protocol.
        if (transferOwner == null) throw unavailable();
        // A stable instance ID can survive a restart. The fresh-token acquire establishes
        // scoped lease absence atomically and refuses every live incumbent or competing grant.
        grant = handoffRedisOwner.acquire(source, expectedRegionEpoch, leaseTtl, runtimeIdentity);
        redisOwner.requireGrantScope(grant, source, expectedRegionEpoch, runtimeIdentity);
        requireSameSource(
            source,
            resolveCurrentInstallationSource(
                bindingInventory,
                worldRequest,
                evidence,
                transferSource,
                transferTopologyCandidate,
                transferOwner,
                handoffDeadline,
                worldDeadline));
        requireSameIncarnation(
            incarnation, readInstallationIncarnation(transferOwner, handoffDeadline, source));
        handoffRedisOwner.verifyInitializedTimeline(grant, preparation);
        if (transferCandidate == null) {
          transferCandidate =
              readInstallationInventory(
                  bindingInventory,
                  transferOwner,
                  handoffDeadline,
                  inventory ->
                      inventory.readRegionTransferOriginalCandidate(transitionId, evidence));
        }
        CanonicalGameplayOriginalNewCandidate pinnedTransferCandidate = transferCandidate;
        handoffRedisOwner.requireCurrent(grant);
        transferId =
            frozenOriginal == null
                ? transferOwner.advance(source, prior, handoffDeadline)
                : transferOwner.resume(frozenOriginal, source, prior, handoffDeadline);
        // Advance may retain a COMMITTED winner and activate its SQL inventory row. Keep that
        // exact terminal source separate from provisional binding/bridge authority.
        transferTopologyCandidate =
            readInstallationInventory(
                    bindingInventory,
                    transferOwner,
                    handoffDeadline,
                    inventory ->
                        inventory.readRegionTransferAbortedCandidate(
                            transitionId, pinnedTransferCandidate))
                .orElse(null);
        transferSource =
            transferTopologyCandidate != null
                ? null
                : readInstallationInventory(
                        bindingInventory,
                        transferOwner,
                        handoffDeadline,
                        inventory ->
                            inventory.readRegionTransferCommittedSource(
                                transitionId, pinnedTransferCandidate))
                    .orElse(null);
      } else {
        requireReusableRegionGrant(
            existingRegionInstallation,
            source,
            incarnation,
            prior,
            expectedRegionEpoch,
            statusRepository,
            transferOwner,
            handoffDeadline,
            handoffRedisOwner);
        grant = existingRegionInstallation.grant;
        reusingRegionGrant = true;
      }
    }
    if (reusingRegionGrant) {
      handoffRedisOwner.requireGrantScope(
          grant, existingRegionInstallation.source, expectedRegionEpoch, runtimeIdentity);
    } else {
      handoffRedisOwner.requireGrantScope(grant, source, expectedRegionEpoch, runtimeIdentity);
    }
    CanonicalGameplayRegionRuntimeStatusRepository.Source afterAcquire =
        resolveCurrentInstallationSource(
            bindingInventory,
            worldRequest,
            evidence,
            transferSource,
            transferTopologyCandidate,
            transferOwner,
            handoffDeadline,
            worldDeadline);
    requireSameSource(source, afterAcquire);
    // Response and SQL latency consume the renewed grant, never extend its authority.
    long installDeadline =
        transferOwner == null
            ? handoffDeadline
            : renewedGrantDeadline(clock.millis(), leaseTtl, handoffDeadline);
    if (!recoveringInitialGrant) handoffRedisOwner.renew(grant, leaseTtl);
    handoffRedisOwner.requireCurrent(grant);
    requireSameIncarnation(
        incarnation, readInstallationIncarnation(transferOwner, handoffDeadline, source));
    handoffRedisOwner.verifyInitializedTimeline(grant, preparation);

    if ((transferOwner != null || deadlineBoundInstallation) && clock.millis() >= handoffDeadline)
      throw unavailable();
    if (transferId == null) {
      var installed =
          recoveringInitialGrant
              ? prior
              : withTransferStatusRepository(
                  statusRepository,
                  transferOwner,
                  handoffDeadline,
                  repository ->
                      repository.installOwner(
                          source,
                          expectedRegionEpoch,
                          prior,
                          grant.tokenCorrelation(),
                          grant.ownerService(),
                          grant.ownerInstanceId(),
                          clock.instant()));
      return verifyInstallation(
          bindingInventory,
          statusRepository,
          redisOwner,
          transitionId,
          worldRequest,
          evidence,
          source,
          incarnation,
          preparation,
          grant,
          installed,
          transferSource,
          transferTopologyCandidate,
          transferOwner,
          handoffDeadline,
          worldDeadline);
    }
    var pending =
        new PendingTransfer(
            this,
            transferId,
            transferOwner,
            transitionId,
            worldRequest,
            evidence,
            source,
            incarnation,
            preparation,
            expectedRegionEpoch,
            grant,
            transferCandidate,
            transferSource,
            transferTopologyCandidate);
    try {
      var installed =
          transferOwner.install(
              transferId,
              source,
              prior,
              grant.tokenCorrelation(),
              grant.ownerService(),
              grant.ownerInstanceId(),
              installDeadline);
      return verifyInstallation(
          bindingInventory,
          statusRepository,
          redisOwner,
          transitionId,
          worldRequest,
          evidence,
          source,
          incarnation,
          preparation,
          grant,
          installed,
          transferSource,
          transferTopologyCandidate,
          transferOwner,
          handoffDeadline,
          worldDeadline);
    } catch (RuntimeException failure) {
      throw new TransferCompletionUnconfirmed(pending, failure);
    }
  }

  /**
   * Resumes only the same process's still-current grant after an unconfirmed transfer completion.
   */
  Installation resumeCompletedTransfer(
      TransferCompletionUnconfirmed interrupted, long absoluteDeadlineMillis) {
    requireNoAmbientTransaction();
    var pending = Objects.requireNonNull(interrupted, "interrupted").pending;
    if (pending == null || pending.owner() != this) throw unavailable();
    long startedAt = clock.millis();
    long deadline = Math.min(absoluteDeadlineMillis, Math.addExact(startedAt, 5_000L));
    if (startedAt >= deadline) throw unavailable();
    Deadline worldDeadline = worldDeadline(startedAt, deadline);
    CanonicalGameplayRegionLeaseRedisOwner handoffRedisOwner =
        redisOwner.forHandoffDeadline(clock, deadline);
    handoffRedisOwner.requireGrantScope(
        pending.grant(), pending.source(), pending.expectedRegionEpoch(), runtimeIdentity);
    handoffRedisOwner.requireCurrent(pending.grant());
    handoffRedisOwner.verifyInitializedTimeline(pending.grant(), pending.preparation());
    requireSameIncarnation(
        pending.incarnation(),
        readInstallationIncarnation(pending.transferOwner(), deadline, pending.source()));
    var installed =
        pending
            .transferOwner()
            .recoverCompleted(
                pending.transferId(),
                pending.grant().ownerInstanceId(),
                pending.grant().tokenCorrelation(),
                deadline);
    if (clock.millis() >= deadline) throw unavailable();
    var transferSource = pending.transferSource();
    var transferTopologyCandidate = pending.transferTopologyCandidate();
    if (transferSource == null && transferTopologyCandidate == null) {
      // Account may have retained its definitive abort after transfer installation committed but
      // before independent verification. Resolve that exact pinned original as topology only;
      // neither the abort row nor its candidate restores provisional admission authority.
      var pinnedCandidate = pending.transferCandidate();
      transferTopologyCandidate =
          readInstallationInventory(
                  bindingInventory,
                  pending.transferOwner(),
                  deadline,
                  inventory ->
                      inventory.readRegionTransferAbortedCandidate(
                          pending.transitionId(), pinnedCandidate))
              .orElse(null);
      if (transferTopologyCandidate == null) {
        transferSource =
            readInstallationInventory(
                    bindingInventory,
                    pending.transferOwner(),
                    deadline,
                    inventory ->
                        inventory.readRegionTransferCommittedSource(
                            pending.transitionId(), pinnedCandidate))
                .orElse(null);
      }
    }
    return verifyInstallation(
        bindingInventory,
        statusRepository,
        redisOwner,
        pending.transitionId(),
        pending.worldRequest(),
        transferTopologyCandidate == null && transferSource == null ? pending.evidence() : null,
        pending.source(),
        pending.incarnation(),
        pending.preparation(),
        pending.grant(),
        installed,
        transferSource,
        transferTopologyCandidate,
        pending.transferOwner(),
        deadline,
        worldDeadline);
  }

  private Installation verifyInstallation(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner,
      UUID transitionId,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation,
      CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation,
      CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant,
      CanonicalGameplayRegionRuntimeStatus installed,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource transferSource,
      CanonicalGameplayOriginalNewCandidate transferTopologyCandidate,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      Deadline worldDeadline) {
    CanonicalGameplayRegionLeaseRedisOwner handoffRedisOwner =
        transferOwner == null && !deadlineBoundInstallation
            ? redisOwner
            : redisOwner.forHandoffDeadline(clock, handoffDeadline);
    handoffRedisOwner.requireCurrent(grant);
    handoffRedisOwner.verifyInitializedTimeline(grant, preparation);
    CanonicalGameplayRegionRuntimeStatus observed =
        withTransferStatusRepository(
                statusRepository,
                transferOwner,
                handoffDeadline,
                repository -> repository.find(source))
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!installed.equals(observed) || observed.paused()) throw unavailable();
    CanonicalGameplayRegionRuntimeStatusRepository.Source afterInstall =
        resolveCurrentInstallationSource(
            bindingInventory,
            worldRequest,
            evidence,
            transferSource,
            transferTopologyCandidate,
            transferOwner,
            handoffDeadline,
            worldDeadline);
    requireSameSource(source, afterInstall);
    handoffRedisOwner.requireCurrent(grant);
    requireSameIncarnation(
        incarnation, readInstallationIncarnation(transferOwner, handoffDeadline, source));
    handoffRedisOwner.verifyInitializedTimeline(grant, preparation);
    withTransferStatusRepository(
        statusRepository,
        transferOwner,
        handoffDeadline,
        repository -> repository.markTimelineInstalled(preparation, installed, clock.instant()));
    handoffRedisOwner.requireCurrent(grant);
    handoffRedisOwner.verifyInitializedTimeline(grant, preparation);
    requireSameIncarnation(
        incarnation, readInstallationIncarnation(transferOwner, handoffDeadline, source));
    requireSameSource(
        source,
        resolveCurrentInstallationSource(
            bindingInventory,
            worldRequest,
            evidence,
            transferSource,
            transferTopologyCandidate,
            transferOwner,
            handoffDeadline,
            worldDeadline));
    CanonicalGameplayRegionRuntimeStatus finalStatus =
        withTransferStatusRepository(
                statusRepository,
                transferOwner,
                handoffDeadline,
                repository -> repository.find(source))
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!installed.equals(finalStatus)
        || finalStatus.paused()
        || transferOwner != null && clock.millis() >= handoffDeadline) throw unavailable();
    return new Installation(
        transitionId, worldRequest, evidence, source, incarnation, preparation, grant, installed);
  }

  private void requireReusableRegionGrant(
      Installation existing,
      CanonicalGameplayRegionRuntimeStatusRepository.Source candidateSource,
      CanonicalGameplayRegionIncarnationSource.StableEvidence currentIncarnation,
      CanonicalGameplayRegionRuntimeStatus currentStatus,
      long expectedRegionEpoch) {
    requireReusableRegionGrant(
        existing,
        candidateSource,
        currentIncarnation,
        currentStatus,
        expectedRegionEpoch,
        statusRepository,
        null,
        Long.MAX_VALUE,
        redisOwner);
  }

  private void requireReusableRegionGrant(
      Installation existing,
      CanonicalGameplayRegionRuntimeStatusRepository.Source candidateSource,
      CanonicalGameplayRegionIncarnationSource.StableEvidence currentIncarnation,
      CanonicalGameplayRegionRuntimeStatus currentStatus,
      long expectedRegionEpoch,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      CanonicalGameplayRegionLeaseRedisOwner grantOwner) {
    if (existing == null
        || !existing.owner.equals(this)
        || !CanonicalGameplayRegionRuntimeStatusRepository.sameRegionSource(
            existing.source, candidateSource)
        || existing.status == null
        || !existing.status.equals(currentStatus)
        || currentStatus.paused()
        || existing.status.regionEpoch() != expectedRegionEpoch) {
      throw unavailable();
    }
    requireSameIncarnation(existing.incarnation, currentIncarnation);
    CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation persistedPreparation =
        withTransferStatusRepository(
                statusRepository,
                transferOwner,
                handoffDeadline,
                repository -> repository.findTimelinePreparation(existing.preparation.source()))
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!statusRepository.matchesCurrentInstalledTimelineInitialization(
        existing.preparation,
        persistedPreparation,
        candidateSource,
        currentIncarnation,
        currentStatus)) {
      throw unavailable();
    }
    grantOwner.requireGrantScope(
        existing.grant, existing.source, expectedRegionEpoch, runtimeIdentity);
    grantOwner.requireCurrent(existing.grant);
    grantOwner.verifyInitializedTimeline(existing.grant, persistedPreparation);
  }

  /**
   * Revalidates the same SQL source, World assignment and acquired lease before any later bridge.
   */
  void revalidateBeforeBridge(Installation installation) {
    currentBridgeContext(installation);
  }

  BridgeContext currentBridgeContext(Installation installation) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(installation, "installation");
    if (!installation.owner.equals(this) || installation.evidence == null) throw unavailable();
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        readCurrentProvisionalEvidence(installation.transitionId);
    bindingInventory.requireSameProvisionalSource(installation.evidence, evidence);
    ResolvedBridgeSource resolved = resolveExactBridgeSource(installation.worldRequest, evidence);
    CanonicalGameplayRegionRuntimeStatusRepository.Source currentSource = resolved.source;
    requireSameSource(installation.source, currentSource);
    requireSameIncarnation(installation.incarnation, readCurrentIncarnation(currentSource));
    CanonicalGameplayRegionRuntimeStatus current =
        statusRepository
            .find(currentSource)
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!installation.status.equals(current) || current.paused()) throw unavailable();
    redisOwner.renew(installation.grant, leaseTtl);
    redisOwner.requireCurrent(installation.grant);
    redisOwner.verifyInitializedTimeline(installation.grant, installation.preparation);
    requireSameIncarnation(installation.incarnation, readCurrentIncarnation(currentSource));
    return new BridgeContext(evidence, currentSource, current, resolved.resolvedRegion);
  }

  /**
   * Revalidates a retained original-NEW bridge using only this owner's live private grant. The SQL
   * source is historical candidate proof; it supplies no lease or Redis authority.
   */
  OriginalNewFinalBindingCurrentProof revalidateOriginalNewFinalBinding(
      Installation installation,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
          finalAdmissionSource,
      CanonicalGameplayRuntimeActorReference reference) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(installation, "installation");
    Objects.requireNonNull(finalAdmissionSource, "finalAdmissionSource");
    Objects.requireNonNull(reference, "runtimeReference");
    if (!installation.owner.equals(this) || installation.evidence == null) throw unavailable();

    CanonicalGameplayOriginalNewCandidate candidate = finalAdmissionSource.candidate();
    CanonicalGameplayBindingRegionBridgeObligation acknowledgement =
        finalAdmissionSource.regionAcknowledgement();
    requireOriginalNewFinalSourceMatchesInstallation(
        installation, finalAdmissionSource, candidate, acknowledgement);

    ResolvedBridgeSource resolved =
        resolveExactBridgeSource(installation.worldRequest, installation.evidence);
    CanonicalGameplayRegionRuntimeStatusRepository.Source currentSource = resolved.source;
    requireSameSource(installation.source, currentSource);
    CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation =
        readCurrentIncarnation(currentSource);
    requireSameIncarnation(installation.incarnation, incarnation);
    CanonicalGameplayRegionRuntimeStatus currentStatus =
        statusRepository
            .find(currentSource)
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    long expectedRegionEpoch = exactExpectedRegionEpoch(candidate.bindingIdentity().regionEpoch());
    requireReusableRegionGrant(
        installation, currentSource, incarnation, currentStatus, expectedRegionEpoch);
    redisOwner.renew(installation.grant, leaseTtl);
    redisOwner.requireCurrent(installation.grant);

    String worldSourceDigest =
        CanonicalGameplayRegionBindingBridgeProofs.worldSourceDigest(
            installation.worldRequest, resolved.resolvedRegion);
    requireFinalAcknowledgementWorldCurrent(
        acknowledgement, currentSource, currentStatus, worldSourceDigest);
    String entityReferenceDigest =
        requireFinalAcknowledgementEntityCurrent(
            installation, candidate, acknowledgement, reference);

    CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation currentPreparation =
        statusRepository
            .findTimelinePreparation(installation.preparation.source())
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!statusRepository.matchesCurrentInstalledTimelineInitialization(
        installation.preparation, currentPreparation, currentSource, incarnation, currentStatus)) {
      throw unavailable();
    }
    redisOwner.requireCurrent(installation.grant);
    redisOwner.verifyInitializedTimeline(installation.grant, currentPreparation);

    CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readback =
        redisOwner.readBackSessionBinding(
            installation.grant,
            currentStatus,
            reference.runtimeEntityId(),
            candidate.bindingIdentity().sessionId(),
            candidate.transition().bindingGeneration());
    requireFinalAcknowledgementReadback(
        acknowledgement, reference, candidate, currentStatus, readback);

    ResolvedBridgeSource afterReadback =
        resolveExactBridgeSource(installation.worldRequest, installation.evidence);
    requireSameSource(currentSource, afterReadback.source);
    String afterReadbackWorldDigest =
        CanonicalGameplayRegionBindingBridgeProofs.worldSourceDigest(
            installation.worldRequest, afterReadback.resolvedRegion);
    if (!worldSourceDigest.equals(afterReadbackWorldDigest)) throw unavailable();
    CanonicalGameplayRegionIncarnationSource.StableEvidence afterReadbackIncarnation =
        readCurrentIncarnation(currentSource);
    requireSameIncarnation(incarnation, afterReadbackIncarnation);
    CanonicalGameplayRegionRuntimeStatus afterReadbackStatus =
        statusRepository
            .find(currentSource)
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!currentStatus.equals(afterReadbackStatus) || afterReadbackStatus.paused()) {
      throw unavailable();
    }
    redisOwner.requireCurrent(installation.grant);
    redisOwner.verifyInitializedTimeline(installation.grant, currentPreparation);

    return new OriginalNewFinalBindingCurrentProof(
        candidate,
        finalAdmissionSource.candidateDigest(),
        acknowledgement,
        currentSource,
        currentStatus,
        afterReadbackIncarnation,
        worldSourceDigest,
        entityReferenceDigest,
        readback);
  }

  private static void requireOriginalNewFinalSourceMatchesInstallation(
      Installation installation,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayBindingRegionBridgeObligation acknowledgement) {
    var identity = candidate.bindingIdentity();
    if (source.candidateInstallationProvenance()
            != CanonicalGameplayBindingInventoryRepository
                .OriginalNewCandidateInstallationProvenance.HISTORICAL_READBACK_CONFIRMED
        || !candidate.bindingDecisionId().equals(installation.transitionId)
        || !candidate.transition().transitionId().equals(installation.transitionId)
        || !candidate.matchesProvisionalSources(installation.evidence)
        || !identity.equals(installation.evidence.candidate().identity())
        || !candidate.targetEvidence().canonicalTenantId().equals(identity.tenantId())
        || !candidate.targetEvidence().canonicalGameInstanceId().equals(identity.gameInstanceId())
        || !candidate
            .targetEvidence()
            .playableStateNamespaceId()
            .equals(identity.playableStateNamespaceId())
        || !candidate.targetEvidence().playableStateScope().equals(identity.playableStateScope())
        || !acknowledgement.transitionId().equals(installation.transitionId)
        || !acknowledgement.bindingRef().equals(candidate.transition().bindingRef())
        || !acknowledgement.bindingGeneration().equals(candidate.transition().bindingGeneration())
        || !acknowledgement.accountId().equals(identity.accountId())
        || !acknowledgement.tenantId().equals(identity.tenantId())
        || !acknowledgement.gameInstanceId().equals(identity.gameInstanceId())
        || acknowledgement.runtimeGameInstanceId() != identity.runtimeGameInstanceId()
        || !acknowledgement.sessionId().equals(identity.sessionId())
        || !acknowledgement.regionId().equals(identity.regionId())
        || !acknowledgement.regionEpoch().equals(identity.regionEpoch())
        || !matchesWorldRequestTarget(candidate, installation.worldRequest)) {
      throw unavailable();
    }
  }

  private static boolean matchesWorldRequestTarget(
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalInitialPlayerLocationRequest request) {
    var source = candidate.targetEvidence();
    var target = request.getExpectedTarget();
    String scope =
        switch (target.getPlayableStateScope()) {
          case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
          case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
          default -> "";
        };
    return source.canonicalTenantId().toString().equals(target.getCanonicalTenantUuid())
        && source.realmId().toString().equals(target.getRealmUuid())
        && source.worldSlug().equals(target.getWorldSlug())
        && source.realmSlug().equals(target.getRealmSlug())
        && source.canonicalGameInstanceId().toString().equals(target.getGameInstanceId())
        && source.catalogRevision() == target.getCatalogRevision()
        && source.canonicalVersionId().toString().equals(target.getCanonicalVersionUuid())
        && source
            .playableStateNamespaceId()
            .toString()
            .equals(target.getPlayableStateNamespaceUuid())
        && source.playableStateScope().equals(scope)
        && source.activeWorldEpoch() == request.getActiveLifecycleEpoch()
        && source.initialAdmissionRequestId().equals(request.getInitialAdmissionRequestId())
        && source.initialAdmissionRequestDigest().equals(request.getInitialAdmissionRequestDigest())
        && source.pointerVersion() == request.getCommittedPointerVersion()
        && source.holdId().toString().equals(request.getInitialAdmissionHoldUuid())
        && source.holdFence().toString().equals(request.getInitialAdmissionHoldFence())
        && source.auditEventId() == request.getAuditEventId()
        && source.ownerProofDigest().equals(request.getTerminalProofDigest());
  }

  private static void requireFinalAcknowledgementWorldCurrent(
      CanonicalGameplayBindingRegionBridgeObligation acknowledgement,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus status,
      String worldSourceDigest) {
    if (acknowledgement.status()
            != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED
        || !acknowledgement.tenantId().equals(source.tenantId())
        || !acknowledgement.gameInstanceId().equals(source.gameInstanceId())
        || !acknowledgement.regionId().equals(source.regionId())
        || !acknowledgement.canonicalRegionId().equals(source.canonicalRegionId())
        || acknowledgement.worldLifecycleEpoch() != source.lifecycleEpoch()
        || acknowledgement.worldLifecycleRowVersion() != source.lifecycleRowVersion()
        || !acknowledgement.worldCaptureId().equals(source.captureId())
        || !acknowledgement.worldGraphSha256().equals(source.graphSha256())
        || !acknowledgement.worldPreparationInputDigest().equals(source.preparationInputDigest())
        || !acknowledgement.worldSourceDigest().equals(worldSourceDigest)
        || !acknowledgement.regionEpoch().equals(BigInteger.valueOf(status.regionEpoch()))
        || !acknowledgement.executorFence().equals(status.executorFence())
        || !acknowledgement.leaseTokenCorrelation().equals(status.leaseTokenCorrelation())) {
      throw unavailable();
    }
  }

  private static String requireFinalAcknowledgementEntityCurrent(
      Installation installation,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayBindingRegionBridgeObligation acknowledgement,
      CanonicalGameplayRuntimeActorReference reference) {
    var identity = candidate.bindingIdentity();
    if (!reference.runtimeEntityId().tenantId().equals(identity.tenantId())
        || !reference
            .runtimeEntityId()
            .gameInstanceId()
            .equals(identity.gameInstanceId().toString())
        || reference.runtimeEntityId().value() != acknowledgement.runtimeEntityId()
        || !reference.origin().equals(acknowledgement.actorOrigin())
        || !reference.matchesWorldRequest(installation.worldRequest)
        || !acknowledgement
            .actorOrigin()
            .equals(CanonicalGameplayActorOrigins.fromWorldRequest(installation.worldRequest))
        || !reference.canonicalAccountUuid().equals(identity.accountId())
        || !reference.characterUuid().equals(identity.characterId())
        || !reference.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        || reference.projectionGeneration() != acknowledgement.entityProjectionGeneration()
        || reference.projectionFence() != acknowledgement.entityProjectionFence()
        || !reference.workloadNamespace().equals(acknowledgement.entityWorkloadNamespace())) {
      throw unavailable();
    }
    String digest = CanonicalGameplayRegionBindingBridgeProofs.entityReferenceDigest(reference);
    if (!acknowledgement.entityReferenceDigest().equals(digest)) throw unavailable();
    return digest;
  }

  private static void requireFinalAcknowledgementReadback(
      CanonicalGameplayBindingRegionBridgeObligation acknowledgement,
      CanonicalGameplayRuntimeActorReference reference,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayRegionRuntimeStatus status,
      CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readback) {
    if (!readback.runtimeEntityId().equals(reference.runtimeEntityId())
        || !readback.sessionId().equals(candidate.bindingIdentity().sessionId())
        || !readback.bindingGeneration().equals(candidate.transition().bindingGeneration())
        || readback.regionEpoch() != status.regionEpoch()
        || !readback.executorFence().equals(status.executorFence())
        || !readback.leaseTokenCorrelation().equals(status.leaseTokenCorrelation())
        || !acknowledgement.executorFence().equals(readback.executorFence())
        || !acknowledgement.leaseTokenCorrelation().equals(readback.leaseTokenCorrelation())
        || !acknowledgement.regionEpoch().equals(BigInteger.valueOf(readback.regionEpoch()))
        || !acknowledgement.redisReadbackDigest().equals(readback.digest())) {
      throw unavailable();
    }
  }

  CanonicalGameplayBindingRegionBridgeObligation prepareBridge(
      Installation installation,
      RegionBridgePreimage preimage,
      CanonicalGameplayRuntimeActorReference reference) {
    Objects.requireNonNull(preimage, "preimage");
    BridgeContext context = preimage.context();
    requireOwnedContext(installation, context);
    return bindingInventory.prepareRegionBridge(
        context.evidence,
        installation.worldRequest,
        context.resolvedRegion,
        reference,
        context.status,
        preimage.redisPreimage());
  }

  RegionBridgePreimage probeRegionSessionBinding(
      Installation installation, CanonicalGameplayRuntimeActorReference reference) {
    BridgeContext context = currentBridgeContext(installation);
    CanonicalGameplayRegionLeaseRedisOwner.BindingPreimage redisPreimage =
        redisOwner.probeSessionBinding(
            installation.grant,
            context.status,
            reference.runtimeEntityId(),
            installation.evidence.candidate().identity().sessionId(),
            installation.evidence.candidate().bindingGeneration());
    return new RegionBridgePreimage(context, redisPreimage);
  }

  void applyRegionSessionBinding(
      Installation installation, CanonicalGameplayRuntimeActorReference reference) {
    BridgeContext context = currentBridgeContext(installation);
    redisOwner.applySessionBinding(
        installation.grant,
        context.status,
        reference.runtimeEntityId(),
        installation.evidence.candidate().identity().sessionId(),
        installation.evidence.candidate().bindingGeneration());
  }

  CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readBackRegionSessionBinding(
      Installation installation,
      CanonicalGameplayRegionRuntimeStatus currentStatus,
      CanonicalGameplayRuntimeActorReference reference) {
    BridgeContext context = currentBridgeContext(installation);
    if (!context.status.equals(currentStatus)) throw unavailable();
    return redisOwner.readBackSessionBinding(
        installation.grant,
        context.status,
        reference.runtimeEntityId(),
        installation.evidence.candidate().identity().sessionId(),
        installation.evidence.candidate().bindingGeneration());
  }

  CanonicalGameplayBindingInventoryRepository.RegionBridgeAcknowledgement acknowledgeBridge(
      Installation installation,
      CanonicalGameplayBindingRegionBridgeObligation prepared,
      BridgeContext context,
      CanonicalGameplayRuntimeActorReference reference,
      CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readback) {
    requireOwnedContext(installation, context);
    BridgeContext finalContext = currentBridgeContext(installation);
    requireOwnedContext(installation, finalContext);
    if (!context.status.equals(finalContext.status)
        || !context.source.equals(finalContext.source)
        || !CanonicalGameplayRegionBindingBridgeProofs.worldSourceDigest(
                installation.worldRequest, context.resolvedRegion)
            .equals(
                CanonicalGameplayRegionBindingBridgeProofs.worldSourceDigest(
                    installation.worldRequest, finalContext.resolvedRegion))) {
      throw unavailable();
    }
    return bindingInventory.acknowledgeRegionBridge(
        prepared,
        installation.worldRequest,
        finalContext.resolvedRegion,
        reference,
        finalContext.status,
        readback,
        clock.instant());
  }

  private void requireOwnedContext(Installation installation, BridgeContext context) {
    Objects.requireNonNull(installation, "installation");
    Objects.requireNonNull(context, "bridgeContext");
    if (!installation.owner.equals(this)
        || !installation.source.equals(context.source)
        || !installation.status.equals(context.status)
        || !installation.transitionId.equals(context.evidence.transition().transitionId())
        || context.resolvedRegion == null) {
      throw unavailable();
    }
    bindingInventory.requireSameProvisionalSource(installation.evidence, context.evidence);
  }

  private CanonicalGameplayBindingProvisionalCasEvidence readCurrentProvisionalEvidence(
      UUID transitionId) {
    return readCurrentProvisionalEvidence(bindingInventory, transitionId);
  }

  private CanonicalGameplayBindingProvisionalCasEvidence readCurrentProvisionalEvidence(
      CanonicalGameplayBindingInventoryRepository inventory, UUID transitionId) {
    requireNoAmbientTransaction();
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        inventory.readProvisionalCasEvidence(transitionId);
    if (evidence.transition().status()
            != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
        || evidence.candidate().lifecycle()
            != CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL
        || evidence.expectedPrior() != null
        || evidence.transition().expectedPriorBindingRef() != null
        || evidence.transition().expectedPriorBindingGeneration() != null
        || !evidence.transition().transitionId().equals(transitionId)
        || !evidence.candidate().transitionId().equals(transitionId)) {
      throw unavailable();
    }
    return evidence;
  }

  private CanonicalGameplayRegionRuntimeStatusRepository.Source resolveCurrentInstallationSource(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalInitialPlayerLocationRequest request,
      CanonicalGameplayBindingProvisionalCasEvidence original,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource transferSource,
      CanonicalGameplayOriginalNewCandidate transferTopologyCandidate,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      Deadline worldDeadline) {
    if (transferSource == null) {
      if (transferTopologyCandidate != null) {
        var current =
            readInstallationInventory(
                bindingInventory,
                transferOwner,
                handoffDeadline,
                inventory ->
                    inventory
                        .readRegionTransferAbortedCandidate(
                            transferTopologyCandidate.bindingDecisionId(),
                            transferTopologyCandidate)
                        .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable));
        return resolveExactBridgeSource(request, current.bindingIdentity(), worldDeadline).source;
      }
      if (original == null) throw unavailable();
      var current =
          readInstallationInventory(
              bindingInventory,
              transferOwner,
              handoffDeadline,
              inventory ->
                  readCurrentProvisionalEvidence(inventory, original.transition().transitionId()));
      return resolveExactBridgeSource(request, current.candidate().identity(), worldDeadline)
          .source;
    }
    var current =
        readInstallationInventory(
            bindingInventory,
            transferOwner,
            handoffDeadline,
            inventory ->
                inventory.readRegionTransferOriginalSource(
                    transferSource.candidate().bindingDecisionId(), transferSource));
    return resolveExactBridgeSource(request, current.candidate().bindingIdentity(), worldDeadline)
        .source;
  }

  private <T> T readInstallationInventory(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      java.util.function.Function<CanonicalGameplayBindingInventoryRepository, T> read) {
    return transferOwner == null
        ? read.apply(bindingInventory)
        : bindingInventory.withinTransferRead(clock, handoffDeadline, read);
  }

  private <T> T readInstallationInventory(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      CanonicalGameplayOriginalNewCapacityExecutionBudget retainedBudget,
      java.util.function.Function<CanonicalGameplayBindingInventoryRepository, T> read) {
    if (transferOwner == null) return read.apply(bindingInventory);
    return retainedBudget == null
        ? bindingInventory.withinTransferRead(clock, handoffDeadline, read)
        : bindingInventory.withinTransferRead(retainedBudget, read);
  }

  private <T> T withTransferStatusRepository(
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      java.util.function.Function<CanonicalGameplayRegionRuntimeStatusRepository, T> operation) {
    return transferOwner == null
        ? operation.apply(statusRepository)
        : transferOwner.withStatusRepository(handoffDeadline, operation);
  }

  private <T> T withTransferStatusRepository(
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      CanonicalGameplayOriginalNewCapacityExecutionBudget retainedBudget,
      java.util.function.Function<CanonicalGameplayRegionRuntimeStatusRepository, T> operation) {
    if (transferOwner == null) return operation.apply(statusRepository);
    return retainedBudget == null
        ? transferOwner.withStatusRepository(handoffDeadline, operation)
        : transferOwner.withStatusRepository(retainedBudget, operation);
  }

  private ResolvedBridgeSource resolveExactBridgeSource(
      CanonicalInitialPlayerLocationRequest request,
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {
    return resolveExactBridgeSource(request, evidence.candidate().identity(), null);
  }

  private static Deadline worldDeadline(long startedAt, long absoluteDeadlineMillis) {
    if (absoluteDeadlineMillis == Long.MAX_VALUE) return null;
    return Deadline.after(
        absoluteDeadlineMillis <= startedAt ? 0 : absoluteDeadlineMillis - startedAt,
        TimeUnit.MILLISECONDS);
  }

  private ResolvedBridgeSource resolveExactBridgeSource(
      CanonicalInitialPlayerLocationRequest request,
      CanonicalGameplayBindingIdentity identity,
      Deadline operationDeadline) {
    requireNoAmbientTransaction();
    var target = request.getExpectedTarget();
    if (!identity.accountId().toString().equals(request.getCanonicalAccountUuid())
        || !identity.characterId().toString().equals(request.getCharacterUuid())
        || !identity.tenantId().toString().equals(target.getCanonicalTenantUuid())
        || !identity.gameInstanceId().toString().equals(target.getGameInstanceId())
        || !identity
            .playableStateNamespaceId()
            .toString()
            .equals(target.getPlayableStateNamespaceUuid())
        || !"SHARED".equals(identity.playableStateScope())) {
      throw unavailable();
    }

    CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolved =
        operationDeadline == null
            ? worldRegionResolver.resolve(request)
            : worldRegionResolver.resolve(request, operationDeadline);
    var assignment = resolved.lifecycleAssignment();
    if (!identity.tenantId().equals(assignment.request().canonicalTenantId())
        || !identity.gameInstanceId().equals(assignment.request().canonicalGameInstanceId())
        || !identity.regionId().equals(assignment.operationalRegionId())) {
      throw unavailable();
    }
    return new ResolvedBridgeSource(
        new CanonicalGameplayRegionRuntimeStatusRepository.Source(
            identity.tenantId(),
            identity.gameInstanceId(),
            assignment.canonicalRegionId(),
            assignment.operationalRegionId(),
            assignment.lifecycleEpoch(),
            assignment.lifecycleRowVersion(),
            assignment.request(),
            assignment.captureId(),
            assignment.graphSha256(),
            assignment.preparationInputDigest()),
        resolved);
  }

  private CanonicalGameplayRegionIncarnationSource.StableEvidence readCurrentIncarnation(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source) {
    requireNoAmbientTransaction();
    CanonicalGameplayRegionIncarnationSource.RegionScope scope =
        CanonicalGameplayRegionIncarnationSource.RegionScope.from(source);
    CanonicalGameplayRegionIncarnationSource.StableEvidence current =
        incarnationSource.readCurrent(scope);
    if (current == null
        || !scope.equals(current.scope())
        || !current.timelineInitializationQualification().matches(scope, current.digest())) {
      throw unavailable();
    }
    return current;
  }

  CanonicalGameplayRegionIncarnationSource.StableEvidence readInstallationIncarnation(
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      long handoffDeadline,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source) {
    if (transferOwner == null) return readCurrentIncarnation(source);
    return CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
        clock,
        handoffDeadline,
        budget -> {
          budget.check();
          return readCurrentIncarnation(source);
        });
  }

  /** Reuses the prepared initialization worker instead of acquiring a third background permit. */
  CanonicalGameplayRegionIncarnationSource.StableEvidence readInstallationIncarnation(
      CanonicalGameplayOriginalNewCapacityExecutionBudget retainedBudget,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source) {
    // The legacy non-deadline preparation has no resource permit to reuse.
    if (retainedBudget != null) retainedBudget.check();
    return readCurrentIncarnation(source);
  }

  private static void requireSameIncarnation(
      CanonicalGameplayRegionIncarnationSource.StableEvidence expected,
      CanonicalGameplayRegionIncarnationSource.StableEvidence actual) {
    if (expected == null
        || actual == null
        || !expected.scope().equals(actual.scope())
        || !java.util.Arrays.equals(expected.scopeBytes(), actual.scopeBytes())
        || !java.util.Arrays.equals(expected.canonicalBytes(), actual.canonicalBytes())) {
      throw unavailable();
    }
  }

  static long renewedGrantDeadline(long renewalStartedAt, Duration leaseTtl, long deadline) {
    try {
      long ttlMillis = leaseTtl.toMillis();
      if (ttlMillis <= 0L) throw unavailable();
      return Math.min(deadline, Math.addExact(renewalStartedAt, ttlMillis));
    } catch (ArithmeticException invalid) {
      throw unavailable();
    }
  }

  private static long exactExpectedRegionEpoch(BigInteger value) {
    Objects.requireNonNull(value, "regionEpoch");
    try {
      long result = value.longValueExact();
      if (result <= 0L) throw unavailable();
      return result;
    } catch (ArithmeticException invalid) {
      throw unavailable();
    }
  }

  private static void requireSameSource(
      CanonicalGameplayRegionRuntimeStatusRepository.Source expected,
      CanonicalGameplayRegionRuntimeStatusRepository.Source actual) {
    if (!expected.equals(actual)) throw unavailable();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Canonical Game Session region owner requires current exact assignment and lease evidence");
  }

  private record PendingTransfer(
      CanonicalGameplayRegionLeaseOwner owner,
      UUID transferId,
      CanonicalGameplayRegionOwnerTransferCoordinator transferOwner,
      UUID transitionId,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation,
      CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation,
      long expectedRegionEpoch,
      CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant,
      CanonicalGameplayOriginalNewCandidate transferCandidate,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource transferSource,
      CanonicalGameplayOriginalNewCandidate transferTopologyCandidate) {}

  static final class TransferCompletionUnconfirmed extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final transient PendingTransfer pending;

    private TransferCompletionUnconfirmed(PendingTransfer pending, RuntimeException cause) {
      super("Normal region handoff completion requires current exact verification", cause);
      this.pending = pending;
    }
  }

  /** Private construction prevents caller-created installation/readback objects. */
  final class Installation {
    private final CanonicalGameplayRegionLeaseOwner owner;
    final UUID transitionId;
    final CanonicalInitialPlayerLocationRequest worldRequest;
    final CanonicalGameplayBindingProvisionalCasEvidence evidence;
    final CanonicalGameplayRegionRuntimeStatusRepository.Source source;
    final CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation;
    final CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation;
    final CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant;
    final CanonicalGameplayRegionRuntimeStatus status;

    private Installation(
        UUID transitionId,
        CanonicalInitialPlayerLocationRequest worldRequest,
        CanonicalGameplayBindingProvisionalCasEvidence evidence,
        CanonicalGameplayRegionRuntimeStatusRepository.Source source,
        CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation,
        CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation,
        CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant,
        CanonicalGameplayRegionRuntimeStatus status) {
      this.owner = CanonicalGameplayRegionLeaseOwner.this;
      this.transitionId = transitionId;
      this.worldRequest = worldRequest;
      this.evidence = evidence;
      this.source = source;
      this.incarnation = incarnation;
      this.preparation = preparation;
      this.grant = grant;
      this.status = status;
    }
  }

  record BridgeContext(
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus status,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion) {}

  record RegionBridgePreimage(
      BridgeContext context,
      CanonicalGameplayRegionLeaseRedisOwner.BindingPreimage redisPreimage) {}

  /**
   * Read-only proof that this private owner, World source, Entity reference, and Redis binding
   * still match the retained original-NEW acknowledgement. It grants no admission authority.
   */
  static final class OriginalNewFinalBindingCurrentProof {
    private final CanonicalGameplayOriginalNewCandidate candidate;
    private final String candidateDigest;
    private final CanonicalGameplayBindingRegionBridgeObligation acknowledgement;
    private final CanonicalGameplayRegionRuntimeStatusRepository.Source source;
    private final CanonicalGameplayRegionRuntimeStatus status;
    private final CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation;
    private final String worldSourceDigest;
    private final String entityReferenceDigest;
    private final CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readback;

    private OriginalNewFinalBindingCurrentProof(
        CanonicalGameplayOriginalNewCandidate candidate,
        String candidateDigest,
        CanonicalGameplayBindingRegionBridgeObligation acknowledgement,
        CanonicalGameplayRegionRuntimeStatusRepository.Source source,
        CanonicalGameplayRegionRuntimeStatus status,
        CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation,
        String worldSourceDigest,
        String entityReferenceDigest,
        CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readback) {
      this.candidate = candidate;
      this.candidateDigest = candidateDigest;
      this.acknowledgement = acknowledgement;
      this.source = source;
      this.status = status;
      this.incarnation = incarnation;
      this.worldSourceDigest = worldSourceDigest;
      this.entityReferenceDigest = entityReferenceDigest;
      this.readback = readback;
    }

    boolean matchesCurrentEntityReference(CanonicalGameplayRuntimeActorReference reference) {
      return reference != null
          && entityReferenceDigest.equals(
              CanonicalGameplayRegionBindingBridgeProofs.entityReferenceDigest(reference));
    }

    boolean sameRetainedBindingEvidenceAs(OriginalNewFinalBindingCurrentProof other) {
      return other != null
          && candidateDigest.equals(other.candidateDigest)
          && candidate.equals(other.candidate)
          && acknowledgement.equals(other.acknowledgement)
          && source.equals(other.source)
          && status.equals(other.status)
          && Arrays.equals(incarnation.canonicalBytes(), other.incarnation.canonicalBytes())
          && worldSourceDigest.equals(other.worldSourceDigest)
          && entityReferenceDigest.equals(other.entityReferenceDigest)
          && readback.digest().equals(other.readback.digest());
    }
  }

  private record ResolvedBridgeSource(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion) {}
}
