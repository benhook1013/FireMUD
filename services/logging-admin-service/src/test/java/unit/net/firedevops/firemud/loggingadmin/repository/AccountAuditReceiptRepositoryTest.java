package net.firedevops.firemud.loggingadmin.repository;

import static net.firedevops.firemud.loggingadmin.jooq.tables.AccountAuditReceipts.ACCOUNT_AUDIT_RECEIPTS;
import static net.firedevops.firemud.loggingadmin.jooq.tables.LogEvents.LOG_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.entity.AccountAuditReceiptInsertResult;
import net.firedevops.firemud.loggingadmin.jooq.tables.records.AccountAuditReceiptsRecord;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class AccountAuditReceiptRepositoryTest {
  private static final String AUDIT_EVENT_ID = "audit-event-1";
  private static final UUID RECEIPT_ID = UUID.fromString("4829d25a-a6d1-4a43-b82b-3e82d538ad16");
  private static final long LOG_EVENT_ID = 91L;

  @Test
  void projectionLookupUsesScopeTenantKeyAndExactEventIdentity() {
    assertProjectionLookup(AccountAuditScope.PLATFORM, null, 0L);
    assertProjectionLookup(AccountAuditScope.TENANT, 73L, 73L);
  }

  private static void assertProjectionLookup(
      AccountAuditScope scope, Long tenantId, long expectedTenantKey) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    List<String> executedSql = new ArrayList<>();
    List<Object[]> projectionInsertBindings = new ArrayList<>();
    List<Object[]> projectionLookupBindings = new ArrayList<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().trim().toLowerCase(Locale.ROOT);
          executedSql.add(sql);
          if (sql.startsWith("insert") && sql.contains("log_events")) {
            projectionInsertBindings.add(context.bindings());
            return new MockResult[] {new MockResult(1)};
          }
          if (sql.startsWith("select") && sql.contains("log_events")) {
            projectionLookupBindings.add(context.bindings());
            Record1<Long> row = resultDsl.newRecord(LOG_EVENTS.ID);
            row.set(LOG_EVENTS.ID, LOG_EVENT_ID);
            Result<Record1<Long>> result = resultDsl.newResult(LOG_EVENTS.ID);
            result.add(row);
            return new MockResult[] {new MockResult(1, result)};
          }
          if (sql.startsWith("insert") && sql.contains("account_audit_receipts")) {
            return new MockResult[] {new MockResult(1)};
          }
          if (sql.startsWith("select") && sql.contains("account_audit_receipts")) {
            AccountAuditReceiptsRecord row = receiptRecord(resultDsl, scope, tenantId);
            Result<AccountAuditReceiptsRecord> result = resultDsl.newResult(ACCOUNT_AUDIT_RECEIPTS);
            result.add(row);
            return new MockResult[] {new MockResult(1, result)};
          }
          throw new SQLException("Unexpected Account audit receipt query: " + sql);
        };

    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    AccountAuditReceiptInsertResult inserted =
        new AccountAuditReceiptRepository(dsl).insertIfAbsent(request(scope, tenantId), RECEIPT_ID);

    assertThat(executedSql).hasSize(4);
    assertThat(executedSql.get(0)).startsWith("insert").contains("log_events");
    assertThat(executedSql.get(1)).startsWith("select").contains("log_events");
    assertThat(executedSql.get(2)).startsWith("insert").contains("account_audit_receipts");
    assertThat(executedSql.get(3)).startsWith("select").contains("account_audit_receipts");
    assertThat(projectionInsertBindings).hasSize(1);
    assertThat(projectionInsertBindings.get(0)).contains(expectedTenantKey, AUDIT_EVENT_ID);

    String lookupSql = executedSql.get(1);
    assertThat(lookupSql)
        .contains("scope", "tenant_key", "audit_event_id")
        .doesNotContain("tenant_id");
    assertThat(projectionLookupBindings).hasSize(1);
    assertThat(projectionLookupBindings.get(0))
        .containsExactly(scope.databaseValue(), expectedTenantKey, AUDIT_EVENT_ID);
    assertThat(inserted.inserted()).isTrue();
    assertThat(inserted.receipt().logEventId()).isEqualTo(LOG_EVENT_ID);
    assertThat(inserted.receipt().receiptId()).isEqualTo(RECEIPT_ID);
    assertThat(inserted.receipt().scope()).isEqualTo(scope.databaseValue());
    assertThat(inserted.receipt().tenantId()).isEqualTo(tenantId);
    assertThat(inserted.receipt().auditEventId()).isEqualTo(AUDIT_EVENT_ID);
  }

  private static CreateLogEventRequest request(AccountAuditScope scope, Long tenantId) {
    return new CreateLogEventRequest(
        scope,
        tenantId,
        AUDIT_EVENT_ID,
        "account-service",
        "ACCOUNT_REGISTERED",
        Instant.parse("2026-10-01T12:00:00Z"),
        1,
        ByteString.copyFromUtf8("{}"),
        1,
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  }

  private static AccountAuditReceiptsRecord receiptRecord(
      DSLContext dsl, AccountAuditScope scope, Long tenantId) {
    AccountAuditReceiptsRecord row = dsl.newRecord(ACCOUNT_AUDIT_RECEIPTS);
    row.setId(5L);
    row.setLogEventId(LOG_EVENT_ID);
    row.setReceiptId(RECEIPT_ID);
    row.setScope(scope.databaseValue());
    row.setTenantId(tenantId);
    row.setAuditEventId(AUDIT_EVENT_ID);
    row.setProducerService("account-service");
    row.setEventType("ACCOUNT_REGISTERED");
    row.setOccurredAtSeconds(Instant.parse("2026-10-01T12:00:00Z").getEpochSecond());
    row.setOccurredAtNanos(0);
    row.setSchemaVersion(1);
    row.setPayloadDigestVersion(1);
    row.setPayloadDigest("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    row.setPayload("{}".getBytes(StandardCharsets.UTF_8));
    row.setStatus("COMMITTED");
    row.setOutcome("ACCEPTED");
    return row;
  }
}
