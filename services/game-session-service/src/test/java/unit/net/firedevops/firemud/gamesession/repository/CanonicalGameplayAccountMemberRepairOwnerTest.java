package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.lettuce.core.RedisClient;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameplayAccountMemberRepairOwnerTest {
  @Test
  void refusesSynchronizationOnlyContextBeforeSqlOrRedisWork() {
    DSLContext dsl = mock(DSLContext.class);
    RedisClient redisClient = mock(RedisClient.class);
    var owner =
        new CanonicalGameplayAccountMemberRepairOwner(
            dsl, redisClient, RedisScriptCatalog.loadInstalled(getClass().getClassLoader()));

    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

      assertThatThrownBy(() -> owner.repair(UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Binding-local account member repair phases cannot share an ambient transaction");

      verifyNoInteractions(dsl, redisClient);
    } finally {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.clearSynchronization();
      }
    }
  }
}
