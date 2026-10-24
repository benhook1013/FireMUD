package net.firedevops.firemud.entitymanagement.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
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
  private final AtomicReference<Double> oldestBlocking = new AtomicReference<>(0.0);
  private final AtomicReference<Double> cleanupLag = new AtomicReference<>(0.0);
  private UUID cursor;
  private UUID closedAdmissionCursor;
  private UUID retirementTransferCursor;

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
            value -> value.get().doubleValue())
        .register(metrics);
    Gauge.builder(
            "firemud.canonical.roster.cleanup_lag_seconds",
            cleanupLag,
            value -> value.get().doubleValue())
        .register(metrics);
  }

  @Scheduled(fixedDelayString = "${firemud.canonical-roster.retention.cleanup-cadence}")
  public synchronized void sweep() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("CANONICAL_ROSTER_SWEEP_REQUIRES_NO_AMBIENT_TRANSACTION");
    }
    runCleanupStage(this::sweepSnapshots);
    runCleanupStage(this::sweepClosedAdmissions);
    runCleanupStage(this::sweepRetirementTransfers);
    runCleanupStage(
        () ->
            transactions.executeWithoutResult(
                ignored -> repository.purgeOrphanOpenAdmissions(policy, policy.batchSize())));
    runCleanupStage(
        () ->
            transactions.executeWithoutResult(
                ignored -> repository.purgeDisposedTombstones(policy.batchSize())));
    runCleanupStage(
        () ->
            transactions.executeWithoutResult(
                ignored -> repository.purgeConfirmedRetirementTransfers(policy.batchSize())));
    refreshHealth();
  }

  private void runCleanupStage(Runnable stage) {
    try {
      stage.run();
    } catch (RuntimeException failure) {
      recordFailure();
    }
  }

  private void sweepSnapshots() {
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
        recordFailure();
      }
    }
  }

  private void sweepClosedAdmissions() {
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
        recordFailure();
      }
    }
  }

  private void sweepRetirementTransfers() {
    UUID after = retirementTransferCursor;
    var transfers =
        Objects.requireNonNull(
            transactions.execute(
                ignored -> repository.retirementTransfers(after, policy.batchSize())));
    if (transfers.isEmpty()) retirementTransferCursor = null;
    for (var transfer : transfers) {
      retirementTransferCursor = transfer.observationUuid();
      try {
        switch (transfer.state()) {
          case "WAITING_RETIREMENT" -> {
            var proof =
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    transfer.original(),
                    ownerClient.getCanonicalGameplayRosterReference(transfer.original()));
            if (proof.getState()
                == CanonicalGameplayRosterReferenceState
                    .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED) {
              CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
              transactions.executeWithoutResult(
                  ignored -> repository.observeWaitingRetirement(transfer, proof));
            } else {
              metrics
                  .counter("firemud.canonical.roster.cleanup", "result", "protected")
                  .increment();
            }
          }
          case "PENDING_ACK" -> {
            var acknowledgement =
                CanonicalGameplayRosterRetirementEvidence.requireAcknowledgementResponse(
                    transfer.acknowledgementRequest(),
                    ownerClient.acknowledgeCanonicalGameplayRosterRetirement(
                        transfer.acknowledgementRequest()));
            boolean acknowledged =
                Boolean.TRUE.equals(
                    transactions.execute(
                        ignored ->
                            repository.recordRetirementAcknowledgement(transfer, acknowledgement)));
            if (acknowledged) {
              metrics
                  .counter("firemud.canonical.roster.cleanup", "result", "acknowledged")
                  .increment();
            }
          }
          case "ACKNOWLEDGED" -> {
            // This page contains only actual ACK bytes committed in an earlier local transaction.
            var confirmation =
                CanonicalGameplayRosterRetirementEvidence.confirmation(
                    transfer.acknowledgementRequest(), transfer.acknowledgement());
            var response =
                CanonicalGameplayRosterRetirementEvidence.requireConfirmationResponse(
                    confirmation,
                    ownerClient.confirmCanonicalGameplayRosterRetirementAcknowledgement(
                        confirmation));
            boolean confirmed =
                Boolean.TRUE.equals(
                    transactions.execute(
                        ignored -> repository.recordRetirementConfirmation(transfer, response)));
            if (confirmed) {
              metrics
                  .counter("firemud.canonical.roster.cleanup", "result", "confirmed")
                  .increment();
            }
          }
          default ->
              throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_TRANSFER_STATE_INVALID");
        }
      } catch (RuntimeException failure) {
        recordFailure();
      }
    }
  }

  private void refreshHealth() {
    try {
      var health = Objects.requireNonNull(transactions.execute(ignored -> repository.health()));
      oldestBlocking.set((double) health.oldestBlockingSeconds());
      cleanupLag.set((double) health.cleanupLagSeconds());
    } catch (RuntimeException failure) {
      oldestBlocking.set(Double.NaN);
      cleanupLag.set(Double.NaN);
      recordFailure();
    }
  }

  private void recordFailure() {
    metrics.counter("firemud.canonical.roster.cleanup", "result", "failure").increment();
  }
}
