package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic transaction-bound repository boundary tests; no PostgreSQL proof is claimed. */
class AccountSelectedOwnerIntakeSourceReservationRepositoryTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

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
    assertThatThrownBy(() -> repository.readFinalAuthorization(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.readHeldFinalAuthorization(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.findSettlement(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");
    assertThatThrownBy(() -> repository.settleCommittedEmpty(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void expiredRecoveryAttemptsInvalidRowsAndAdvancesCursorPastThem() {
    var dsl = mock(DSLContext.class);
    var first = mock(Record.class);
    var later = mock(Record.class);
    when(first.get("scope_bytes", byte[].class)).thenReturn(new byte[0]);
    when(later.get("scope_bytes", byte[].class))
        .thenThrow(new IllegalStateException("synthetic row failure"));
    when(first.get("recovery_expires_at_epoch_second", Long.class)).thenReturn(10L);
    when(later.get("recovery_expires_at_epoch_second", Long.class)).thenReturn(20L);
    when(first.get("recovery_operation_id", java.util.UUID.class))
        .thenReturn(java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(later.get("recovery_operation_id", java.util.UUID.class))
        .thenReturn(java.util.UUID.fromString("22222222-2222-4222-8222-222222222222"));
    Result<Record> rows = mock(Result.class);
    when(rows.size()).thenReturn(2);
    when(rows.iterator()).thenReturn(List.of(first, later).iterator());
    when(dsl.fetch(anyString(), any(Object[].class))).thenReturn(rows);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    var repository = new AccountSelectedOwnerIntakeSourceReservationRepository(dsl);

    var page = repository.findExpiredPreliminarySourceReadScopes(2, "test", null);

    assertThat(page.scopes()).isEmpty();
    assertThat(page.examinedCount()).isEqualTo(2);
    assertThat(page.nextCursor())
        .isEqualTo(
            new AccountSelectedOwnerIntakeSourceReservationRepository.RecoveryCursor(
                20L, java.util.UUID.fromString("22222222-2222-4222-8222-222222222222")));

    verify(first).get("scope_bytes", byte[].class);
    verify(later).get("scope_bytes", byte[].class);
  }

  @Test
  void expiredRecoveryPropagatesLookupFailuresForTheWholeOwnerTransaction() {
    var dsl = mock(DSLContext.class);
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenThrow(
            new DataAccessException("synthetic lookup failure") {
              private static final long serialVersionUID = 1L;
            });
    TransactionSynchronizationManager.setActualTransactionActive(true);

    var repository = new AccountSelectedOwnerIntakeSourceReservationRepository(dsl);

    assertThatThrownBy(() -> repository.findExpiredPreliminarySourceReadScopes(2, "test", null))
        .isInstanceOf(DataAccessException.class);
  }
}
