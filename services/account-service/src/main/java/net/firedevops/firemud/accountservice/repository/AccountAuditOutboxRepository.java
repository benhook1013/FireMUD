package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.tables.AccountAuditOutbox.ACCOUNT_AUDIT_OUTBOX;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountAuditOutboxRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    row.setNextAttemptAt(toLocalDateTime(occurredAt));
    row.setSchemaVersion(1);
    row.setPayloadDigestVersion(1);
    row.setPayloadDigest(digest);
    row.setPayload(payload);
    row.setDeliveryStatus("PENDING");
    row.store();
    row.refresh();
    return toEnvelope(row);
  }

  public List<AccountAuditEnvelope> pending(int limit, Instant dueBeforeOrAt) {
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .DELIVERY_STATUS
                .eq("PENDING")
                .and(ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT.le(toLocalDateTime(dueBeforeOrAt))))
        .orderBy(
            ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT.asc(),
            ACCOUNT_AUDIT_OUTBOX.CREATED_AT.asc(),
            ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.asc())
        .limit(limit)
        .fetch(this::toEnvelope);
  }

  /** Locks one exact Account JOIN transition envelope for transaction-local reconciliation. */
  public Optional<JoinAuditEvidence> findJoinEnvelopeForUpdate(UUID auditEventId, long tenantId) {
    if (auditEventId == null || tenantId <= 0) {
      throw new IllegalArgumentException("JOIN audit identity and tenant are required");
    }
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .AUDIT_EVENT_ID
                .eq(auditEventId)
                .and(ACCOUNT_AUDIT_OUTBOX.SCOPE.eq("tenant"))
                .and(ACCOUNT_AUDIT_OUTBOX.TENANT_ID.eq(tenantId))
                .and(ACCOUNT_AUDIT_OUTBOX.PRODUCER_SERVICE.eq("account-service"))
                .and(ACCOUNT_AUDIT_OUTBOX.EVENT_TYPE.eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION")))
        .forUpdate()
        .fetchOptional(
            row ->
                new JoinAuditEvidence(
                    toEnvelope(row),
                    row.getDeliveryStatus(),
                    row.getReceiverAuditProjectionVersion(),
                    row.getReceiverReceiptId(),
                    row.getReceiverLogEventId()));
  }

  /** Locks one exact Account LEFT envelope for same-owner-transaction retry proof. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<AccountAuditEnvelope> findMembershipLeftEnvelopeForUpdate(
      UUID auditEventId, long tenantId) {
    if (auditEventId == null || tenantId <= 0) {
      throw new IllegalArgumentException("LEFT audit identity and tenant are required");
    }
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .AUDIT_EVENT_ID
                .eq(auditEventId)
                .and(ACCOUNT_AUDIT_OUTBOX.SCOPE.eq("tenant"))
                .and(ACCOUNT_AUDIT_OUTBOX.TENANT_ID.eq(tenantId))
                .and(ACCOUNT_AUDIT_OUTBOX.PRODUCER_SERVICE.eq("account-service"))
                .and(ACCOUNT_AUDIT_OUTBOX.EVENT_TYPE.eq("ACCOUNT_MEMBERSHIP_LEFT")))
        .forUpdate()
        .fetchOptional(this::toEnvelope);
  }

  public void markDelivered(
      UUID auditEventId, String receiptId, String logEventId, boolean minimized) {
    if (auditEventId == null
        || receiptId == null
        || receiptId.isBlank()
        || logEventId == null
        || logEventId.isBlank()) {
      throw new IllegalArgumentException("Verified audit delivery requires nonblank identity");
    }
    var update =
        dsl.update(ACCOUNT_AUDIT_OUTBOX)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_RECEIPT_ID, receiptId)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_LOG_EVENT_ID, logEventId)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_AUDIT_PROJECTION_VERSION, 1)
            .set(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS, minimized ? "MINIMIZED" : "COMMITTED")
            .set(ACCOUNT_AUDIT_OUTBOX.LAST_ATTEMPT_AT, toLocalDateTime(Instant.now()))
            .set(ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT, (LocalDateTime) null);
    if (minimized) {
      update.set(ACCOUNT_AUDIT_OUTBOX.PAYLOAD, (String) null);
    }
    int changed =
        update
            .where(
                ACCOUNT_AUDIT_OUTBOX
                    .AUDIT_EVENT_ID
                    .eq(auditEventId)
                    .and(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS.eq("PENDING")))
            .execute();
    if (changed == 0) {
      AccountAuditOutboxRecord durableRow =
          dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
              .where(ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.eq(auditEventId))
              .fetchOne();
      String expectedStatus = minimized ? "MINIMIZED" : "COMMITTED";
      if (durableRow != null
          && expectedStatus.equals(durableRow.getDeliveryStatus())
          && Objects.equals(receiptId, durableRow.getReceiverReceiptId())
          && Objects.equals(logEventId, durableRow.getReceiverLogEventId())) {
        return;
      }
    }
    if (changed != 1) {
      throw new IllegalStateException("Audit delivery state changed concurrently");
    }
  }

  public void recordAttempt(UUID auditEventId) {
    LocalDateTime attemptedAt = toLocalDateTime(Instant.now());
    dsl.update(ACCOUNT_AUDIT_OUTBOX)
        .set(ACCOUNT_AUDIT_OUTBOX.ATTEMPT_COUNT, ACCOUNT_AUDIT_OUTBOX.ATTEMPT_COUNT.plus(1))
        .set(ACCOUNT_AUDIT_OUTBOX.LAST_ATTEMPT_AT, attemptedAt)
        .set(
            ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT,
            DSL.field(
                "CAST({0} AS TIMESTAMP) + make_interval(secs => "
                    + "LEAST(300, (5 * POWER(2, LEAST({1}, 6)))::INTEGER))",
                LocalDateTime.class, DSL.val(attemptedAt), ACCOUNT_AUDIT_OUTBOX.ATTEMPT_COUNT))
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .AUDIT_EVENT_ID
                .eq(auditEventId)
                .and(ACCOUNT_AUDIT_OUTBOX.DELIVERY_STATUS.eq("PENDING")))
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

  public record JoinAuditEvidence(
      AccountAuditEnvelope envelope,
      String deliveryStatus,
      Integer auditProjectionVersion,
      String receiptId,
      String projectionId) {
    public JoinAuditEvidence {
      Objects.requireNonNull(envelope, "envelope");
    }
  }
}
