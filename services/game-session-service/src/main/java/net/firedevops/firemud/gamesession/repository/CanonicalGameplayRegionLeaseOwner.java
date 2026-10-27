package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
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

  CanonicalGameplayRegionLeaseOwner(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock) {
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
    requireNoAmbientTransaction();
    Objects.requireNonNull(transitionId, "transitionId");
    Objects.requireNonNull(worldRequest, "worldRequest");
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        readCurrentProvisionalEvidence(transitionId);
    ResolvedBridgeSource initialResolved = resolveExactBridgeSource(worldRequest, evidence);
    CanonicalGameplayRegionRuntimeStatusRepository.Source source = initialResolved.source;
    CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation =
        readCurrentIncarnation(source);
    long expectedRegionEpoch =
        exactExpectedRegionEpoch(evidence.candidate().identity().regionEpoch());
    CanonicalGameplayRegionRuntimeStatus prior = statusRepository.find(source).orElse(null);
    if (prior == null && expectedRegionEpoch != 1L) throw unavailable();
    if (prior != null && prior.regionEpoch() != expectedRegionEpoch) throw unavailable();

    CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation preparation;
    CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant;
    boolean reusingRegionGrant = false;
    if (prior == null) {
      if (existingRegionInstallation != null) throw unavailable();
      preparation = statusRepository.findTimelinePreparation(source).orElse(null);
      if (preparation == null) {
        CanonicalGameplayRegionLeaseRedisOwner.TimelineAbsenceProof absenceProof =
            redisOwner.requireTimelineAbsent(source, incarnation);
        preparation =
            statusRepository.prepareInitialTimeline(
                evidence,
                worldRequest,
                initialResolved.resolvedRegion,
                source,
                incarnation,
                absenceProof,
                clock.instant());
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
      Optional<CanonicalGameplayRegionRuntimeStatusRepository.InitialInitializationPermit> permit =
          statusRepository.beginInitialTimeline(preparation, clock.instant());
      grant =
          redisOwner.initializeOrResumeTimeline(
              preparation, permit.orElse(null), leaseTtl, runtimeIdentity);
      redisOwner.verifyInitializedTimeline(grant, preparation);
      requireSameIncarnation(incarnation, readCurrentIncarnation(source));
      ResolvedBridgeSource afterInitWorld = resolveExactBridgeSource(worldRequest, evidence);
      requireSameSource(source, afterInitWorld.source);
      bindingInventory.requireSameProvisionalSource(
          evidence, readCurrentProvisionalEvidence(transitionId));
      preparation =
          statusRepository.markRedisInitialized(
              preparation,
              grant.tokenCorrelation(),
              CanonicalGameplayRegionBindingBridgeProofs.initialMetaDigest(
                  preparation.tenantRegionTag(),
                  preparation.operationId(),
                  preparation.sourceDigest()),
              clock.instant());
    } else {
      preparation =
          statusRepository
              .findTimelinePreparation(source)
              .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
      boolean sameInitializationAttempt =
          preparation.transitionId().equals(evidence.transition().transitionId());
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
      if (existingRegionInstallation == null) {
        grant = redisOwner.acquire(source, expectedRegionEpoch, leaseTtl, runtimeIdentity);
      } else {
        requireReusableRegionGrant(
            existingRegionInstallation, source, incarnation, prior, expectedRegionEpoch);
        grant = existingRegionInstallation.grant;
        reusingRegionGrant = true;
      }
    }
    if (reusingRegionGrant) {
      redisOwner.requireGrantScope(
          grant, existingRegionInstallation.source, expectedRegionEpoch, runtimeIdentity);
    } else {
      redisOwner.requireGrantScope(grant, source, expectedRegionEpoch, runtimeIdentity);
    }
    CanonicalGameplayRegionRuntimeStatusRepository.Source afterAcquire =
        resolveExactSource(worldRequest, readCurrentProvisionalEvidence(transitionId));
    requireSameSource(source, afterAcquire);
    redisOwner.renew(grant, leaseTtl);
    redisOwner.requireCurrent(grant);
    requireSameIncarnation(incarnation, readCurrentIncarnation(source));
    redisOwner.verifyInitializedTimeline(grant, preparation);

    Instant installedAt = clock.instant();
    CanonicalGameplayRegionRuntimeStatus installed =
        statusRepository.installOwner(
            source,
            expectedRegionEpoch,
            prior,
            grant.tokenCorrelation(),
            grant.ownerService(),
            grant.ownerInstanceId(),
            installedAt);

    redisOwner.requireCurrent(grant);
    redisOwner.verifyInitializedTimeline(grant, preparation);
    CanonicalGameplayRegionRuntimeStatus observed =
        statusRepository.find(source).orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!installed.equals(observed) || observed.paused()) throw unavailable();
    CanonicalGameplayRegionRuntimeStatusRepository.Source afterInstall =
        resolveExactSource(worldRequest, readCurrentProvisionalEvidence(transitionId));
    requireSameSource(source, afterInstall);
    redisOwner.requireCurrent(grant);
    requireSameIncarnation(incarnation, readCurrentIncarnation(source));
    redisOwner.verifyInitializedTimeline(grant, preparation);
    statusRepository.markTimelineInstalled(preparation, installed, clock.instant());
    redisOwner.requireCurrent(grant);
    redisOwner.verifyInitializedTimeline(grant, preparation);
    requireSameIncarnation(incarnation, readCurrentIncarnation(source));
    requireSameSource(
        source, resolveExactSource(worldRequest, readCurrentProvisionalEvidence(transitionId)));
    CanonicalGameplayRegionRuntimeStatus finalStatus =
        statusRepository.find(source).orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!installed.equals(finalStatus) || finalStatus.paused()) throw unavailable();
    return new Installation(
        transitionId, worldRequest, evidence, source, incarnation, preparation, grant, installed);
  }

  private void requireReusableRegionGrant(
      Installation existing,
      CanonicalGameplayRegionRuntimeStatusRepository.Source candidateSource,
      CanonicalGameplayRegionIncarnationSource.StableEvidence currentIncarnation,
      CanonicalGameplayRegionRuntimeStatus currentStatus,
      long expectedRegionEpoch) {
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
        statusRepository
            .findTimelinePreparation(existing.preparation.source())
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!statusRepository.matchesCurrentInstalledTimelineInitialization(
        existing.preparation,
        persistedPreparation,
        candidateSource,
        currentIncarnation,
        currentStatus)) {
      throw unavailable();
    }
    redisOwner.requireGrantScope(
        existing.grant, existing.source, expectedRegionEpoch, runtimeIdentity);
    redisOwner.requireCurrent(existing.grant);
    redisOwner.verifyInitializedTimeline(existing.grant, persistedPreparation);
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
    if (!installation.owner.equals(this)) throw unavailable();
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
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(installation, "installation");
    Objects.requireNonNull(finalAdmissionSource, "finalAdmissionSource");
    Objects.requireNonNull(reference, "runtimeReference");
    if (!installation.owner.equals(this)) throw unavailable();

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
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
    var identity = candidate.bindingIdentity();
    if (!reference.runtimeEntityId().tenantId().equals(identity.tenantId())
        || !reference
            .runtimeEntityId()
            .gameInstanceId()
            .equals(identity.gameInstanceId().toString())
        || reference.runtimeEntityId().value() != acknowledgement.runtimeEntityId()
        || !reference.assignmentUuid().equals(acknowledgement.assignmentId())
        || !reference
            .assignmentUuid()
            .toString()
            .equals(installation.worldRequest.getAssignmentUuid())
        || !acknowledgement
            .assignmentIntentDigest()
            .equals(installation.worldRequest.getAssignmentIntentDigest())
        || !reference.canonicalAccountUuid().equals(identity.accountId())
        || !reference.characterUuid().equals(identity.characterId())
        || !reference.intentDigest().equals(installation.worldRequest.getAssignmentIntentDigest())
        || !reference.target().equals(installation.worldRequest.getExpectedTarget())
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
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference,
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
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
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
      Installation installation,
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
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
      Installation installation,
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
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
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
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
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference,
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
    requireNoAmbientTransaction();
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        bindingInventory.readProvisionalCasEvidence(transitionId);
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

  private CanonicalGameplayRegionRuntimeStatusRepository.Source resolveExactSource(
      CanonicalInitialPlayerLocationRequest request,
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {
    return resolveExactBridgeSource(request, evidence).source;
  }

  private ResolvedBridgeSource resolveExactBridgeSource(
      CanonicalInitialPlayerLocationRequest request,
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {
    requireNoAmbientTransaction();
    var identity = evidence.candidate().identity();
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
        worldRegionResolver.resolve(request);
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

    boolean matchesCurrentEntityReference(
        CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
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
