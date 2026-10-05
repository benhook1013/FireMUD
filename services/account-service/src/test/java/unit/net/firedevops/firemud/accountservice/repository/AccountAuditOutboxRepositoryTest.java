package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.tables.AccountAuditOutbox.ACCOUNT_AUDIT_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountAuditOutboxRecord;
import org.jooq.DSLContext;
import org.jooq.RecordContext;
import org.jooq.RecordListener;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.exception.NoDataFoundException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultRecordListenerProvider;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class AccountAuditOutboxRepositoryTest {
  private static final UUID EVENT_ID = UUID.fromString("8a5f6238-f0d9-4992-80cb-e7f447e0f913");
  private static final String RECEIPT_ID = "receipt-1";
  private static final String LOG_EVENT_ID = "log-1";

  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountAuditOutboxRepository repository = new AccountAuditOutboxRepository(dsl);

  @Test
  void appendReturnsRefreshedPersistedEnvelope() {
    UUID auditEventId = UUID.randomUUID();
    AccountAuditOutboxRecord row = mock(AccountAuditOutboxRecord.class);
    when(dsl.newRecord(ACCOUNT_AUDIT_OUTBOX)).thenReturn(row);
    String persistedPayload = "{\"persisted\":\"exact material\"}";
    LocalDateTime persistedOccurredAt = LocalDateTime.parse("2026-10-01T12:34:56.123456");
    String persistedDigest =
        arrangePersistedTenantRow(row, auditEventId, persistedOccurredAt, persistedPayload);

    AccountAuditEnvelope envelope =
        repository.append(auditEventId, "tenant", 73L, "ACCOUNT_MEMBERSHIP_LEFT", persistedPayload);

    assertThat(envelope)
        .isEqualTo(
            new AccountAuditEnvelope(
                auditEventId,
                "tenant",
                AccountAuditTenantIdentity.retainedTenantV1(73L),
                "account-service",
                "ACCOUNT_MEMBERSHIP_LEFT",
                persistedOccurredAt.toInstant(ZoneOffset.UTC),
                1,
                1,
                persistedDigest,
                persistedPayload));
    assertThat(envelope.payloadDigest())
        .isEqualTo(AccountAuditDigest.ofPayload(envelope.payload()));
    verify(row).setTenantIdentityVersion(1);
    verify(row).setTenantUuid(null);
  }

  @Test
  void appendRejectsChangedStoredPayloadAndDigest() {
    UUID auditEventId = UUID.randomUUID();
    AccountAuditOutboxRecord row = mock(AccountAuditOutboxRecord.class);
    when(dsl.newRecord(ACCOUNT_AUDIT_OUTBOX)).thenReturn(row);
    LocalDateTime persistedOccurredAt = LocalDateTime.parse("2026-10-01T12:34:56.123456");
    arrangePersistedTenantRow(
        row, auditEventId, persistedOccurredAt, "{\"stored\":\"different material\"}");

    assertThatThrownBy(
            () ->
                repository.append(
                    auditEventId,
                    "tenant",
                    73L,
                    "ACCOUNT_MEMBERSHIP_LEFT",
                    "{\"requested\":\"original material\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not preserve its exact envelope");
  }

  @Test
  void appendFailsClosedWhenStoredEnvelopeCannotBeReadBack() {
    AccountAuditOutboxRecord row = mock(AccountAuditOutboxRecord.class);
    when(dsl.newRecord(ACCOUNT_AUDIT_OUTBOX)).thenReturn(row);
    doThrow(new NoDataFoundException("Stored audit envelope is missing")).when(row).refresh();

    assertThatThrownBy(
            () ->
                repository.append(
                    UUID.randomUUID(), "tenant", 73L, "ACCOUNT_MEMBERSHIP_LEFT", "{}"))
        .isInstanceOf(NoDataFoundException.class)
        .hasMessageContaining("Stored audit envelope is missing");
  }

  @Test
  void canonicalAppendRequiresAnActiveReadWriteOwnerTransactionBeforeStorage() {
    assertThatThrownBy(
            () ->
                repository.appendCanonicalTenant(
                    UUID.randomUUID(),
                    "33333333-3333-4333-8333-333333333333",
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active read-write owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void canonicalReadbackRequiresAnActiveReadWriteOwnerTransactionBeforeStorage() {
    String payload = "{}";
    AccountAuditEnvelope expected =
        new AccountAuditEnvelope(
            UUID.randomUUID(),
            "tenant",
            AccountAuditTenantIdentity.canonicalTenantV2("33333333-3333-4333-8333-333333333333"),
            "account-service",
            "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
            java.time.Instant.parse("2026-10-01T00:00:00Z"),
            1,
            1,
            AccountAuditDigest.ofPayload(payload),
            payload);

    assertThatThrownBy(() -> repository.findExactCanonicalTenantEnvelopeForUpdate(expected))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active read-write owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void canonicalIdentityRejectsMissingMixedUnsupportedNilAndNoncanonicalValues() {
    assertThatThrownBy(() -> AccountAuditTenantIdentity.canonicalTenantV2(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditEnvelope(
                    UUID.randomUUID(),
                    "tenant",
                    null,
                    "account-service",
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                    1,
                    1,
                    AccountAuditDigest.ofPayload("{}"),
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountAuditTenantIdentity.canonicalTenantV2(
                    "00000000-0000-0000-0000-000000000000"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountAuditTenantIdentity.canonicalTenantV2(
                    "33333333-3333-4333-8333-33333333333A"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountAuditTenantIdentity(3, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditTenantIdentity(
                    AccountAuditTenantIdentity.VERSION_2,
                    73L,
                    UUID.fromString("33333333-3333-4333-8333-333333333333")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditEnvelope(
                    UUID.randomUUID(),
                    "platform",
                    AccountAuditTenantIdentity.retainedTenantV1(73L),
                    "account-service",
                    "ACCOUNT_REGISTERED",
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                    1,
                    1,
                    AccountAuditDigest.ofPayload("{}"),
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditEnvelope(
                    UUID.randomUUID(),
                    "tenant",
                    AccountAuditTenantIdentity.canonicalTenantV2(
                        "33333333-3333-4333-8333-333333333333"),
                    "account-service",
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                    1,
                    1,
                    "sha256:" + "0".repeat(64),
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void retainedIdentityIsExplicitVersionOneAndDigestRejectsMalformedUnicode() {
    AccountAuditEnvelope retained =
        new AccountAuditEnvelope(
            UUID.randomUUID(),
            "tenant",
            AccountAuditTenantIdentity.retainedTenantV1(73L),
            "account-service",
            "ACCOUNT_MEMBERSHIP_LEFT",
            java.time.Instant.parse("2026-10-01T00:00:00Z"),
            1,
            1,
            AccountAuditDigest.ofPayload("{}"),
            "{}");

    assertThat(retained.tenantIdentityVersion()).isEqualTo(1);
    assertThat(retained.tenantId()).isEqualTo(73L);
    assertThat(retained.tenantUuid()).isNull();
    assertThatThrownBy(() -> AccountAuditDigest.ofPayload(String.valueOf((char) 0xD800)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid Unicode");
  }

  private static String arrangePersistedTenantRow(
      AccountAuditOutboxRecord row, UUID auditEventId, LocalDateTime occurredAt, String payload) {
    String digest = AccountAuditDigest.ofPayload(payload);
    doAnswer(
            invocation -> {
              when(row.getAuditEventId()).thenReturn(auditEventId);
              when(row.getScope()).thenReturn("tenant");
              when(row.getTenantId()).thenReturn(73L);
              when(row.getTenantIdentityVersion()).thenReturn(1);
              when(row.getTenantUuid()).thenReturn(null);
              when(row.getProducerService()).thenReturn("account-service");
              when(row.getEventType()).thenReturn("ACCOUNT_MEMBERSHIP_LEFT");
              when(row.getOccurredAt()).thenReturn(occurredAt);
              when(row.getSchemaVersion()).thenReturn(1);
              when(row.getPayloadDigestVersion()).thenReturn(1);
              when(row.getPayloadDigest()).thenReturn(digest);
              when(row.getPayload()).thenReturn(payload);
              return null;
            })
        .when(row)
        .refresh();
    return digest;
  }

  @Test
  void exactCommittedOrMinimizedTerminalReceiptAfterLostCompareAndSetIsANoOp() {
    for (String status : new String[] {"COMMITTED", "MINIMIZED"}) {
      boolean minimized = "MINIMIZED".equals(status);
      assertThatCode(
              () ->
                  repositoryWithReadback(status, RECEIPT_ID, LOG_EVENT_ID, true)
                      .markDelivered(EVENT_ID, RECEIPT_ID, LOG_EVENT_ID, minimized))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void contradictoryTerminalReceiptOrStatusAfterLostCompareAndSetFails() {
    assertRejected("COMMITTED", "other-receipt", LOG_EVENT_ID, false);
    assertRejected("COMMITTED", RECEIPT_ID, "other-log", false);
    assertRejected("MINIMIZED", RECEIPT_ID, LOG_EVENT_ID, false);
  }

  @Test
  void missingOutboxRowAfterLostCompareAndSetFails() {
    AccountAuditOutboxRepository repository =
        repositoryWithReadback("COMMITTED", RECEIPT_ID, LOG_EVENT_ID, false);

    assertThatThrownBy(() -> repository.markDelivered(EVENT_ID, RECEIPT_ID, LOG_EVENT_ID, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Audit delivery state changed concurrently");
  }

  @Test
  void pendingQuerySelectsOnlyDueRowsAndOrdersByRetryTimeBeforeCreationTime() {
    AtomicReference<String> executedSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          executedSql.set(context.sql().toLowerCase(Locale.ROOT));
          Result<AccountAuditOutboxRecord> rows =
              DSL.using(SQLDialect.POSTGRES).newResult(ACCOUNT_AUDIT_OUTBOX);
          return new MockResult[] {new MockResult(0, rows)};
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

    new AccountAuditOutboxRepository(dsl).pending(7, Instant.parse("2026-10-01T00:00:00Z"));

    String sql = executedSql.get();
    int where = sql.indexOf("where");
    int orderBy = sql.indexOf("order by");
    int retryOrder = sql.indexOf("next_attempt_at", orderBy);
    int createdOrder = sql.indexOf("created_at", retryOrder);
    int eventOrder = sql.indexOf("audit_event_id", createdOrder);
    assertThat(sql.substring(where, orderBy))
        .contains("delivery_status", "next_attempt_at", "<=", "=");
    assertThat(retryOrder).isGreaterThan(orderBy);
    assertThat(createdOrder).isGreaterThan(retryOrder);
    assertThat(eventOrder).isGreaterThan(createdOrder);
    assertThat(sql).contains("fetch next ? rows only");
  }

  @Test
  void appendStoresRetryTimeAsTheSameUtcLocalDateTimeAsOccurredAt() {
    AtomicReference<String> executedSql = new AtomicReference<>();
    AtomicReference<LocalDateTime> storedOccurredAt = new AtomicReference<>();
    AtomicReference<LocalDateTime> storedNextAttemptAt = new AtomicReference<>();
    AtomicReference<AccountAuditOutboxRecord> insertedRow = new AtomicReference<>();
    AtomicReference<AccountAuditOutboxRecord> persistedRow = new AtomicReference<>();
    AtomicReference<AccountAuditOutboxRecord> refreshedRow = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.stripLeading().startsWith("insert") && sql.contains("account_audit_outbox")) {
            executedSql.set(sql);
            AccountAuditOutboxRecord inserted = insertedRow.get();
            if (inserted == null) {
              throw new SQLException("Audit outbox row was not prepared before insert");
            }
            AccountAuditOutboxRecord persisted = copyAsPersistedRow(resultDsl, inserted);
            persistedRow.set(persisted);
            Result<AccountAuditOutboxRecord> rows = resultDsl.newResult(ACCOUNT_AUDIT_OUTBOX);
            rows.add(persisted);
            return new MockResult[] {new MockResult(1, rows)};
          }
          if (sql.stripLeading().startsWith("select") && sql.contains("account_audit_outbox")) {
            AccountAuditOutboxRecord persisted = persistedRow.get();
            if (persisted == null) {
              throw new SQLException("Audit outbox row was not inserted before refresh");
            }
            Result<AccountAuditOutboxRecord> rows = resultDsl.newResult(ACCOUNT_AUDIT_OUTBOX);
            AccountAuditOutboxRecord readback = copyAsPersistedRow(resultDsl, persisted);
            refreshedRow.set(readback);
            rows.add(readback);
            return new MockResult[] {new MockResult(1, rows)};
          }
          throw new SQLException("Unexpected Account audit outbox query");
        };
    RecordListener recordListener =
        new RecordListener() {
          @Override
          public void storeStart(RecordContext context) {
            if (context.record() instanceof AccountAuditOutboxRecord record) {
              insertedRow.set(record);
              storedOccurredAt.set(record.getOccurredAt());
              storedNextAttemptAt.set(record.getNextAttemptAt());
            }
          }
        };
    DSLContext dsl =
        DSL.using(
            new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.POSTGRES)
                .set(new DefaultRecordListenerProvider(recordListener)));

    AccountAuditEnvelope envelope =
        new AccountAuditOutboxRepository(dsl)
            .append(EVENT_ID, "platform", null, "ACCOUNT_REGISTERED", "{}");

    assertThat(executedSql.get()).contains("occurred_at", "next_attempt_at");
    LocalDateTime expectedUtc = LocalDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC);
    assertThat(storedOccurredAt.get()).isEqualTo(expectedUtc);
    assertThat(storedNextAttemptAt.get()).isEqualTo(expectedUtc);
    assertThat(refreshedRow.get()).isNotNull();
    assertThat(refreshedRow.get().getOccurredAt()).isEqualTo(expectedUtc);
    assertThat(refreshedRow.get().getNextAttemptAt()).isEqualTo(expectedUtc);
    assertThat(persistedRow.get()).isNotNull();
    assertThat(persistedRow.get().getOccurredAt()).isEqualTo(expectedUtc);
    assertThat(persistedRow.get().getNextAttemptAt()).isEqualTo(expectedUtc);
  }

  @Test
  void recordAttemptPersistsIncrementAndCappedExponentialScheduleOnlyWhilePending() {
    AtomicReference<String> executedSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          executedSql.set(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(1)};
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);

    new AccountAuditOutboxRepository(dsl).recordAttempt(EVENT_ID);

    String sql = executedSql.get();
    assertThat(sql)
        .contains(
            "attempt_count",
            "last_attempt_at",
            "next_attempt_at",
            "make_interval",
            "least(300",
            "where",
            "delivery_status");
  }

  private static void assertRejected(
      String storedStatus, String storedReceiptId, String storedLogEventId, boolean minimized) {
    AccountAuditOutboxRepository repository =
        repositoryWithReadback(storedStatus, storedReceiptId, storedLogEventId, true);
    assertThatThrownBy(
            () -> repository.markDelivered(EVENT_ID, RECEIPT_ID, LOG_EVENT_ID, minimized))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Audit delivery state changed concurrently");
  }

  private static AccountAuditOutboxRecord copyAsPersistedRow(
      DSLContext dsl, AccountAuditOutboxRecord source) {
    AccountAuditOutboxRecord persisted = dsl.newRecord(ACCOUNT_AUDIT_OUTBOX);
    persisted.setAuditEventId(source.getAuditEventId());
    persisted.setScope(source.getScope());
    persisted.setTenantId(source.getTenantId());
    persisted.setTenantIdentityVersion(source.getTenantIdentityVersion());
    persisted.setTenantUuid(source.getTenantUuid());
    persisted.setProducerService(source.getProducerService());
    persisted.setEventType(source.getEventType());
    persisted.setOccurredAt(source.getOccurredAt());
    persisted.setSchemaVersion(source.getSchemaVersion());
    persisted.setPayloadDigestVersion(source.getPayloadDigestVersion());
    persisted.setPayloadDigest(source.getPayloadDigest());
    persisted.setPayload(source.getPayload());
    persisted.setReceiverReceiptId(source.getReceiverReceiptId());
    persisted.setReceiverLogEventId(source.getReceiverLogEventId());
    persisted.setDeliveryStatus(
        source.getDeliveryStatus() == null ? "PENDING" : source.getDeliveryStatus());
    persisted.setLastAttemptAt(source.getLastAttemptAt());
    persisted.setCreatedAt(
        source.getCreatedAt() == null ? LocalDateTime.now(ZoneOffset.UTC) : source.getCreatedAt());
    persisted.setAttemptCount(
        source.getAttemptCount() == null ? Integer.valueOf(0) : source.getAttemptCount());
    persisted.setNextAttemptAt(source.getNextAttemptAt());
    persisted.setReceiverAuditProjectionVersion(source.getReceiverAuditProjectionVersion());
    return persisted;
  }

  private static AccountAuditOutboxRepository repositoryWithReadback(
      String status, String receiptId, String logEventId, boolean rowExists) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          String sql = context.sql().trim().toLowerCase(Locale.ROOT);
          if (sql.startsWith("update")) {
            return new MockResult[] {new MockResult(0)};
          }
          if (sql.startsWith("select")) {
            Result<AccountAuditOutboxRecord> rows = resultDsl.newResult(ACCOUNT_AUDIT_OUTBOX);
            if (rowExists) {
              AccountAuditOutboxRecord row = resultDsl.newRecord(ACCOUNT_AUDIT_OUTBOX);
              row.setAuditEventId(EVENT_ID);
              row.setDeliveryStatus(status);
              row.setReceiverReceiptId(receiptId);
              row.setReceiverLogEventId(logEventId);
              row.setReceiverAuditProjectionVersion(1);
              rows.add(row);
            }
            return new MockResult[] {new MockResult(rowExists ? 1 : 0, rows)};
          }
          throw new SQLException("Unexpected Account audit outbox query");
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    return new AccountAuditOutboxRepository(dsl);
  }
}
