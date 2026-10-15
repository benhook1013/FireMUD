package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The SQL owner is only exercised here for its required transaction boundary. */
class AccountStartSessionActivationProtectionRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountStartSessionActivationProtectionRepository repository =
      new AccountStartSessionActivationProtectionRepository(dsl);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void refusesAllOwnerReadsAndWritesOutsideWritableReadCommittedTransaction() {
    assertThatThrownBy(() -> repository.findHistoricalExact(uuid(1), 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction");
    assertThatThrownBy(() -> repository.findSettlementExact(uuid(1), 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction");
    assertThatThrownBy(() -> repository.settleExact(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction");
    assertThatThrownBy(() -> repository.acquireOrReadExact(null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction");

    verifyNoInteractions(dsl);
  }

  private static UUID uuid(long leastSignificantBits) {
    return new UUID(1L, leastSignificantBits);
  }
}
