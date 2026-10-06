package net.firedevops.firemud.gamedesign.maintenance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionTenantAssociationPayloadRetentionJobTest {
  private final GameSessionTenantAssociationRepository repository =
      Mockito.mock(GameSessionTenantAssociationRepository.class);
  private final GameSessionTenantAssociationPayloadRetentionJob job =
      new GameSessionTenantAssociationPayloadRetentionJob(repository);

  @Test
  void failedCleanupBatchIsRetriedOnTheNextScheduledInvocation() {
    when(repository.purgeExpiredRawPayloads())
        .thenThrow(new IllegalStateException("temporary cleanup failure"))
        .thenReturn(1);

    assertThatThrownBy(job::purgeExpiredPayloadBatch)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("temporary cleanup failure");
    job.purgeExpiredPayloadBatch();

    verify(repository, times(2)).purgeExpiredRawPayloads();
  }
}
