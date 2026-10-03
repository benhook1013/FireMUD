package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.tables.AccountAuditOutbox.ACCOUNT_AUDIT_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
    String persistedDigest = AccountAuditDigest.ofPayload(persistedPayload);
    LocalDateTime persistedOccurredAt = LocalDateTime.parse("2026-10-01T12:34:56.123456");
    doAnswer(
            invocation -> {
              when(row.getAuditEventId()).thenReturn(auditEventId);
              when(row.getScope()).thenReturn("tenant");
              when(row.getTenantId()).thenReturn(73L);
              when(row.getProducerService()).thenReturn("account-service");
              when(row.getEventType()).thenReturn("ACCOUNT_MEMBERSHIP_LEFT");
              when(row.getOccurredAt()).thenReturn(persistedOccurredAt);
              when(row.getSchemaVersion()).thenReturn(1);
              when(row.getPayloadDigestVersion()).thenReturn(1);
              when(row.getPayloadDigest()).thenReturn(persistedDigest);
              when(row.getPayload()).thenReturn(persistedPayload);
              return null;
            })
        .when(row)
        .refresh();

    AccountAuditEnvelope envelope =
        repository.append(
            auditEventId,
            "tenant",
            73L,
            "ACCOUNT_MEMBERSHIP_LEFT",
            "{\"requested\":\"in-memory material\"}");

    assertThat(envelope)
        .isEqualTo(
            new AccountAuditEnvelope(
                auditEventId,
                "tenant",
                73L,
                "account-service",
                "ACCOUNT_MEMBERSHIP_LEFT",
                persistedOccurredAt.toInstant(ZoneOffset.UTC),
                1,
                1,
                persistedDigest,
                persistedPayload));
    assertThat(envelope.payloadDigest())
        .isEqualTo(AccountAuditDigest.ofPayload(envelope.payload()));
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
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.stripLeading().startsWith("insert") && sql.contains("account_audit_outbox")) {
            executedSql.set(sql);
            return new MockResult[] {new MockResult(1)};
          }
          if (sql.stripLeading().startsWith("select") && sql.contains("account_audit_outbox")) {
            AccountAuditOutboxRecord inserted = insertedRow.get();
            if (inserted == null) {
              throw new SQLException("Audit outbox row was not inserted before readback");
            }
            DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
            Result<AccountAuditOutboxRecord> rows = resultDsl.newResult(ACCOUNT_AUDIT_OUTBOX);
            AccountAuditOutboxRecord persisted = resultDsl.newRecord(ACCOUNT_AUDIT_OUTBOX);
            persisted.setAuditEventId(inserted.getAuditEventId());
            persisted.setScope(inserted.getScope());
            persisted.setTenantId(inserted.getTenantId());
            persisted.setProducerService(inserted.getProducerService());
            persisted.setEventType(inserted.getEventType());
            persisted.setOccurredAt(inserted.getOccurredAt());
            persisted.setSchemaVersion(inserted.getSchemaVersion());
            persisted.setPayloadDigestVersion(inserted.getPayloadDigestVersion());
            persisted.setPayloadDigest(inserted.getPayloadDigest());
            persisted.setPayload(inserted.getPayload());
            rows.add(persisted);
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
