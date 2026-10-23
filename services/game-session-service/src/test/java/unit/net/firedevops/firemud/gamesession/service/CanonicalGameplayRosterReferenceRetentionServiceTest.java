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
