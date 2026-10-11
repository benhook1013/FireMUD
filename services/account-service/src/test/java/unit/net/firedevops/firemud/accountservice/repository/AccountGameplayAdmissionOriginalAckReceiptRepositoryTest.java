package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionOriginalAckReceipt;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Repository doubles verify exact storage binding only; they do not prove PostgreSQL durability.
 */
class AccountGameplayAdmissionOriginalAckReceiptRepositoryTest {
  private static final String INSERT_SQL =
      "INSERT INTO account_gameplay_admission_original_commit_ack_receipts "
          + "(request_id, evidence_sha256, binding_decision_id, finalization_xid, committed_before_ms) "
          + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING RETURNING *";
  private static final String SELECT_SQL =
      "SELECT * FROM account_gameplay_admission_original_commit_ack_receipts WHERE request_id = ?";
  private static final String READ_DURABLY_SQL =
      "SELECT * FROM account_gameplay_admission_read_original_ack_receipt_durably(?, ?, ?)";
  private static final String ACCOUNT_LOCK_SQL =
      "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE";
  private static final String OPERATION_SELECT_SQL =
      "SELECT * FROM account_gameplay_admission_lease_operations WHERE request_id = ? FOR UPDATE";

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void createInsertsTypedAckAndReturnsReattachedExactReceipt() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    var operation = committed(evidence, decision);
    Record inserted = receiptRow(evidence, decision, "123456", 1_014_999L);
    DSLContext dsl = dsl(evidence, operation, inserted, inserted);
    var repository = repository(dsl);
    var acknowledgement = acknowledgement(evidence, decision, "123456", 1_014_999L);

    var created = repository.create(acknowledgement);

    assertThat(created.inserted()).isTrue();
    assertThat(created.receipt()).isEqualTo(decoded(operation, evidence, decision));
    verify(dsl)
        .fetchOne(
            INSERT_SQL, requestId(evidence), evidence.sha256(), decision, "123456", 1_014_999L);
    var order = inOrder(dsl);
    order
        .verify(dsl)
        .fetchOne(
            INSERT_SQL, requestId(evidence), evidence.sha256(), decision, "123456", 1_014_999L);
    order.verify(dsl).fetchOne(ACCOUNT_LOCK_SQL, accountId(evidence));
    order.verify(dsl).fetchOne(OPERATION_SELECT_SQL, requestId(evidence));
    verify(dsl, never()).fetchOne(eq(READ_DURABLY_SQL), any(Object[].class));
  }

  @Test
  void exactRetainedAckReturnsInsertedFalseWithoutChangingOriginalClockOrXids() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    var operation = committed(evidence, decision);
    Record retained = receiptRow(evidence, decision, "123456", 1_014_999L);
    var repository = repository(dsl(evidence, operation, null, retained));

    var created = repository.create(acknowledgement(evidence, decision, "123456", 1_014_999L));

    assertThat(created.inserted()).isFalse();
    assertThat(created.receipt()).isEqualTo(decoded(operation, evidence, decision));
  }

  @Test
  void retainedAckWithChangedOriginalClockOrXidIsRejected() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    var operation = committed(evidence, decision);
    Record retained = receiptRow(evidence, decision, "123456", 1_014_999L);
    var repository = repository(dsl(evidence, operation, null, retained));

    assertThatThrownBy(
            () -> repository.create(acknowledgement(evidence, decision, "123456", 1_014_998L)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> repository.create(acknowledgement(evidence, decision, "123457", 1_014_999L)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void storageOnlyReadAndDurableReadUseDistinctPathsAndSameExactOperation() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    var operation = committed(evidence, decision);
    Record receipt = receiptRow(evidence, decision, "123456", 1_014_999L);

    DSLContext exactDsl = dsl(evidence, operation, null, receipt);
    var repository = repository(exactDsl);
    assertThat(repository.readExact(evidence, decision))
        .isEqualTo(decoded(operation, evidence, decision));
    verify(exactDsl).fetchOne(SELECT_SQL, requestId(evidence));
    verify(exactDsl, never()).fetchOne(eq(READ_DURABLY_SQL), any(Object[].class));

    DSLContext durableDsl = dsl(evidence, operation, receipt, receipt);
    assertThat(repository(durableDsl).readDurably(evidence, decision))
        .isEqualTo(decoded(operation, evidence, decision));
    verify(durableDsl).fetchOne(READ_DURABLY_SQL, requestId(evidence), evidence.sha256(), decision);
    verify(durableDsl, never()).fetchOne(eq(INSERT_SQL), any(Object[].class));
  }

  @Test
  void rejectsMissingChangedAndNonCommittedOriginalOperation() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    Record receipt = receiptRow(evidence, decision, "123456", 1_014_999L);
    DSLContext missing = dsl(evidence, null, null, receipt);
    assertThatThrownBy(() -> repository(missing).readExact(evidence, decision))
        .isInstanceOf(IllegalStateException.class);

    for (var state : State.values()) {
      if (state == State.COMMITTED) continue;
      var operation =
          new AccountGameplayAdmissionLeaseOperation(
              evidence, state, null, state == State.ABORTED ? UUID.randomUUID() : null);
      DSLContext mismatched = dsl(evidence, operation, null, receipt);
      assertThatThrownBy(() -> repository(mismatched).readExact(evidence, decision))
          .isInstanceOf(IllegalStateException.class);
    }
    var wrongDecision = committed(evidence, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                repository(dsl(evidence, wrongDecision, null, receipt))
                    .readExact(evidence, decision))
        .isInstanceOf(IllegalStateException.class);

    var changedCarrier = new java.util.LinkedHashMap<>(evidence.carrier());
    changedCarrier.put("leaseId", UUID.randomUUID().toString());
    var changedEvidence = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
    DSLContext changedDsl = dsl(evidence, committed(evidence, decision), null, receipt);
    assertThatThrownBy(() -> repository(changedDsl).readExact(changedEvidence, decision))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validatesReceiptVersionFullBindingClockAndCanonicalDistinctUnsignedXids() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    var operation = committed(evidence, decision);
    var valid = decoded(operation, evidence, decision);
    assertThat(valid.schemaVersion()).isEqualTo((short) 1);
    assertThat(valid.receiptXid()).isEqualTo("123457");
    assertThat(valid.committedBeforeMs()).isLessThan(valid.expiresAtMs());

    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 2,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    UUID.randomUUID(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    UUID.randomUUID(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    UUID.randomUUID(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence() + 1,
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    "0".repeat(64),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    UUID.randomUUID(),
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs() + 1,
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.expiresAtMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    "18446744073709551616",
                    valid.committedBeforeMs(),
                    valid.receiptXid()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalAckReceipt(
                    operation,
                    (short) 1,
                    valid.requestId(),
                    valid.accountId(),
                    valid.leaseId(),
                    valid.leaseFence(),
                    valid.evidenceSha256(),
                    decision,
                    valid.expiresAtMs(),
                    valid.finalizationXid(),
                    valid.committedBeforeMs(),
                    valid.finalizationXid()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void receiptValueExposesOnlyImmutableRecordAccessors() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    var receipt = decoded(committed(evidence, decision), evidence, decision);
    assertThat(AccountGameplayAdmissionOriginalAckReceipt.class.isRecord()).isTrue();
    assertThat(
            java.util.Arrays.stream(
                    AccountGameplayAdmissionOriginalAckReceipt.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers())))
        .isTrue();
    assertThat(
            java.util.Arrays.stream(AccountGameplayAdmissionOriginalAckReceipt.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.startsWith("set")))
        .isTrue();
    assertThat(receipt.toString())
        .doesNotContain("committedBeforeMs", "finalizationXid", "receiptXid");
  }

  @Test
  void everyRepositoryMethodRequiresWritableSerializableTransactionBeforeStorage() {
    var evidence = AccountGameplayAdmissionOriginalAckReceiptTestFixtures.fixture();
    UUID decision = UUID.randomUUID();
    DSLContext dsl = mock(DSLContext.class);
    var repository =
        new AccountGameplayAdmissionOriginalAckReceiptRepository(
            dsl, new AccountGameplayAdmissionLeaseRepository(dsl));
    var acknowledgement = acknowledgement(evidence, decision, "123456", 1_014_999L);

    assertThatThrownBy(() -> repository.create(acknowledgement))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.readExact(evidence, decision))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.readDurably(evidence, decision))
        .isInstanceOf(IllegalStateException.class);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);
    assertThatThrownBy(() -> repository.readDurably(evidence, decision))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.create(acknowledgement))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  private static AccountGameplayAdmissionOriginalAckReceiptRepository repository(DSLContext dsl) {
    serializableTransaction();
    return new AccountGameplayAdmissionOriginalAckReceiptRepository(
        dsl, new AccountGameplayAdmissionLeaseRepository(dsl));
  }

  private static DSLContext dsl(
      AccountGameplayAdmissionLeaseEvidence evidence,
      AccountGameplayAdmissionLeaseOperation operation,
      Record inserted,
      Record receipt) {
    DSLContext dsl = mock(DSLContext.class);
    Record account = mock(Record.class);
    Record operationRow = operation == null ? null : operationRow(evidence, operation);
    when(dsl.fetchOne(eq(INSERT_SQL), any(Object[].class))).thenReturn(inserted);
    when(dsl.fetchOne(eq(SELECT_SQL), any(Object[].class))).thenReturn(receipt);
    when(dsl.fetchOne(eq(READ_DURABLY_SQL), any(Object[].class))).thenReturn(inserted);
    when(dsl.fetchOne(eq(ACCOUNT_LOCK_SQL), any(Object[].class))).thenReturn(account);
    when(dsl.fetchOne(eq(OPERATION_SELECT_SQL), any(Object[].class))).thenReturn(operationRow);
    return dsl;
  }

  private static Record operationRow(
      AccountGameplayAdmissionLeaseEvidence evidence,
      AccountGameplayAdmissionLeaseOperation operation) {
    Record row = mock(Record.class);
    when(row.get("evidence_json", String.class)).thenReturn(evidence.canonicalJson());
    when(row.get("evidence_sha256", String.class)).thenReturn(evidence.sha256());
    when(row.get("account_uuid", UUID.class)).thenReturn(accountId(evidence));
    when(row.get("request_id", UUID.class)).thenReturn(requestId(evidence));
    when(row.get("lease_id", UUID.class)).thenReturn(leaseId(evidence));
    when(row.get("lease_fence", Long.class)).thenReturn(evidence.leaseFence().longValueExact());
    when(row.get("caller_workload", String.class))
        .thenReturn((String) evidence.carrier().get("callerWorkload"));
    when(row.get("evaluated_at_ms", Long.class))
        .thenReturn(Long.parseLong((String) evidence.carrier().get("evaluatedAt")));
    when(row.get("expires_at_ms", Long.class))
        .thenReturn(Long.parseLong((String) evidence.carrier().get("expiresAt")));
    when(row.get("status", String.class)).thenReturn(operation.state().name());
    when(row.get("binding_decision_id", UUID.class)).thenReturn(operation.bindingDecisionId());
    when(row.get("orphan_cleanup_id", UUID.class)).thenReturn(operation.orphanCleanupId());
    return row;
  }

  private static Record receiptRow(
      AccountGameplayAdmissionLeaseEvidence evidence,
      UUID decision,
      String finalizationXid,
      long committedBeforeMs) {
    Record row = mock(Record.class);
    when(row.get("schema_version", Short.class)).thenReturn((short) 1);
    when(row.get("request_id", UUID.class)).thenReturn(requestId(evidence));
    when(row.get("account_uuid", UUID.class)).thenReturn(accountId(evidence));
    when(row.get("lease_id", UUID.class)).thenReturn(leaseId(evidence));
    when(row.get("lease_fence", Long.class)).thenReturn(evidence.leaseFence().longValueExact());
    when(row.get("evidence_sha256", String.class)).thenReturn(evidence.sha256());
    when(row.get("binding_decision_id", UUID.class)).thenReturn(decision);
    when(row.get("expires_at_ms", Long.class))
        .thenReturn(Long.parseLong((String) evidence.carrier().get("expiresAt")));
    when(row.get("finalization_xid", String.class)).thenReturn(finalizationXid);
    when(row.get("committed_before_ms", Long.class)).thenReturn(committedBeforeMs);
    when(row.get("receipt_xid", String.class)).thenReturn("123457");
    return row;
  }

  private static AccountGameplayAdmissionOriginalAckReceipt decoded(
      AccountGameplayAdmissionLeaseOperation operation,
      AccountGameplayAdmissionLeaseEvidence evidence,
      UUID decision) {
    return new AccountGameplayAdmissionOriginalAckReceipt(
        operation,
        (short) 1,
        requestId(evidence),
        accountId(evidence),
        leaseId(evidence),
        evidence.leaseFence().longValueExact(),
        evidence.sha256(),
        decision,
        Long.parseLong((String) evidence.carrier().get("expiresAt")),
        "123456",
        1_014_999L,
        "123457");
  }

  private static OriginalCommitAcknowledgement acknowledgement(
      AccountGameplayAdmissionLeaseEvidence evidence,
      UUID decision,
      String finalizationXid,
      long committedBeforeMs) {
    OriginalCommitAcknowledgement acknowledgement = mock(OriginalCommitAcknowledgement.class);
    when(acknowledgement.evidence()).thenReturn(evidence);
    when(acknowledgement.decisionId()).thenReturn(decision);
    when(acknowledgement.finalizationXid()).thenReturn(finalizationXid);
    when(acknowledgement.committedBeforeMs()).thenReturn(committedBeforeMs);
    return acknowledgement;
  }

  private static AccountGameplayAdmissionLeaseOperation committed(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    return new AccountGameplayAdmissionLeaseOperation(evidence, State.COMMITTED, decision, null);
  }

  private static UUID requestId(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString((String) evidence.carrier().get("requestId"));
  }

  private static UUID leaseId(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString((String) evidence.carrier().get("leaseId"));
  }

  private static UUID accountId(AccountGameplayAdmissionLeaseEvidence evidence) {
    var scope = (Map<?, ?>) evidence.carrier().get("bindingScope");
    return UUID.fromString((String) scope.get("accountId"));
  }

  private static void serializableTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_SERIALIZABLE);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }
}
