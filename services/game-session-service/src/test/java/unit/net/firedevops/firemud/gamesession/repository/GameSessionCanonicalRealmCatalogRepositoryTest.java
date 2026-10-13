package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionCanonicalRealmCatalogRepositoryTest {
  @Test
  void rejectsInvalidNamespaceBeforeAnyStorageTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    assertThatThrownBy(
            () ->
                new GameSessionCanonicalRealmCatalogRepository(
                    dsl, transactionManager, "Not a DNS label"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload namespace");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void rejectsNilTenantSelectorBeforeStartingTheReadTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var repository =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, "test");

    assertThatThrownBy(() -> repository.readByCanonicalTenantId(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonicalTenantId");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void refusesToOpenTheOwnerBoundaryInsideAnExistingTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var repository =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, "test");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  repository.establishFreshPublicRealm(
                      null, null, null, UUID.randomUUID(), UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("before opening a Game Session owner transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void ownerTransactionCatalogReadRejectsMissingOwnerTransactionBeforeStorage() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var repository =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, "test");

    assertThatThrownBy(
            () -> repository.readByCanonicalTenantIdInOwnerTransaction(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires an active owner transaction");

    verifyNoInteractions(dsl, transactionManager);
  }
}
