package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/** Synthetic transaction-bound repository boundary tests; no PostgreSQL proof is claimed. */
class AccountSelectedOwnerIntakeSourceReservationRepositoryTest {
  @Test
  void everyReservationRecoveryAbortAndCurrentnessReadRequiresAnOwnerTransaction() {
    var dsl = mock(DSLContext.class);
    var repository = new AccountSelectedOwnerIntakeSourceReservationRepository(dsl);

    assertThatThrownBy(() -> repository.reserveSourceRead(null, null, null, null, null, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.findSourceReadScope(null, null, null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");

    assertThatThrownBy(() -> repository.recoverSourceRead(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.abortSourceRead(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.sourceReadCurrentness(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.readSourceScope(null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");

    verifyNoInteractions(dsl);
  }
}
