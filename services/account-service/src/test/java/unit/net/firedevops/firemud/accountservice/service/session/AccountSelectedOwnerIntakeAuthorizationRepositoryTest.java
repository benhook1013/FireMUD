package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/** Synthetic repository boundary proof; PostgreSQL finalization is covered separately. */
class AccountSelectedOwnerIntakeAuthorizationRepositoryTest {
  @Test
  void finalizationAndExactReadsRequireWritableOwnerTransactions() {
    var dsl = mock(DSLContext.class);
    var repository = new AccountSelectedOwnerIntakeSourceReservationRepository(dsl);

    assertThatThrownBy(() -> repository.finalizeSourceRead(null, null, null, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.findFinalAuthorization(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.readFinalAuthorization(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");

    verifyNoInteractions(dsl);
  }
}
