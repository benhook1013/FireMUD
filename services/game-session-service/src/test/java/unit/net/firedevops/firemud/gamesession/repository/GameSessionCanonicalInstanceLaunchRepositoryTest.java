package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionCanonicalInstanceLaunchRepositoryTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearAmbientState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void rejectsInvalidNamespaceBeforeStorage() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var catalogs =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE);

    assertThatThrownBy(
            () ->
                new GameSessionCanonicalInstanceLaunchRepository(
                    dsl, transactionManager, "Not a DNS label", catalogs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload namespace");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void refusesToCreateInsideAnExistingOwnerTransactionBeforeReadingInputs() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var catalogs =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE);
    var repository =
        new GameSessionCanonicalInstanceLaunchRepository(
            dsl, transactionManager, NAMESPACE, catalogs);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.createStartingLaunch(null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("independent transaction");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void refusesToReadInsideAnExistingOwnerTransactionBeforeStorage() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var catalogs =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE);
    var repository =
        new GameSessionCanonicalInstanceLaunchRepository(
            dsl, transactionManager, NAMESPACE, catalogs);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.randomUUID(),
            NAMESPACE,
            UUID.randomUUID(),
            "world",
            UUID.randomUUID(),
            "operation",
            "descriptor",
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));

    assertThatThrownBy(() -> repository.readByLaunchIdentity(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("independent transaction");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void rejectsSelectorForAnotherNamespaceBeforeOpeningReadTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var catalogs =
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, NAMESPACE);
    var repository =
        new GameSessionCanonicalInstanceLaunchRepository(
            dsl, transactionManager, NAMESPACE, catalogs);
    var request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.randomUUID(),
            "other",
            UUID.randomUUID(),
            "world",
            UUID.randomUUID(),
            "operation",
            "descriptor",
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));

    assertThatThrownBy(() -> repository.readByLaunchIdentity(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from the configured");

    verifyNoInteractions(dsl, transactionManager);
  }
}
