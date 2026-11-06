package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Request;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** LOCAL composition guards only; physical capture/commit/readback belongs to PostgreSQL tests. */
class GameSessionRuntimeFamilyCatalogRepositoryTest {
  @Test
  void captureAndReadbackRejectBothAmbientStatesBeforeSqlOrTransactionCreation() {
    DSLContext dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var owner =
        new GameSessionRuntimeFamilyCatalogRepository(dsl, transactions, "gs_owner", "test");
    var request = new Request(UUID.randomUUID(), "test");
    for (boolean synchronizationOnly : new boolean[] {false, true}) {
      try {
        if (synchronizationOnly) TransactionSynchronizationManager.initSynchronization();
        else TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> owner.capture(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("independent owner transactions");
        assertThatThrownBy(() -> owner.readExact(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("independent owner transactions");
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    verifyNoInteractions(dsl, transactions);
  }

  @Test
  void workloadNamespaceIsFixedConfigurationNotCallerSelectedAuthority() {
    DSLContext dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var owner =
        new GameSessionRuntimeFamilyCatalogRepository(dsl, transactions, "gs_owner", "test");
    var wrong = new Request(UUID.randomUUID(), "other");
    assertThatThrownBy(() -> owner.capture(wrong))
        .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    assertThatThrownBy(() -> owner.readExact(wrong))
        .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    verifyNoInteractions(dsl, transactions);
  }

  @Test
  void invalidConfiguredSchemaOrNamespaceCannotReachSql() {
    DSLContext dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    for (String schema : new String[] {"", "public;select", "MixedCase", "a".repeat(64)}) {
      assertThatThrownBy(
              () ->
                  new GameSessionRuntimeFamilyCatalogRepository(dsl, transactions, schema, "test"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new GameSessionRuntimeFamilyCatalogRepository(dsl, transactions, "gs_owner", "BAD"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl, transactions);
  }
}
