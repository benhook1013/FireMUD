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
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountAuditOutboxRecord;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    AccountAuditTenantIdentity identity =
        "platform".equals(scope)
            ? AccountAuditTenantIdentity.platformV1()
            : AccountAuditTenantIdentity.retainedTenantV1(tenantId);
    return append(auditEventId, scope, identity, eventType, payload, false);
  }

  /**
   * Appends a version-2 canonical tenant identity in the active Account owner write transaction.
   * This storage boundary does not establish source authenticity, membership, or admission.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountAuditEnvelope appendCanonicalTenant(
      UUID auditEventId, String canonicalTenantUuid, String eventType, String payload) {
    requireReadWriteOwnerTransaction();
    return append(
        auditEventId,
        "tenant",
        AccountAuditTenantIdentity.canonicalTenantV2(canonicalTenantUuid),
        eventType,
        payload,
        true);
  }

  /** Locks and returns the exact V2 envelope identity for Account-local terminal replay checks. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountAuditEnvelope> findCanonicalTenantEnvelopeForUpdate(UUID auditEventId) {
    requireReadWriteOwnerTransaction();
    Objects.requireNonNull(auditEventId, "canonical audit event ID is required");
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.eq(auditEventId))
        .forUpdate()
        .fetchOptional(this::toEnvelope);
  }

  /** Locks and verifies the exact immutable V2 envelope previously read or appended. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<AccountAuditEnvelope> findExactCanonicalTenantEnvelopeForUpdate(
      AccountAuditEnvelope expected) {
    requireReadWriteOwnerTransaction();
    if (expected == null
        || expected.tenantIdentityVersion() != AccountAuditTenantIdentity.VERSION_2
        || expected.payload() == null) {
      throw new IllegalArgumentException("Exact canonical tenant audit envelope is required");
    }
    Optional<AccountAuditEnvelope> found =
        findCanonicalTenantEnvelopeForUpdate(expected.auditEventId());
    found.ifPresent(
        actual -> {
          if (!actual.equals(expected)) {
            throw new IllegalStateException(
                "Canonical Account audit identity conflicts with its immutable original envelope");
          }
        });
    return found;
  }

  private AccountAuditEnvelope append(
      UUID auditEventId,
      String scope,
      AccountAuditTenantIdentity identity,
      String eventType,
      String payload,
      boolean canonicalWrite) {
    Objects.requireNonNull(auditEventId, "audit event ID is required");
    Objects.requireNonNull(eventType, "audit event type is required");
    Objects.requireNonNull(payload, "audit payload is required");
    identity.requireScope(scope);
    Instant occurredAt = Instant.now();
    String digest = AccountAuditDigest.ofPayload(payload);
    AccountAuditOutboxRecord row = dsl.newRecord(ACCOUNT_AUDIT_OUTBOX);
    row.setAuditEventId(auditEventId);
    row.setScope(scope);
    row.setTenantId(identity.tenantId());
    row.setTenantIdentityVersion(identity.version());
    row.setTenantUuid(identity.tenantUuid());
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
    if (canonicalWrite) {
      row.refresh();
    }
    AccountAuditEnvelope stored = toEnvelope(row);
    if (!stored.auditEventId().equals(auditEventId)
        || !stored.scope().equals(scope)
        || !stored.tenantIdentity().equals(identity)
        || !stored.producerService().equals("account-service")
        || !stored.eventType().equals(eventType)
        || stored.schemaVersion() != 1
        || stored.payloadDigestVersion() != 1
        || !stored.payloadDigest().equals(digest)
        || !payload.equals(stored.payload())) {
      throw new IllegalStateException("Account audit outbox did not preserve its exact envelope");
    }
    if (canonicalWrite && stored.tenantIdentityVersion() != AccountAuditTenantIdentity.VERSION_2) {
      throw new IllegalStateException(
          "Account audit outbox did not preserve canonical identity version");
    }
    return stored;
  }

  public List<AccountAuditEnvelope> pending(int limit, Instant dueBeforeOrAt) {
    return dsl.selectFrom(ACCOUNT_AUDIT_OUTBOX)
        .where(
            ACCOUNT_AUDIT_OUTBOX
                .DELIVERY_STATUS
                .eq("PENDING")
                .and(ACCOUNT_AUDIT_OUTBOX.TENANT_IDENTITY_VERSION.eq(1))
                .and(ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT.le(toLocalDateTime(dueBeforeOrAt))))
        .orderBy(
            ACCOUNT_AUDIT_OUTBOX.NEXT_ATTEMPT_AT.asc(),
            ACCOUNT_AUDIT_OUTBOX.CREATED_AT.asc(),
            ACCOUNT_AUDIT_OUTBOX.AUDIT_EVENT_ID.asc())
        .limit(limit)
        .fetch(this::toEnvelope);
  }

  public void markDelivered(
      UUID auditEventId, String receiptId, String logEventId, boolean minimized) {
    var update =
        dsl.update(ACCOUNT_AUDIT_OUTBOX)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_RECEIPT_ID, receiptId)
            .set(ACCOUNT_AUDIT_OUTBOX.RECEIVER_LOG_EVENT_ID, logEventId)
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
        new AccountAuditTenantIdentity(
            row.getTenantIdentityVersion(), row.getTenantId(), row.getTenantUuid()),
        row.getProducerService(),
        row.getEventType(),
        toInstant(row.getOccurredAt()),
        row.getSchemaVersion(),
        row.getPayloadDigestVersion(),
        row.getPayloadDigest(),
        row.getPayload());
  }

  private static void requireReadWriteOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Canonical Account audit identity requires an active read-write owner transaction");
    }
  }
}
