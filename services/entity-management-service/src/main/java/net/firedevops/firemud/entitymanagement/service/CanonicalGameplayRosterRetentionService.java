package net.firedevops.firemud.entitymanagement.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered owner-local bounded sweep; authenticated owner calls never run in SQL transactions.
 */
public final class CanonicalGameplayRosterRetentionService {
  private final CanonicalGameplayRosterRetentionRepository repository;
  private final GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient;
  private final TransactionOperations transactions;
  private final CanonicalGameplayRosterRetentionPolicy policy;
  private final MeterRegistry metrics;
  private final AtomicLong oldestBlocking = new AtomicLong();
  private final AtomicLong cleanupLag = new AtomicLong();
  private UUID cursor;
  private UUID closedAdmissionCursor;

  public CanonicalGameplayRosterRetentionService(
      CanonicalGameplayRosterRetentionRepository repository,
      GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient,
      TransactionOperations transactions,
      CanonicalGameplayRosterRetentionPolicy policy,
      MeterRegistry metrics) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.ownerClient = Objects.requireNonNull(ownerClient, "ownerClient");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    Gauge.builder(
            "firemud.canonical.roster.oldest_blocking_seconds",
            oldestBlocking,
            AtomicLong::doubleValue)
        .register(metrics);
    Gauge.builder(
            "firemud.canonical.roster.cleanup_lag_seconds", cleanupLag, AtomicLong::doubleValue)
        .register(metrics);
  }

  @Scheduled(fixedDelayString = "${firemud.canonical-roster.retention.cleanup-cadence}")
  public synchronized void sweep() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("CANONICAL_ROSTER_SWEEP_REQUIRES_NO_AMBIENT_TRANSACTION");
    }
    UUID after = cursor;
    var candidates =
        Objects.requireNonNull(
            transactions.execute(ignored -> repository.candidates(after, policy.batchSize())));
    if (candidates.isEmpty()) {
      cursor = null;
    }
    for (var candidate : candidates) {
      cursor = candidate.binding().snapshot().snapshotUuid();
      try {
        boolean changed;
        if ("RETIRED".equals(candidate.lifecycleState())) {
          changed =
              Boolean.TRUE.equals(transactions.execute(ignored -> repository.dispose(candidate)));
          metrics
              .counter(
                  "firemud.canonical.roster.cleanup", "result", changed ? "disposed" : "protected")
              .increment();
        } else {
          var proof =
              CanonicalGameplayRosterReferenceEvidence.requireResponse(
                  candidate.ownerRequest(),
                  ownerClient.getCanonicalGameplayRosterReference(candidate.ownerRequest()));
          if (proof.getState()
              != CanonicalGameplayRosterReferenceState
                  .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED) {
            metrics.counter("firemud.canonical.roster.cleanup", "result", "protected").increment();
            continue;
          }
          CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
          changed =
              Boolean.TRUE.equals(
                  transactions.execute(
                      ignored -> repository.observeRetirement(candidate, proof, policy)));
          metrics
              .counter(
                  "firemud.canonical.roster.cleanup", "result", changed ? "retired" : "protected")
              .increment();
        }
      } catch (RuntimeException failure) {
        metrics.counter("firemud.canonical.roster.cleanup", "result", "failure").increment();
      }
    }
    UUID closedAfter = closedAdmissionCursor;
    var closures =
        Objects.requireNonNull(
            transactions.execute(
                ignored -> repository.closedAdmissionCandidates(closedAfter, policy.batchSize())));
    if (closures.isEmpty()) closedAdmissionCursor = null;
    for (var closure : closures) {
      closedAdmissionCursor = closure.operationUuid();
      try {
        var owner =
            ownerClient.getCanonicalGameplayRosterReference(
                closure.response().getRequest().getOriginalSelection());
        boolean removed =
            Boolean.TRUE.equals(
                transactions.execute(ignored -> repository.purgeClosedAdmission(closure, owner)));
        metrics
            .counter(
                "firemud.canonical.roster.cleanup",
                "result",
                removed ? "admission_purged" : "protected")
            .increment();
      } catch (RuntimeException failure) {
        metrics.counter("firemud.canonical.roster.cleanup", "result", "failure").increment();
      }
    }
    transactions.executeWithoutResult(
        ignored -> repository.purgeOrphanOpenAdmissions(policy, policy.batchSize()));
    transactions.executeWithoutResult(
        ignored -> repository.purgeDisposedTombstones(policy.batchSize()));
    var health = Objects.requireNonNull(transactions.execute(ignored -> repository.health()));
    oldestBlocking.set(health.oldestBlockingSeconds());
    cleanupLag.set(health.cleanupLagSeconds());
  }
}
