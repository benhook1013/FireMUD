package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionFreshTenantAssociationRepositoryTest {
  @AfterEach
  void clearTransactionSynchronization() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void doesNotReadOrWriteOutsideTheWritableOwnerTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionFreshTenantAssociationRepository repository =
        new GameSessionFreshTenantAssociationRepository(dsl);

    assertThatThrownBy(() -> repository.associateFresh(null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsReadOnlyOwnerTransactionBeforeTouchingStorage() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionFreshTenantAssociationRepository repository =
        new GameSessionFreshTenantAssociationRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThatThrownBy(() -> repository.associateFresh(null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    verifyNoInteractions(dsl);
  }
}
