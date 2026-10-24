package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.Health;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.TargetCandidate;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameplayRosterReferenceRetentionServiceTest {
  private final CanonicalGameplayRosterReferenceOwner owner =
      mock(CanonicalGameplayRosterReferenceOwner.class);
  private final CanonicalGameplayRosterReferenceRepository repository =
      mock(CanonicalGameplayRosterReferenceRepository.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final CanonicalGameplayRosterReferenceRetentionService service =
      new CanonicalGameplayRosterReferenceRetentionService(
          owner,
          repository,
          localTransactions(),
          new CanonicalGameplayRosterReferencePolicy(
              Duration.ofHours(8),
              Duration.ofHours(2),
              Duration.ofHours(8),
              Duration.ofMinutes(3),
              2),
          metrics);

  @BeforeEach
  void retainedTransferHealth() {
    when(repository.transferHealth())
        .thenReturn(new CanonicalGameplayRosterReferenceRepository.TransferHealth(0, 0));
  }

  @Test
  void boundedConfirmedReceiptCleanupAndHealthContinueAfterTargetFailure() {
    when(repository.targetCandidates(null, 2)).thenReturn(List.of(candidate(1, "RETIRED")));
    when(repository.purgeRetiredTarget(any()))
        .thenThrow(new IllegalStateException("Entity observation outstanding"));
    when(repository.purgeConfirmedRetirementAcknowledgements(2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return 1;
            });
    when(repository.transferHealth())
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return new CanonicalGameplayRosterReferenceRepository.TransferHealth(19, 7);
            });
    when(repository.health()).thenReturn(new Health(0, 0));
    service.sweep();
    verify(repository).purgeConfirmedRetirementAcknowledgements(2);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.transfer_cleanup")
                .tag("result", "purged")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.oldest_pending_transfer_seconds")
                .gauge()
                .value())
        .isEqualTo(19);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.transfer_cleanup_lag_seconds")
                .gauge()
                .value())
        .isEqualTo(7);
  }

  @Test
  void transferCleanupFailureKeepsOtherCleanupAndHealthAvailable() {
    when(repository.targetCandidates(null, 2)).thenReturn(List.of());
    when(repository.purgeConfirmedRetirementAcknowledgements(2))
        .thenThrow(new IllegalStateException("storage unavailable"));
    when(repository.health()).thenReturn(new Health(3, 2));
    service.sweep();
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.transfer_cleanup")
                .tag("result", "failure")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.oldest_blocking_seconds")
                .gauge()
                .value())
        .isEqualTo(3);
  }

  @Test
  void unavailableTransferHealthReportsUnknownInsteadOfAHealthyZero() {
    when(repository.targetCandidates(null, 2)).thenReturn(List.of());
    when(repository.health()).thenReturn(new Health(0, 0));
    when(repository.transferHealth()).thenThrow(new IllegalStateException("health unavailable"));
    service.sweep();
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.oldest_pending_transfer_seconds")
                .gauge()
                .value())
        .isEqualTo(-1);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.transfer_cleanup_lag_seconds")
                .gauge()
                .value())
        .isEqualTo(-1);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.transfer_cleanup")
                .tag("result", "health_failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void blockingHealthFailureInvalidatesHealthyGaugesAndLaterObservationRecoversThem() {
    when(repository.targetCandidates(null, 2)).thenReturn(List.of());
    when(repository.health())
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return new Health(0, 0);
            })
        .thenThrow(new IllegalStateException("blocking health unavailable"))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return new Health(13, 9);
            });
    var oldest = metrics.get("firemud.canonical.roster.reference.oldest_blocking_seconds").gauge();
    var lag = metrics.get("firemud.canonical.roster.reference.cleanup_lag_seconds").gauge();

    service.sweep();
    assertThat(oldest.value()).isZero();
    assertThat(lag.value()).isZero();

    service.sweep();
    assertThat(oldest.value()).isEqualTo(-1);
    assertThat(lag.value()).isEqualTo(-1);
    assertThat(outcomes("health_failure")).isEqualTo(1);

    service.sweep();
    assertThat(oldest.value()).isEqualTo(13);
    assertThat(lag.value()).isEqualTo(9);
    assertThat(outcomes("health_failure")).isEqualTo(1);
    verify(repository, times(3)).health();
    assertOutsideTransaction();
  }

  @Test
  void candidateReadFailureRetriesTheSameCursorAndContinuesCleanupAndBothHealthStages() {
    var first = candidate(1, "ACTIVE");
    var last = candidate(2, "ACTIVE");
    var retired = candidate(3, "RETIRED");
    when(repository.targetCandidates(null, 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of(first, last);
            });
    when(repository.targetCandidates(last.fenceUuid(), 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              throw new IllegalStateException("candidate storage unavailable");
            })
        .thenThrow(new IllegalStateException("candidate storage still unavailable"))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of(retired);
            });
    when(repository.purgeRetiredTarget(retired)).thenReturn(true);
    when(repository.purgeConfirmedRetirementAcknowledgements(2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return 1;
            });
    when(repository.transferHealth())
        .thenReturn(new CanonicalGameplayRosterReferenceRepository.TransferHealth(0, 0))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return new CanonicalGameplayRosterReferenceRepository.TransferHealth(19, 7);
            })
        .thenThrow(new IllegalStateException("transfer health unavailable"))
        .thenReturn(new CanonicalGameplayRosterReferenceRepository.TransferHealth(23, 11));
    when(repository.health())
        .thenReturn(new Health(0, 0))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return new Health(13, 9);
            })
        .thenThrow(new IllegalStateException("blocking health unavailable"))
        .thenReturn(new Health(17, 12));
    var blocking =
        metrics.get("firemud.canonical.roster.reference.oldest_blocking_seconds").gauge();
    var lag = metrics.get("firemud.canonical.roster.reference.cleanup_lag_seconds").gauge();
    var pending =
        metrics.get("firemud.canonical.roster.reference.oldest_pending_transfer_seconds").gauge();
    var transferLag =
        metrics.get("firemud.canonical.roster.reference.transfer_cleanup_lag_seconds").gauge();

    service.sweep();
    assertThat(blocking.value()).isZero();
    assertThat(lag.value()).isZero();
    assertThat(pending.value()).isZero();
    assertThat(transferLag.value()).isZero();

    service.sweep();
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(blocking.value()).isEqualTo(13);
    assertThat(lag.value()).isEqualTo(9);
    assertThat(pending.value()).isEqualTo(19);
    assertThat(transferLag.value()).isEqualTo(7);

    service.sweep();
    assertThat(outcomes("failure")).isEqualTo(2);
    assertThat(blocking.value()).isEqualTo(-1);
    assertThat(lag.value()).isEqualTo(-1);
    assertThat(pending.value()).isEqualTo(-1);
    assertThat(transferLag.value()).isEqualTo(-1);
    assertThat(outcomes("health_failure")).isEqualTo(1);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.transfer_cleanup")
                .tag("result", "health_failure")
                .counter()
                .count())
        .isEqualTo(1);

    service.sweep();
    assertThat(outcomes("failure")).isEqualTo(2);
    assertThat(outcomes("purged")).isEqualTo(1);
    assertThat(blocking.value()).isEqualTo(17);
    assertThat(lag.value()).isEqualTo(12);
    assertThat(pending.value()).isEqualTo(23);
    assertThat(transferLag.value()).isEqualTo(11);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.transfer_cleanup")
                .tag("result", "purged")
                .counter()
                .count())
        .isEqualTo(4);
    var pages = inOrder(repository);
    pages.verify(repository).targetCandidates(null, 2);
    pages.verify(repository, times(3)).targetCandidates(last.fenceUuid(), 2);
    verify(repository, times(4)).purgeConfirmedRetirementAcknowledgements(2);
    verify(repository, times(4)).transferHealth();
    verify(repository, times(4)).health();
    verify(repository).purgeRetiredTarget(retired);
    assertOutsideTransaction();
  }

  @Test
  void boundedPagesPassProtectedAndUnavailableTargetsAndResetOnlyAfterAnEmptyPage() {
    var protectedTarget = candidate(1, "ACTIVE");
    var unavailable = candidate(2, "ACTIVE");
    var eligible = candidate(3, "ACTIVE");
    var retired = candidate(4, "RETIRED");
    when(repository.targetCandidates(null, 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of(protectedTarget, unavailable);
            })
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of();
            });
    when(repository.targetCandidates(unavailable.fenceUuid(), 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of(eligible, retired);
            });
    when(repository.targetCandidates(retired.fenceUuid(), 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of();
            });
    when(owner.retireTarget(protectedTarget.original()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return false;
            });
    when(owner.retireTarget(unavailable.original()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              throw new IllegalStateException("Owner unavailable");
            });
    when(owner.retireTarget(eligible.original()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return true;
            });
    when(repository.purgeRetiredTarget(retired))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return true;
            });
    when(repository.health())
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return new Health(91, 17);
            });

    service.sweep();
    verify(repository, never()).purgeRetiredTarget(any());
    assertThat(outcomes("protected")).isEqualTo(1);
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.oldest_blocking_seconds")
                .gauge()
                .value())
        .isEqualTo(91);
    assertThat(
            metrics.get("firemud.canonical.roster.reference.cleanup_lag_seconds").gauge().value())
        .isEqualTo(17);
    service.sweep();
    service.sweep();
    org.mockito.Mockito.doReturn(new Health(0, 0)).when(repository).health();
    service.sweep();

    var pages = inOrder(repository);
    pages.verify(repository).targetCandidates(null, 2);
    pages.verify(repository).targetCandidates(unavailable.fenceUuid(), 2);
    pages.verify(repository).targetCandidates(retired.fenceUuid(), 2);
    pages.verify(repository).targetCandidates(null, 2);
    verify(owner, never()).retireTarget(retired.original());
    verify(owner, times(3)).retireTarget(any());
    verify(repository).purgeRetiredTarget(retired);
    assertThat(outcomes("retired")).isEqualTo(1);
    assertThat(outcomes("purged")).isEqualTo(1);
    assertThat(outcomes("protected")).isEqualTo(1);
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.oldest_blocking_seconds")
                .gauge()
                .value())
        .isZero();
    assertThat(
            metrics.get("firemud.canonical.roster.reference.cleanup_lag_seconds").gauge().value())
        .isZero();
    assertOutsideTransaction();
  }

  @Test
  void refusedPurgeIsProtectedAndFailedPurgeHasAFailureOutcome() {
    var held = candidate(1, "RETIRED");
    var failed = candidate(2, "RETIRED");
    when(repository.targetCandidates(null, 2)).thenReturn(List.of(held, failed));
    when(repository.purgeRetiredTarget(held)).thenReturn(false);
    when(repository.purgeRetiredTarget(failed)).thenThrow(new IllegalStateException("Still held"));
    when(repository.health()).thenReturn(new Health(5, 9));

    service.sweep();

    verifyNoInteractions(owner);
    assertThat(outcomes("protected")).isEqualTo(1);
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(
            metrics
                .find("firemud.canonical.roster.reference.cleanup")
                .tag("result", "purged")
                .counter())
        .isNull();
  }

  @Test
  void refusesBothActualAndSynchronizationOnlyAmbientTransactionsBeforeAnyWork() {
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(service::sweep)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("CANONICAL_ROSTER_REFERENCE_SWEEP_REQUIRES_NO_AMBIENT_TRANSACTION");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(service::sweep)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("CANONICAL_ROSTER_REFERENCE_SWEEP_REQUIRES_NO_AMBIENT_TRANSACTION");
      verifyNoInteractions(repository, owner);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private double outcomes(String result) {
    return metrics
        .get("firemud.canonical.roster.reference.cleanup")
        .tag("result", result)
        .counter()
        .count();
  }

  private static TargetCandidate candidate(long id, String state) {
    return new TargetCandidate(
        new UUID(0, id),
        GetCanonicalGameplayRosterReferenceRequest.newBuilder()
            .setOperationUuid(new UUID(1, id).toString())
            .build(),
        state);
  }

  // This callback seam checks orchestration boundaries; it is not a database transaction proof
  // or evidence that the mocked reference owner has obtained genuine producer authority.
  private static TransactionOperations localTransactions() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        assertOutsideTransaction();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
          return action.doInTransaction(new SimpleTransactionStatus());
        } finally {
          TransactionSynchronizationManager.clear();
        }
      }
    };
  }

  private static void assertSqlTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
  }

  private static void assertOutsideTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }
}
