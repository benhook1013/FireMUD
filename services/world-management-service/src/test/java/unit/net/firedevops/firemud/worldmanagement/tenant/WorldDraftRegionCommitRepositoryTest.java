package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Connection;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class WorldDraftRegionCommitRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final WorldDesignPublicationFenceRepository fence =
      mock(WorldDesignPublicationFenceRepository.class);
  private final WorldDraftRegionCommitRepository repository =
      new WorldDraftRegionCommitRepository(dsl, fence, new ObjectMapper());
  private final WorldDraftRegionCommitPlan plan = mock(WorldDraftRegionCommitPlan.class);

  @AfterEach
  void clearTransactionFixture() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void absentTransactionCannotResolveOrTouchHistory() {
    assertThatThrownBy(() -> repository.store(plan))
        .hasMessageContaining("writable READ COMMITTED");
    verifyNoInteractions(dsl, fence, plan);
  }

  @Test
  void readOnlyTransactionCannotResolveOrTouchHistory() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);
    assertThatThrownBy(() -> repository.store(plan))
        .hasMessageContaining("writable READ COMMITTED");
    verifyNoInteractions(dsl, fence, plan);
  }

  @Test
  void repeatableReadCannotUseReadCommittedReplayAndOwnerLockOrdering() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_REPEATABLE_READ);
    assertThatThrownBy(() -> repository.store(plan))
        .hasMessageContaining("writable READ COMMITTED");
    verifyNoInteractions(dsl, fence, plan);
  }
}
