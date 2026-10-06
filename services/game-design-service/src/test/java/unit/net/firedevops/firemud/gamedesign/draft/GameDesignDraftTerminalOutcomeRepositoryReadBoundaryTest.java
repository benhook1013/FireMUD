package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameDesignDraftTerminalOutcomeRepositoryReadBoundaryTest {
  @Test
  void accountBoundReadRejectsActiveOwnerTransactionBeforeAnyOwnerRead() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new GameDesignDraftTerminalOutcomeRepository(dsl);
    byte[] originalAccountBinding = {1, 2, 3};

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> repository.readAccountBound(originalAccountBinding))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("committed owner transaction boundary");
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
