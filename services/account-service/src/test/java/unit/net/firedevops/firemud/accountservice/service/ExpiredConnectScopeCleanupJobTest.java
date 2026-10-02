package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExpiredConnectScopeCleanupJobTest {
  @Test
  void cleanupRepeatsFullBatchesWithOneCutoffAndStopsAfterPartialBatch() {
    AccountConnectScopeRepository repository = mock(AccountConnectScopeRepository.class);
    when(repository.deleteExpiredUnreferenced(any(Instant.class), eq(2))).thenReturn(2, 2, 1);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExpiredConnectScopeCleanupJob job = newJob(repository, meters, 2);

    job.cleanupExpiredConnectScopes();

    ArgumentCaptor<Instant> cutoffs = ArgumentCaptor.forClass(Instant.class);
    verify(repository, times(3)).deleteExpiredUnreferenced(cutoffs.capture(), eq(2));
    assertThat(cutoffs.getAllValues())
        .containsExactly(cutoffs.getValue(), cutoffs.getValue(), cutoffs.getValue());
    verifyNoMoreInteractions(repository);
    assertThat(deletedCount(meters)).isEqualTo(5);
    assertThat(failureCount(meters)).isZero();
  }

  @Test
  void cleanupStopsAfterAnEmptyBatch() {
    AccountConnectScopeRepository repository = mock(AccountConnectScopeRepository.class);
    when(repository.deleteExpiredUnreferenced(any(Instant.class), eq(2))).thenReturn(0);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExpiredConnectScopeCleanupJob job = newJob(repository, meters, 2);

    job.cleanupExpiredConnectScopes();

    verify(repository).deleteExpiredUnreferenced(any(Instant.class), eq(2));
    verifyNoMoreInteractions(repository);
    assertThat(deletedCount(meters)).isZero();
    assertThat(failureCount(meters)).isZero();
  }

  @Test
  void cleanupCapsEachRunAtFiveFullBatches() {
    AccountConnectScopeRepository repository = mock(AccountConnectScopeRepository.class);
    when(repository.deleteExpiredUnreferenced(any(Instant.class), eq(2))).thenReturn(2);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExpiredConnectScopeCleanupJob job = newJob(repository, meters, 2);

    job.cleanupExpiredConnectScopes();

    verify(repository, times(5)).deleteExpiredUnreferenced(any(Instant.class), eq(2));
    verifyNoMoreInteractions(repository);
    assertThat(deletedCount(meters)).isEqualTo(10);
    assertThat(failureCount(meters)).isZero();
  }

  @Test
  void cleanupKeepsEarlierCountsAndRecordsFailureWhenLaterBatchThrows() {
    AccountConnectScopeRepository repository = mock(AccountConnectScopeRepository.class);
    when(repository.deleteExpiredUnreferenced(any(Instant.class), eq(2)))
        .thenReturn(2)
        .thenThrow(new IllegalStateException("private repository detail"));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExpiredConnectScopeCleanupJob job = newJob(repository, meters, 2);

    job.cleanupExpiredConnectScopes();

    verify(repository, times(2)).deleteExpiredUnreferenced(any(Instant.class), eq(2));
    verifyNoMoreInteractions(repository);
    assertThat(deletedCount(meters)).isEqualTo(2);
    assertThat(failureCount(meters)).isEqualTo(1);
  }

  private static ExpiredConnectScopeCleanupJob newJob(
      AccountConnectScopeRepository repository, SimpleMeterRegistry meters, int batchSize) {
    return new ExpiredConnectScopeCleanupJob(repository, meters, batchSize, 60_000);
  }

  private static double deletedCount(SimpleMeterRegistry meters) {
    return meters.get("account.connect_scopes.cleanup.deleted").counter().count();
  }

  private static double failureCount(SimpleMeterRegistry meters) {
    return meters.get("account.connect_scopes.cleanup.failure").counter().count();
  }
}
