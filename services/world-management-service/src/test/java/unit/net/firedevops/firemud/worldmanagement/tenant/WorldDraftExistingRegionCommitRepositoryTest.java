package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class WorldDraftExistingRegionCommitRepositoryTest {
  @Test
  void refusesStorageOutsideOwnerTransactionBeforeAnyEffect() {
    var dsl = mock(DSLContext.class);
    var fence = mock(WorldDesignPublicationFenceRepository.class);
    var repository = new WorldDraftExistingRegionCommitRepository(dsl, fence, new ObjectMapper());
    assertThatThrownBy(() -> repository.store(null))
        .hasMessageContaining("writable owner transaction");
    verifyNoInteractions(dsl, fence);
  }

  @Test
  void refusesCommittedReadInsideCallerTransaction() {
    var dsl = mock(DSLContext.class);
    var fence = mock(WorldDesignPublicationFenceRepository.class);
    var repository = new WorldDraftExistingRegionCommitRepository(dsl, fence, new ObjectMapper());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.readCommitted(null))
          .hasMessageContaining("committed read");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(dsl, fence);
  }

  @Test
  void refusesReadOnlyStorageBeforeAnyEffect() {
    var dsl = mock(DSLContext.class);
    var fence = mock(WorldDesignPublicationFenceRepository.class);
    var repository = new WorldDraftExistingRegionCommitRepository(dsl, fence, new ObjectMapper());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.store(null))
          .hasMessageContaining("writable owner transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    verifyNoInteractions(dsl, fence);
  }
}
