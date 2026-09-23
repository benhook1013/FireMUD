package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.tables.AccountAuditOutbox.ACCOUNT_AUDIT_OUTBOX;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountAuditOutboxRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Owner-local audit identity, immutable envelope, and durable delivery state. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountAuditOutboxRepository {
  private final DSLContext dsl;

  public AccountAuditOutboxRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public AccountAuditEnvelope append(
      UUID auditEventId, String scope, Long tenantId, String eventType, String payload) {
    if (!("platform".equals(scope) && tenantId == null)
        && !("tenant".equals(scope) && tenantId != null && tenantId > 0)) {
      throw new IllegalArgumentException("Audit scope and tenant ID must match");
    }
    Instant occurredAt = Instant.now();
    String digest = AccountAuditDigest.ofPayload(payload);
    AccountAuditOutboxRecord row = dsl.newRecord(ACCOUNT_AUDIT_OUTBOX);
    row.setAuditEventId(auditEventId);
    row.setScope(scope);
    row.setTenantId(tenantId);
    row.setProducerService("account-service");
    row.setEventType(eventType);
    row.setOccurredAt(toLocalDateTime(occurredAt));
    row.setSchemaVersion(1);
    row.setPayloadDigestVersion(1);
    row.setPayloadDigest(digest);
    row.setPayload(payload);
    row.setDeliveryStatus("PENDING");
    row.store();
    return toEnvelope(row);
  }

  public List<AccountAuditEnvelope> pending(int limit) {
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS.eq("PENDING"))
        .orderBy(ACCOUNT_AUDIT_OUTBOX.CREATED_AT.asc(), ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.asc())
        .limit(limit)
        .fetch(this::toEnvelope);
  }

  public void markDelivered(
      UUID auditEventId, String receiptId, String logEventId, boolean minimized) {
    int changed =
        dsl.update(ACCOUNT_AUDIT_OUTBOX)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_RECEIPT_ID, receiptId)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_LOG_EVENT_ID, logEventId)
            .set(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS, minimized ? "MINIMIZED" : "COMMITTED")
            .set(ACCOUNT_AUDIT_OUTBOX.LAST_ATTEMPT_AT, toLocalDateTime(Instant.now()))
            .where(
                ACCOUNT_AUDIT_OUTBOX
                    .AUDIT_EVENT_ID
                    .eq(auditEventId)
                    .and(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS.eq("PENDING")))
            .execute();
    if (changed != 1) {
      throw new IllegalStateException("Audit delivery state changed concurrently");
    }
  }

  public void recordAttempt(UUID auditEventId) {
    dsl.update(ACCOUNT_AUDIT_OUTBOX)
        .set(ACCOUNT_AUDIT_OUTBOX.LAST_ATTEMPT_AT, toLocalDateTime(Instant.now()))
        .where(ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.eq(auditEventId))
        .execute();
  }

  private AccountAuditEnvelope toEnvelope(AccountAuditOutboxRecord row) {
    return new AccountAuditEnvelope(
        row.getAuditEventId(),
        row.getScope(),
        row.getTenantId(),
        row.getProducerService(),
        row.getEventType(),
        toInstant(row.getOccurredAt()),
        row.getSchemaVersion(),
        row.getPayloadDigestVersion(),
        row.getPayloadDigest(),
        row.getPayload());
  }
}
