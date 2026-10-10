package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.PendingProtection;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked database interactions verify comparison and guard ordering, not PostgreSQL execution. */
class AccountStartSessionAdmissionProtectionRepositoryTest {
  private static final UUID PROTECTION_ID = UUID.fromString("f0467d79-a879-4f4f-99f0-b2369d49bd9b");
  private static final long PROTECTION_FENCE = 71L;
  private static final byte[] REQUEST_BYTES = new byte[] {1, 2, 3};
  private static final UUID ACCOUNT_ID = UUID.fromString("8d5cb900-7900-4200-88ae-113ca0f7028f");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void sourceChildrenMustMatchTheFullCanonicalEvidenceBytes() {
    SourceEvidence expected = source("2", "17", "current source bytes");
    SourceEvidence changedMetadata = source("3", "17", "current source bytes");
    SourceEvidence changedBytes = source("2", "17", "substituted source bytes");

    AccountStartSessionAdmissionProtectionRepository.requireExactSources(
        List.of(expected), List.of(expected));
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRepository.requireExactSources(
                    List.of(expected), List.of(changedMetadata)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRepository.requireExactSources(
                    List.of(expected), List.of(changedBytes)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
  }

  @Test
  void databaseCurrentnessExpiryAfterLockWaitAbortsBeforeSourceReadback() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchOne(contains("account_ss_admission_read_current_exact"), any(Object[].class)))
        .thenThrow(new DataAccessException("original lease elapsed after row-lock wait") {});

    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);

    assertThatThrownBy(
            () -> repository.assertCurrent(PROTECTION_ID, PROTECTION_FENCE, REQUEST_BYTES))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("elapsed after row-lock wait");

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(1))
        .fetchOne(contains("account_ss_admission_read_current_exact"), arguments.capture());
    assertThat(arguments.getValue())
        .containsExactly(PROTECTION_ID, PROTECTION_FENCE, REQUEST_BYTES);
    assertThat(arguments.getValue()[2]).isSameAs(REQUEST_BYTES);
  }

  @Test
  void historicalLookupRequiresWritableReadCommittedTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);

    assertThatThrownBy(() -> repository.findHistoricalExact(PROTECTION_ID, PROTECTION_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");

    verifyNoInteractions(dsl);
  }

  @Test
  void settlementLookupRequiresWritableReadCommittedTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);

    assertThatThrownBy(() -> repository.findSettlementExact(PROTECTION_ID, PROTECTION_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");

    verifyNoInteractions(dsl);
  }

  @Test
  void settlementWriteChecksTransactionBeforeInspectingTypedCarrier() {
    DSLContext dsl = mock(DSLContext.class);
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);
    AccountStartSessionAdmissionProtectionSettlement settlement =
        mock(AccountStartSessionAdmissionProtectionSettlement.class);

    assertThatThrownBy(() -> repository.settleExact(settlement))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_COMMITTED");

    verifyNoInteractions(dsl, settlement);
  }

  @Test
  void pendingPageUsesBoundedDescendingFenceKeysetAndDecodesExactIds() throws SQLException {
    DSLContext dsl = writableDsl();
    UUID newestId = UUID.fromString("c5dc6d8d-13ee-42a8-83a5-a78ee044b55c");
    UUID olderId = UUID.fromString("db7c25cb-dcb1-4ec5-af07-8f48d1e0a62c");
    when(dsl.fetch(anyString(), any(Object[].class)))
        .thenReturn(pendingRows(newestId, 90L, olderId, 73L));
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);
    beginWritableReadCommittedTransaction();

    List<PendingProtection> candidates = repository.findUnsettledPageDescending(100L, 2);

    assertThat(candidates)
        .containsExactly(new PendingProtection(newestId, 90L), new PendingProtection(olderId, 73L));
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(dsl).fetch(query.capture(), arguments.capture());
    assertThat(query.getValue())
        .contains("SELECT protection.protection_id, protection.protection_fence")
        .contains("NOT EXISTS")
        .contains("protection.protection_fence < ?")
        .contains("ORDER BY protection.protection_fence DESC LIMIT ?")
        .doesNotContain("FOR UPDATE", "expires_at", "UPDATE ", "DELETE ");
    assertThat(arguments.getValue()).containsExactly(100L, 2);
  }

  @Test
  void pendingPageRejectsUnboundedLimitsBeforeSelect() throws SQLException {
    DSLContext dsl = writableDsl();
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);
    beginWritableReadCommittedTransaction();

    assertThatThrownBy(
            () ->
                repository.findUnsettledPageDescending(
                    null,
                    AccountStartSessionAdmissionProtectionRepository.MAX_PENDING_SWEEP_SIZE + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bounded page size");

    verify(dsl, times(0)).fetch(anyString(), any(Object[].class));
  }

  @Test
  void settlementLookupRejectsMismatchedRetainedFenceBeforeCaptureOrReceiptRead()
      throws SQLException {
    DSLContext dsl = writableDsl();
    Record row = mock(Record.class);
    when(row.get("protection_id", UUID.class)).thenReturn(PROTECTION_ID);
    when(row.get("protection_fence", Long.class)).thenReturn(PROTECTION_FENCE + 1L);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);
    beginWritableReadCommittedTransaction();

    assertThatThrownBy(() -> repository.findSettlementExact(PROTECTION_ID, PROTECTION_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(1)).fetchOne(contains("WHERE protection_id = ?"), arguments.capture());
    assertThat(arguments.getValue()).containsExactly(PROTECTION_ID);
    verify(dsl, times(0)).fetch(anyString(), any(Object[].class));
    verify(dsl, times(0))
        .fetchOne(
            contains("account_start_session_admission_protection_settlements"),
            any(Object[].class));
  }

  @Test
  void historicalLookupRejectsAMismatchedRetainedFenceBeforeCaptureOrSourceRead()
      throws SQLException {
    DSLContext dsl = writableDsl();
    Record row = mock(Record.class);
    when(row.get("protection_id", UUID.class)).thenReturn(PROTECTION_ID);
    when(row.get("protection_fence", Long.class)).thenReturn(PROTECTION_FENCE + 1L);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);
    beginWritableReadCommittedTransaction();

    assertThatThrownBy(() -> repository.findHistoricalExact(PROTECTION_ID, PROTECTION_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(1)).fetchOne(contains("WHERE protection_id = ?"), arguments.capture());
    assertThat(arguments.getValue()).containsExactly(PROTECTION_ID);
    verify(dsl, times(0)).fetch(anyString(), any(Object[].class));
  }

  @Test
  void historicalLookupReturnsEmptyWhenProtectionIdIsNotRetained() throws SQLException {
    DSLContext dsl = writableDsl();
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(null);
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);
    beginWritableReadCommittedTransaction();

    assertThat(repository.findHistoricalExact(PROTECTION_ID, PROTECTION_FENCE)).isEmpty();

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(1)).fetchOne(contains("WHERE protection_id = ?"), arguments.capture());
    assertThat(arguments.getValue()).containsExactly(PROTECTION_ID);
    verify(dsl, times(0)).fetch(anyString(), any(Object[].class));
  }

  @Test
  void historicalLookupRejectsMalformedRetainedRequestBeforeCaptureOrSourceRead()
      throws SQLException {
    DSLContext dsl = writableDsl();
    Record row = mock(Record.class);
    when(row.get("protection_id", UUID.class)).thenReturn(PROTECTION_ID);
    when(row.get("protection_fence", Long.class)).thenReturn(PROTECTION_FENCE);
    when(row.get("request_binding_bytes", byte[].class)).thenReturn(new byte[] {1, 2, 3});
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);
    AccountStartSessionAdmissionProtectionRepository repository =
        new AccountStartSessionAdmissionProtectionRepository(dsl);
    beginWritableReadCommittedTransaction();

    assertThatThrownBy(() -> repository.findHistoricalExact(PROTECTION_ID, PROTECTION_FENCE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(1)).fetchOne(contains("WHERE protection_id = ?"), arguments.capture());
    assertThat(arguments.getValue()).containsExactly(PROTECTION_ID);
    verify(dsl, times(0)).fetch(anyString(), any(Object[].class));
  }

  private static SourceEvidence source(String generation, String sourceVersion, String bytes) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        ACCOUNT_ID.toString(),
        generation,
        sourceVersion,
        "account/admission-protection",
        "17",
        bytes.getBytes(StandardCharsets.UTF_8));
  }

  private static DSLContext writableDsl() throws SQLException {
    DSLContext dsl = mock(DSLContext.class);
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isReadOnly()).thenReturn(false);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    doAnswer(
            invocation -> {
              invocation.<ConnectionRunnable>getArgument(0).run(connection);
              return null;
            })
        .when(dsl)
        .connection(any(ConnectionRunnable.class));
    return dsl;
  }

  private static Result<Record> pendingRows(
      UUID firstId, long firstFence, UUID secondId, long secondFence) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    Field<UUID> idField = DSL.field("protection_id", SQLDataType.UUID);
    Field<Long> fenceField = DSL.field("protection_fence", SQLDataType.BIGINT);
    Result<Record> rows = resultDsl.newResult(new Field<?>[] {idField, fenceField});
    Record first = resultDsl.newRecord(idField, fenceField);
    first.setValue(idField, firstId);
    first.setValue(fenceField, firstFence);
    Record second = resultDsl.newRecord(idField, fenceField);
    second.setValue(idField, secondId);
    second.setValue(fenceField, secondFence);
    rows.add(first);
    rows.add(second);
    return rows;
  }

  private static void beginWritableReadCommittedTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
}
