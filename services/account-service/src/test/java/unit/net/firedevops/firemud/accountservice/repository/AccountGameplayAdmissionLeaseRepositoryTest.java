package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.util.Arrays;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Foundation storage proof; deliberately supplies no authenticated admission source. */
class AccountGameplayAdmissionLeaseRepositoryTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void remainsUnregisteredWithNoPublicMutationOrAuthorizationFacade() {
    assertThat(AccountGameplayAdmissionLeaseRepository.class.isAnnotationPresent(Repository.class))
        .isFalse();
    assertThat(
            Arrays.stream(AccountGameplayAdmissionLeaseRepository.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .anyMatch(
                    method ->
                        Modifier.isPublic(method.getModifiers())
                            || Modifier.isProtected(method.getModifiers())))
        .isFalse();
  }

  @Test
  void requiresWritableSerializableAccountTransactionBeforeReadingOrWriting() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountGameplayAdmissionLeaseRepository(dsl);
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    assertThatThrownBy(() -> repository.allocate(account, request, lease))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);
    assertThatThrownBy(() -> repository.allocate(account, request, lease))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.allocate(account, request, lease))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsNonV4AccountIdentityBeforeAnyStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountGameplayAdmissionLeaseRepository(dsl);
    assertThatThrownBy(
            () ->
                repository.allocate(
                    UUID.fromString("11111111-1111-1111-8111-111111111111"),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UUIDv4");
    verifyNoInteractions(dsl);
  }

  @Test
  void missingCounterReadbackCannotPersistAnAllocation() {
    assertCounterReadbackRejected(null, "unavailable");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(longs = {0L, -1L})
  void nullOrNonpositiveCounterCannotPersistAnAllocation(Long fence) {
    Record counter = mock(Record.class);
    when(counter.get("last_fence", Long.class)).thenReturn(fence);
    assertCounterReadbackRejected(counter, "invalid");
  }

  @Test
  void insertedAllocationMustBeReadBackBeforeItCanBeReturned() {
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    Record row = allocationRow(account, request, lease, 1L);
    DSLContext dsl = allocationDsl(row, 1);
    assertThat(new AccountGameplayAdmissionLeaseRepository(dsl).allocate(account, request, lease))
        .isEqualTo(
            new AccountGameplayAdmissionLeaseRepository.Allocation(account, request, lease, 1L));
  }

  @Test
  void missingInsertedAllocationReadbackIsNotReturnedAsAnInMemoryIdentity() {
    DSLContext dsl = allocationDsl(null, 1);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionLeaseRepository(dsl)
                    .allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        .hasMessage("Account admission lease allocation readback unavailable");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void allocationInsertRequiresExactlyOneStoredRow(int insertCount) {
    DSLContext dsl = allocationDsl(null, insertCount);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionLeaseRepository(dsl)
                    .allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        .hasMessage("Account admission lease allocation insert unavailable");
  }

  @ParameterizedTest
  @ValueSource(strings = {"account_uuid", "request_id", "lease_id", "lease_fence"})
  void changedInsertedAllocationIdentityFailsClosed(String field) {
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    Record row = allocationRow(account, request, lease, 1L);
    if (field.equals("lease_fence")) when(row.get(field, Long.class)).thenReturn(2L);
    else when(row.get(field, UUID.class)).thenReturn(UUID.randomUUID());
    DSLContext dsl = allocationDsl(row, 1);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionLeaseRepository(dsl).allocate(account, request, lease))
        .hasMessageContaining("identity conflict");
  }

  @Test
  void allocationRecoveryReturnsOnlyOriginalStoredIdentity() {
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    DSLContext dsl = recoveryDsl(allocationRow(account, request, lease, 17L));
    assertThat(new AccountGameplayAdmissionLeaseRepository(dsl).findAllocation(account, request))
        .contains(
            new AccountGameplayAdmissionLeaseRepository.Allocation(account, request, lease, 17L));
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"account_uuid", "request_id"})
  void allocationRecoveryRejectsDifferentStoredOwnerOrRequest(String field) {
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    Record row = allocationRow(account, request, UUID.randomUUID(), 1L);
    when(row.get(field, UUID.class)).thenReturn(UUID.randomUUID());
    DSLContext dsl = recoveryDsl(row);
    assertThatThrownBy(
            () -> new AccountGameplayAdmissionLeaseRepository(dsl).findAllocation(account, request))
        .hasMessageContaining("identity conflict");
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void missingRecoveryRowsRemainEmptyStorageWithoutAllocating() {
    DSLContext allocationDsl = recoveryDsl(null);
    assertThat(
            new AccountGameplayAdmissionLeaseRepository(allocationDsl)
                .findAllocation(UUID.randomUUID(), UUID.randomUUID()))
        .isEmpty();
    verify(allocationDsl, never()).execute(anyString(), any(Object[].class));
    DSLContext operationDsl = recoveryDsl(null);
    assertThat(
            new AccountGameplayAdmissionLeaseRepository(operationDsl)
                .findByRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "spiffe://firemud/ns/test/sa/game-session-service"))
        .isEmpty();
    verify(operationDsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void operationRecoveryRejectsMalformedStoredCarrierWithoutWrites() {
    Record row = mock(Record.class);
    when(row.get("evidence_json", String.class)).thenReturn("{}");
    DSLContext dsl = recoveryDsl(row);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionLeaseRepository(dsl)
                    .findByRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "spiffe://firemud/ns/test/sa/game-session-service"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  private static Record allocationRow(UUID account, UUID request, UUID lease, Long fence) {
    Record row = mock(Record.class);
    when(row.get("account_uuid", UUID.class)).thenReturn(account);
    when(row.get("request_id", UUID.class)).thenReturn(request);
    when(row.get("lease_id", UUID.class)).thenReturn(lease);
    when(row.get("lease_fence", Long.class)).thenReturn(fence);
    return row;
  }

  private static DSLContext allocationDsl(Record readback, int insertCount) {
    DSLContext dsl = mock(DSLContext.class);
    Record owner = mock(Record.class);
    Record counter = mock(Record.class);
    when(counter.get("last_fence", Long.class)).thenReturn(1L);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(owner, null, counter, readback);
    when(dsl.execute(
            startsWith("INSERT INTO account_gameplay_admission_lease_allocations "),
            any(Object[].class)))
        .thenReturn(insertCount);
    serializableTransaction();
    return dsl;
  }

  private static DSLContext recoveryDsl(Record readback) {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(mock(Record.class), readback);
    serializableTransaction();
    return dsl;
  }

  private static void serializableTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
  }

  /** Mocked storage failure only; this does not establish PostgreSQL or source authority proof. */
  private static void assertCounterReadbackRejected(Record counter, String failureKind) {
    DSLContext dsl = mock(DSLContext.class);
    Record account = mock(Record.class);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, null, counter);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
    var repository = new AccountGameplayAdmissionLeaseRepository(dsl);
    assertThatThrownBy(
            () -> repository.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account admission lease counter readback " + failureKind);
    verify(dsl, never())
        .execute(
            startsWith("INSERT INTO account_gameplay_admission_lease_allocations "),
            any(Object[].class));
  }
}
