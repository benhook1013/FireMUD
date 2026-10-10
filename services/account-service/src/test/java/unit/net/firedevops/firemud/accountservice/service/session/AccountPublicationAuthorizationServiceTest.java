package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountPublicationAuthorizationServiceTest {
  @Test
  void persistenceCannotBeCalledWithoutTheWritableAccountOwnerTransaction() {
    var dsl = mock(DSLContext.class);
    var repository = new AccountPublicationAuthorizationRepository(dsl);
    assertThatThrownBy(() -> repository.authorize(null, null, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable READ_COMMITTED");
    verifyNoInteractions(dsl);
  }

  @Test
  void readOnlyOrDifferentIsolationCannotMintPublicationPermission() {
    var dsl = mock(DSLContext.class);
    var repository = new AccountPublicationAuthorizationRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          java.sql.Connection.TRANSACTION_READ_COMMITTED);
      assertThatThrownBy(() -> repository.authorize(null, null, () -> {}))
          .isInstanceOf(IllegalStateException.class);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      assertThatThrownBy(() -> repository.authorize(null, null, () -> {}))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }
}
