package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountBillingCustomerRepositoryTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID OPERATION = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountBillingCustomerRepository customers =
      new AccountBillingCustomerRepository(dsl);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void mutationsAndLockedReadsRequireWritableTransactionBeforeSql() {
    assertTransactionRefusal();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertTransactionRefusal();
    verifyNoInteractions(dsl);
  }

  private void assertTransactionRefusal() {
    assertThatThrownBy(() -> customers.retainIntent(ACCOUNT, OPERATION, KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    assertThatThrownBy(() -> customers.findByAccountForUpdate(ACCOUNT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    assertThatThrownBy(
            () ->
                customers.recordObservation(
                    ACCOUNT, OPERATION, 1, UUID.randomUUID(), Observation.AMBIGUOUS, null, DIGEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
  }

  @Test
  void rejectsNilOrConflatedOperationIdentitiesBeforeSql() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> customers.retainIntent(new UUID(0L, 0L), OPERATION, KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> customers.retainIntent(ACCOUNT, OPERATION, OPERATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must differ");
    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsMissingEvidenceInvalidVersionAndInventedConfirmationBeforeSql() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(
            () ->
                customers.recordObservation(
                    ACCOUNT, OPERATION, 0, UUID.randomUUID(), Observation.AMBIGUOUS, null, DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                customers.recordObservation(
                    ACCOUNT,
                    OPERATION,
                    Long.MAX_VALUE,
                    UUID.randomUUID(),
                    Observation.AMBIGUOUS,
                    null,
                    DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                customers.recordObservation(
                    ACCOUNT,
                    OPERATION,
                    1,
                    UUID.randomUUID(),
                    Observation.AMBIGUOUS,
                    "cus_unverified",
                    DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                customers.recordObservation(
                    ACCOUNT,
                    OPERATION,
                    1,
                    UUID.randomUUID(),
                    Observation.CONFIRMED,
                    "raw-customer-payload",
                    DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                customers.recordObservation(
                    ACCOUNT,
                    OPERATION,
                    1,
                    UUID.randomUUID(),
                    Observation.PROVIDER_ABSENT,
                    null,
                    "missing-digest"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                customers.recordObservation(
                    ACCOUNT, OPERATION, 1, UUID.randomUUID(), Observation.NONE, null, DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }
}
