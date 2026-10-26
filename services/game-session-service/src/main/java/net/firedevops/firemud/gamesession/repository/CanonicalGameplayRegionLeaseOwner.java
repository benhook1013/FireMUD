package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed owner for an authenticated initial-placement region assignment. It installs and
 * revalidates region ownership only; the region session-binding bridge remains unavailable until
 * its typed runtime-entity identity source is supplied.
 */
final class CanonicalGameplayRegionLeaseOwner {
  private final CanonicalGameplayBindingInventoryRepository bindingInventory;
  private final CanonicalGameplayRegionRuntimeStatusRepository statusRepository;
  private final CanonicalGameplayRegionLeaseRedisOwner redisOwner;
  private final CanonicalInitialPlayerLocationRegionResolver worldRegionResolver;
  private final RuntimeIdentity runtimeIdentity;
  private final Duration leaseTtl;
  private final Clock clock;

  CanonicalGameplayRegionLeaseOwner(
      CanonicalGameplayBindingInventoryRepository bindingInventory,
      CanonicalGameplayRegionRuntimeStatusRepository statusRepository,
      CanonicalGameplayRegionLeaseRedisOwner redisOwner,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock) {
    this.bindingInventory = Objects.requireNonNull(bindingInventory, "bindingInventory");
    this.statusRepository = Objects.requireNonNull(statusRepository, "statusRepository");
    this.redisOwner = Objects.requireNonNull(redisOwner, "redisOwner");
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
    requireNoAmbientTransaction();
    Objects.requireNonNull(transitionId, "transitionId");
    Objects.requireNonNull(worldRequest, "worldRequest");
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        readCurrentProvisionalEvidence(transitionId);
    CanonicalGameplayRegionRuntimeStatusRepository.Source source =
        resolveExactSource(worldRequest, evidence);
    long expectedRegionEpoch =
        exactExpectedRegionEpoch(evidence.candidate().identity().regionEpoch());
    CanonicalGameplayRegionRuntimeStatus prior = statusRepository.find(source).orElse(null);
    if (prior == null && expectedRegionEpoch != 1L) throw unavailable();
    if (prior != null && prior.regionEpoch() != expectedRegionEpoch) throw unavailable();

    CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant =
        redisOwner.acquire(source, expectedRegionEpoch, leaseTtl, runtimeIdentity);
    redisOwner.requireGrantScope(grant, source, expectedRegionEpoch, runtimeIdentity);
    CanonicalGameplayRegionRuntimeStatusRepository.Source afterAcquire =
        resolveExactSource(worldRequest, readCurrentProvisionalEvidence(transitionId));
    requireSameSource(source, afterAcquire);
    redisOwner.renew(grant, leaseTtl);
    redisOwner.requireCurrent(grant);

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
    CanonicalGameplayRegionRuntimeStatus observed =
        statusRepository.find(source).orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!installed.equals(observed) || observed.paused()) throw unavailable();
    CanonicalGameplayRegionRuntimeStatusRepository.Source afterInstall =
        resolveExactSource(worldRequest, readCurrentProvisionalEvidence(transitionId));
    requireSameSource(source, afterInstall);
    redisOwner.requireCurrent(grant);
    return new Installation(transitionId, worldRequest, evidence, source, grant, installed);
  }

  /**
   * Revalidates the same SQL source, World assignment and acquired lease before any later bridge.
   */
  void revalidateBeforeBridge(Installation installation) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(installation, "installation");
    if (!installation.owner.equals(this)) throw unavailable();
    CanonicalGameplayBindingProvisionalCasEvidence evidence =
        readCurrentProvisionalEvidence(installation.transitionId);
    if (!installation.evidence.equals(evidence)) throw unavailable();
    CanonicalGameplayRegionRuntimeStatusRepository.Source currentSource =
        resolveExactSource(installation.worldRequest, evidence);
    requireSameSource(installation.source, currentSource);
    redisOwner.renew(installation.grant, leaseTtl);
    CanonicalGameplayRegionRuntimeStatus current =
        statusRepository
            .find(currentSource)
            .orElseThrow(CanonicalGameplayRegionLeaseOwner::unavailable);
    if (!installation.status.equals(current) || current.paused()) throw unavailable();
    redisOwner.requireCurrent(installation.grant);
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
        || !evidence.transition().transitionId().equals(transitionId)
        || !evidence.candidate().transitionId().equals(transitionId)) {
      throw unavailable();
    }
    return evidence;
  }

  private CanonicalGameplayRegionRuntimeStatusRepository.Source resolveExactSource(
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
    return new CanonicalGameplayRegionRuntimeStatusRepository.Source(
        identity.tenantId(),
        identity.gameInstanceId(),
        assignment.canonicalRegionId(),
        assignment.operationalRegionId(),
        assignment.lifecycleEpoch(),
        assignment.lifecycleRowVersion(),
        assignment.request(),
        assignment.captureId(),
        assignment.graphSha256(),
        assignment.preparationInputDigest());
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
    private final UUID transitionId;
    private final CanonicalInitialPlayerLocationRequest worldRequest;
    private final CanonicalGameplayBindingProvisionalCasEvidence evidence;
    private final CanonicalGameplayRegionRuntimeStatusRepository.Source source;
    private final CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant;
    private final CanonicalGameplayRegionRuntimeStatus status;

    private Installation(
        UUID transitionId,
        CanonicalInitialPlayerLocationRequest worldRequest,
        CanonicalGameplayBindingProvisionalCasEvidence evidence,
        CanonicalGameplayRegionRuntimeStatusRepository.Source source,
        CanonicalGameplayRegionLeaseRedisOwner.LeaseGrant grant,
        CanonicalGameplayRegionRuntimeStatus status) {
      this.owner = CanonicalGameplayRegionLeaseOwner.this;
      this.transitionId = transitionId;
      this.worldRequest = worldRequest;
      this.evidence = evidence;
      this.source = source;
      this.grant = grant;
      this.status = status;
    }
  }
}
