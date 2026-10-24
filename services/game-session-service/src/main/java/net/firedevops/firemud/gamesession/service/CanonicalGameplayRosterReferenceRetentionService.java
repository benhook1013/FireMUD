package net.firedevops.firemud.gamesession.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered, finite, bounded cleanup for the exact original roster reference owner. */
public final class CanonicalGameplayRosterReferenceRetentionService {
  private final CanonicalGameplayRosterReferenceOwner owner;
  private final CanonicalGameplayRosterReferenceRepository repository;
  private final TransactionOperations transactions;
  private final CanonicalGameplayRosterReferencePolicy policy;
  private final MeterRegistry metrics;
  private final AtomicReference<Double> oldestBlocking = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> cleanupLag = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> oldestPendingTransfer = new AtomicReference<>(Double.NaN);
  private final AtomicReference<Double> transferCleanupLag = new AtomicReference<>(Double.NaN);
  private UUID cursor;

  public CanonicalGameplayRosterReferenceRetentionService(
      CanonicalGameplayRosterReferenceOwner owner,
      CanonicalGameplayRosterReferenceRepository repository,
      TransactionOperations transactions,
      CanonicalGameplayRosterReferencePolicy policy,
      MeterRegistry metrics) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    Gauge.builder(
            "firemud.canonical.roster.reference.oldest_pending_transfer_seconds",
            oldestPendingTransfer,
            value -> value.get().doubleValue())
        .register(metrics);
    Gauge.builder(
            "firemud.canonical.roster.reference.transfer_cleanup_lag_seconds",
            transferCleanupLag,
            value -> value.get().doubleValue())
        .register(metrics);
    Gauge.builder(
            "firemud.canonical.roster.reference.oldest_blocking_seconds",
            oldestBlocking,
            value -> value.get().doubleValue())
        .register(metrics);
    Gauge.builder(
            "firemud.canonical.roster.reference.cleanup_lag_seconds",
            cleanupLag,
            value -> value.get().doubleValue())
        .register(metrics);
  }

  @Scheduled(fixedDelayString = "${firemud.canonical-roster.reference.cleanup-cadence}")
  public synchronized void sweep() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "CANONICAL_ROSTER_REFERENCE_SWEEP_REQUIRES_NO_AMBIENT_TRANSACTION");
    }
    UUID after = cursor;
    List<CanonicalGameplayRosterReferenceRepository.TargetCandidate> candidates = List.of();
    try {
      candidates =
          Objects.requireNonNull(
              transactions.execute(
                  ignored -> repository.targetCandidates(after, policy.batchSize())));
      if (candidates.isEmpty()) {
        cursor = null;
      }
    } catch (RuntimeException failure) {
      metrics
          .counter("firemud.canonical.roster.reference.cleanup", "result", "failure")
          .increment();
    }
    for (var candidate : candidates) {
      cursor = candidate.fenceUuid();
      try {
        boolean changed =
            "RETIRED".equals(candidate.state())
                ? Boolean.TRUE.equals(
                    transactions.execute(ignored -> repository.purgeRetiredTarget(candidate)))
                : owner.retireTarget(candidate.original());
        metrics
            .counter(
                "firemud.canonical.roster.reference.cleanup",
                "result",
                changed
                    ? ("RETIRED".equals(candidate.state()) ? "purged" : "retired")
                    : "protected")
            .increment();
      } catch (RuntimeException failure) {
        metrics
            .counter("firemud.canonical.roster.reference.cleanup", "result", "failure")
            .increment();
      }
    }
    try {
      int purged =
          Objects.requireNonNull(
              transactions.execute(
                  ignored ->
                      repository.purgeConfirmedRetirementAcknowledgements(policy.batchSize())));
      metrics
          .counter(
              "firemud.canonical.roster.reference.transfer_cleanup",
              "result",
              purged > 0 ? "purged" : "protected")
          .increment();
    } catch (RuntimeException failure) {
      metrics
          .counter("firemud.canonical.roster.reference.transfer_cleanup", "result", "failure")
          .increment();
    }
    try {
      var transferHealth =
          Objects.requireNonNull(transactions.execute(ignored -> repository.transferHealth()));
      oldestPendingTransfer.set((double) transferHealth.oldestPendingSeconds());
      transferCleanupLag.set((double) transferHealth.cleanupLagSeconds());
    } catch (RuntimeException failure) {
      oldestPendingTransfer.set(Double.NaN);
      transferCleanupLag.set(Double.NaN);
      metrics
          .counter(
              "firemud.canonical.roster.reference.transfer_cleanup", "result", "health_failure")
          .increment();
    }
    try {
      var health = Objects.requireNonNull(transactions.execute(ignored -> repository.health()));
      oldestBlocking.set((double) health.oldestBlockingSeconds());
      cleanupLag.set((double) health.cleanupLagSeconds());
    } catch (RuntimeException failure) {
      oldestBlocking.set(Double.NaN);
      cleanupLag.set(Double.NaN);
      metrics
          .counter("firemud.canonical.roster.reference.cleanup", "result", "health_failure")
          .increment();
    }
  }
}
