package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Repository and SQL function doubles only; no PostgreSQL durability is established here. */
class AccountGameplayAdmissionCommitConfirmationRepositoryTest {
  private static final String CONFIRM_SQL =
      "SELECT * FROM account_gameplay_admission_confirm_committed(?, ?, ?)";
  private static final String READ_SQL =
      "SELECT * FROM account_gameplay_admission_read_commit_confirmation(?, ?, ?)";

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void confirmAndReadCallOnlyTheirDatabaseFunctionWithOriginalRequestDigestAndDecision() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    var operation = committed(evidence, decision);
    var confirmDsl = dsl(evidence, operation, proofRow(evidence, decision));
    var confirmRepository = repository(confirmDsl);

    var created = confirmRepository.confirmCommitted(evidence, decision);

    assertThat(created.operation()).isEqualTo(operation);
    assertThat(created.operation().evidence().canonicalJson()).isEqualTo(evidence.canonicalJson());
    assertThat(created.operation().evidence().sha256()).isEqualTo(evidence.sha256());
    assertThat(created.operation().state()).isEqualTo(State.COMMITTED);
    assertThat(created.operation().bindingDecisionId()).isEqualTo(decision);
    assertThat(created.requestId())
        .isEqualTo(UUID.fromString((String) evidence.carrier().get("requestId")));
    assertThat(created.accountId())
        .isEqualTo(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    assertThat(created.leaseId())
        .isEqualTo(UUID.fromString((String) evidence.carrier().get("leaseId")));
    assertThat(created.leaseFence()).isEqualTo(1L);
    assertThat(created.evidenceSha256()).isEqualTo(evidence.sha256());
    assertThat(created.bindingDecisionId()).isEqualTo(decision);
    assertThat(created.expiresAtMs()).isEqualTo(1_015_000L);
    assertThat(created.finalizationXid()).isEqualTo("123456");
    assertThat(created.walInsertLsn()).isEqualTo("1/20");
    assertThat(created.walFlushLsn()).isEqualTo("1/21");
    assertThat(created.committedBeforeMs()).isEqualTo(1_014_999L);
    assertThat(created.confirmationXid()).isEqualTo("123457");
    verify(confirmDsl)
        .fetchOne(
            CONFIRM_SQL,
            UUID.fromString((String) evidence.carrier().get("requestId")),
            evidence.sha256(),
            decision);

    var readDsl = dsl(evidence, operation, proofRow(evidence, decision));
    var readRepository = repository(readDsl);
    assertThat(readRepository.readCommitConfirmation(evidence, decision)).isEqualTo(created);
    verify(readDsl)
        .fetchOne(
            READ_SQL,
            UUID.fromString((String) evidence.carrier().get("requestId")),
            evidence.sha256(),
            decision);
    verify(readDsl, never()).fetchOne(eq(CONFIRM_SQL), any(Object[].class));
  }

  @Test
  void requiresWritableSerializableAccountTransactionBeforeAnyStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new AccountGameplayAdmissionCommitConfirmationRepository(
            dsl, new AccountGameplayAdmissionLeaseRepository(dsl));
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    assertThatThrownBy(() -> repository.readCommitConfirmation(evidence, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);
    assertThatThrownBy(() -> repository.readCommitConfirmation(evidence, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.confirmCommitted(evidence, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void pendingAbortedMissingAndDecisionMismatchNeverReachConfirmationFunctions() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    for (var operation :
        java.util.List.of(
            new AccountGameplayAdmissionLeaseOperation(evidence, State.PENDING, null, null),
            new AccountGameplayAdmissionLeaseOperation(
                evidence, State.ABORTED, null, UUID.randomUUID()),
            new AccountGameplayAdmissionLeaseOperation(
                evidence, State.COMMITTED, UUID.randomUUID(), null))) {
      DSLContext dsl = dsl(evidence, operation, proofRow(evidence, decision));
      assertThatThrownBy(() -> repository(dsl).confirmCommitted(evidence, decision))
          .isInstanceOf(IllegalStateException.class);
      verify(dsl, never()).fetchOne(eq(CONFIRM_SQL), any(Object[].class));
      verify(dsl, never()).fetchOne(eq(READ_SQL), any(Object[].class));
    }

    DSLContext missingDsl = dsl(evidence, null, proofRow(evidence, decision));
    assertThatThrownBy(() -> repository(missingDsl).readCommitConfirmation(evidence, decision))
        .isInstanceOf(IllegalStateException.class);
    verify(missingDsl, never()).fetchOne(eq(READ_SQL), any(Object[].class));
  }

  @Test
  void missingOrContradictoryDatabaseProofFailsClosed() {
    var evidence = AccountGameplayAdmissionAbortOwnerTest.fixture();
    UUID decision = UUID.randomUUID();
    var operation = committed(evidence, decision);
    for (String field :
        java.util.List.of(
            "confirmation_version",
            "request_id",
            "account_uuid",
            "lease_id",
            "lease_fence",
            "evidence_sha256",
            "binding_decision_id",
            "expires_at_ms",
            "finalization_xid",
            "wal_insert_lsn",
            "wal_flush_lsn",
            "committed_before_ms",
            "confirmation_xid")) {
      Record bad = proofRow(evidence, decision);
      overrideBadField(bad, field);
      DSLContext dsl = dsl(evidence, operation, bad);
      assertThatThrownBy(() -> repository(dsl).readCommitConfirmation(evidence, decision))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Durable Account admission commit confirmation unavailable");
    }

    DSLContext missing = dsl(evidence, operation, null);
    assertThatThrownBy(() -> repository(missing).readCommitConfirmation(evidence, decision))
        .isInstanceOf(IllegalStateException.class);
  }

  private static AccountGameplayAdmissionCommitConfirmationRepository repository(DSLContext dsl) {
    serializableTransaction();
    return new AccountGameplayAdmissionCommitConfirmationRepository(
        dsl, new AccountGameplayAdmissionLeaseRepository(dsl));
  }

  private static DSLContext dsl(
      AccountGameplayAdmissionLeaseEvidence evidence,
      AccountGameplayAdmissionLeaseOperation operation,
      Record proof) {
    DSLContext dsl = mock(DSLContext.class);
    Record account = mock(Record.class);
    Record operationRow = operation == null ? null : operationRow(evidence, operation);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, operationRow, proof);
    return dsl;
  }

  private static Record operationRow(
      AccountGameplayAdmissionLeaseEvidence evidence,
      AccountGameplayAdmissionLeaseOperation operation) {
    Record row = mock(Record.class);
    when(row.get("evidence_json", String.class)).thenReturn(evidence.canonicalJson());
    when(row.get("evidence_sha256", String.class)).thenReturn(evidence.sha256());
    when(row.get("account_uuid", UUID.class))
        .thenReturn(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(row.get("request_id", UUID.class))
        .thenReturn(UUID.fromString((String) evidence.carrier().get("requestId")));
    when(row.get("lease_id", UUID.class))
        .thenReturn(UUID.fromString((String) evidence.carrier().get("leaseId")));
    when(row.get("lease_fence", Long.class)).thenReturn(1L);
    when(row.get("caller_workload", String.class))
        .thenReturn("spiffe://firemud/ns/test/sa/game-session-service");
    when(row.get("evaluated_at_ms", Long.class)).thenReturn(1_000_000L);
    when(row.get("expires_at_ms", Long.class)).thenReturn(1_015_000L);
    when(row.get("status", String.class)).thenReturn(operation.state().name());
    when(row.get("binding_decision_id", UUID.class)).thenReturn(operation.bindingDecisionId());
    when(row.get("orphan_cleanup_id", UUID.class)).thenReturn(operation.orphanCleanupId());
    return row;
  }

  private static Record proofRow(AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    Record row = mock(Record.class);
    when(row.get("confirmation_version", Short.class)).thenReturn((short) 1);
    when(row.get("request_id", UUID.class))
        .thenReturn(UUID.fromString((String) evidence.carrier().get("requestId")));
    when(row.get("account_uuid", UUID.class))
        .thenReturn(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(row.get("lease_id", UUID.class))
        .thenReturn(UUID.fromString((String) evidence.carrier().get("leaseId")));
    when(row.get("lease_fence", Long.class)).thenReturn(1L);
    when(row.get("evidence_sha256", String.class)).thenReturn(evidence.sha256());
    when(row.get("binding_decision_id", UUID.class)).thenReturn(decision);
    when(row.get("expires_at_ms", Long.class)).thenReturn(1_015_000L);
    when(row.get("finalization_xid", String.class)).thenReturn("123456");
    when(row.get("wal_insert_lsn", String.class)).thenReturn("1/20");
    when(row.get("wal_flush_lsn", String.class)).thenReturn("1/21");
    when(row.get("committed_before_ms", Long.class)).thenReturn(1_014_999L);
    when(row.get("confirmation_xid", String.class)).thenReturn("123457");
    return row;
  }

  private static void overrideBadField(Record row, String field) {
    switch (field) {
      case "confirmation_version" -> when(row.get(field, Short.class)).thenReturn((short) 2);
      case "request_id", "account_uuid", "lease_id", "binding_decision_id" ->
          when(row.get(field, UUID.class)).thenReturn(UUID.randomUUID());
      case "lease_fence", "expires_at_ms" -> when(row.get(field, Long.class)).thenReturn(2L);
      case "committed_before_ms" -> when(row.get(field, Long.class)).thenReturn(1_015_000L);
      case "evidence_sha256" -> when(row.get(field, String.class)).thenReturn("0".repeat(64));
      case "finalization_xid", "confirmation_xid" ->
          when(row.get(field, String.class)).thenReturn("01");
      case "wal_insert_lsn" -> when(row.get(field, String.class)).thenReturn("2/20");
      case "wal_flush_lsn" -> when(row.get(field, String.class)).thenReturn("1/20 ");
      default -> throw new IllegalArgumentException("Unknown proof field");
    }
  }

  private static AccountGameplayAdmissionLeaseOperation committed(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    return new AccountGameplayAdmissionLeaseOperation(evidence, State.COMMITTED, decision, null);
  }

  private static void serializableTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }
}
