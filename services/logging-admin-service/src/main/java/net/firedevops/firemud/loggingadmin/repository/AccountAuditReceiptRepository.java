package net.firedevops.firemud.loggingadmin.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.loggingadmin.jooq.tables.AccountAuditReceipts.ACCOUNT_AUDIT_RECEIPTS;
import static net.firedevops.firemud.loggingadmin.jooq.tables.LogEvents.LOG_EVENTS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.entity.AccountAuditReceipt;
import net.firedevops.firemud.loggingadmin.entity.AccountAuditReceiptInsertResult;
import net.firedevops.firemud.loggingadmin.jooq.tables.records.AccountAuditReceiptsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountAuditReceiptRepository {
  private static final String ACCOUNT_AUDIT_PROJECTION_TYPE = "ACCOUNT_AUDIT";
  private static final String ACCOUNT_AUDIT_PROJECTION_MESSAGE_PREFIX = "Account audit event ";

  private final DSLContext dsl;

  public AccountAuditReceiptRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public AccountAuditReceiptInsertResult insertIfAbsent(
      CreateLogEventRequest request, UUID receiptId) {
    long logEventId = insertOrFindProjection(request);
    int inserted =
        dsl.insertInto(ACCOUNT_AUDIT_RECEIPTS)
            .set(ACCOUNT_AUDIT_RECEIPTS.LOG_EVENT_ID, logEventId)
            .set(ACCOUNT_AUDIT_RECEIPTS.RECEIPT_ID, receiptId)
            .set(ACCOUNT_AUDIT_RECEIPTS.SCOPE, request.scope().databaseValue())
            .set(ACCOUNT_AUDIT_RECEIPTS.TENANT_IDENTITY_VERSION, request.tenantIdentityVersion())
            .set(ACCOUNT_AUDIT_RECEIPTS.TENANT_ID, request.tenantId())
            .set(ACCOUNT_AUDIT_RECEIPTS.TENANT_KEY, tenantKey(request))
            .set(ACCOUNT_AUDIT_RECEIPTS.TENANT_UUID, request.tenantUuid())
            .set(ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID, request.auditEventId())
            .set(ACCOUNT_AUDIT_RECEIPTS.PRODUCER_SERVICE, request.producerService())
            .set(ACCOUNT_AUDIT_RECEIPTS.EVENT_TYPE, request.eventType())
            .set(ACCOUNT_AUDIT_RECEIPTS.OCCURRED_AT_SECONDS, request.occurredAt().getEpochSecond())
            .set(ACCOUNT_AUDIT_RECEIPTS.OCCURRED_AT_NANOS, request.occurredAt().getNano())
            .set(ACCOUNT_AUDIT_RECEIPTS.SCHEMA_VERSION, request.schemaVersion())
            .set(ACCOUNT_AUDIT_RECEIPTS.PAYLOAD_DIGEST_VERSION, request.payloadDigestVersion())
            .set(ACCOUNT_AUDIT_RECEIPTS.PAYLOAD_DIGEST, request.payloadDigest())
            .set(ACCOUNT_AUDIT_RECEIPTS.PAYLOAD, request.payload().toByteArray())
            .set(ACCOUNT_AUDIT_RECEIPTS.STATUS, "COMMITTED")
            .set(ACCOUNT_AUDIT_RECEIPTS.OUTCOME, "ACCEPTED")
            .onConflictDoNothing()
            .execute();
    AccountAuditReceipt receipt =
        findByIdentity(request)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Account audit receipt identity conflict did not resolve to a receipt"));
    return new AccountAuditReceiptInsertResult(receipt, inserted == 1);
  }

  private long insertOrFindProjection(CreateLogEventRequest request) {
    dsl.insertInto(LOG_EVENTS)
        .set(LOG_EVENTS.SCOPE, request.scope().databaseValue())
        .set(LOG_EVENTS.TENANT_IDENTITY_VERSION, request.tenantIdentityVersion())
        .set(LOG_EVENTS.TENANT_ID, request.tenantId())
        .set(LOG_EVENTS.TENANT_KEY, tenantKey(request))
        .set(LOG_EVENTS.TENANT_UUID, request.tenantUuid())
        .set(LOG_EVENTS.AUDIT_EVENT_ID, request.auditEventId())
        .set(LOG_EVENTS.TYPE, ACCOUNT_AUDIT_PROJECTION_TYPE)
        .set(LOG_EVENTS.MESSAGE, ACCOUNT_AUDIT_PROJECTION_MESSAGE_PREFIX + request.auditEventId())
        .set(LOG_EVENTS.TIMESTAMP, toLocalDateTime(request.occurredAt()))
        .set(LOG_EVENTS.ACCOUNT_ID, (Long) null)
        .onConflictDoNothing()
        .execute();

    var identityCondition =
        LOG_EVENTS
            .SCOPE
            .eq(request.scope().databaseValue())
            .and(LOG_EVENTS.TENANT_IDENTITY_VERSION.eq(request.tenantIdentityVersion()))
            .and(LOG_EVENTS.AUDIT_EVENT_ID.eq(request.auditEventId()));
    identityCondition =
        request.tenantIdentityVersion() == 1
            ? identityCondition.and(LOG_EVENTS.TENANT_KEY.eq(tenantKey(request)))
            : identityCondition.and(LOG_EVENTS.TENANT_UUID.eq(request.tenantUuid()));
    Long logEventId =
        dsl.select(LOG_EVENTS.ID).from(LOG_EVENTS).where(identityCondition).fetchOne(LOG_EVENTS.ID);
    if (logEventId == null) {
      throw new IllegalStateException("Account audit projection identity did not resolve");
    }
    return logEventId;
  }

  public Optional<AccountAuditReceipt> findByIdentity(CreateLogEventRequest request) {
    var identityCondition =
        ACCOUNT_AUDIT_RECEIPTS
            .SCOPE
            .eq(request.scope().databaseValue())
            .and(ACCOUNT_AUDIT_RECEIPTS.TENANT_IDENTITY_VERSION.eq(request.tenantIdentityVersion()))
            .and(ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(request.auditEventId()));
    identityCondition =
        request.tenantIdentityVersion() == 1
            ? identityCondition.and(ACCOUNT_AUDIT_RECEIPTS.TENANT_KEY.eq(tenantKey(request)))
            : identityCondition.and(ACCOUNT_AUDIT_RECEIPTS.TENANT_UUID.eq(request.tenantUuid()));
    return dsl.selectFrom(ACCOUNT_AUDIT_RECEIPTS)
        .where(identityCondition)
        .fetchOptional(this::toEntity);
  }

  private static Long tenantKey(CreateLogEventRequest request) {
    if (request.tenantIdentityVersion() != 1) {
      return null;
    }
    return request.tenantId() == null ? Long.valueOf(0L) : request.tenantId();
  }

  private AccountAuditReceipt toEntity(Record record) {
    AccountAuditReceiptsRecord receiptRecord = (AccountAuditReceiptsRecord) record;
    return new AccountAuditReceipt(
        receiptRecord.getId(),
        receiptRecord.getLogEventId(),
        receiptRecord.getReceiptId(),
        receiptRecord.getScope(),
        receiptRecord.getTenantIdentityVersion(),
        receiptRecord.getTenantId(),
        receiptRecord.getTenantUuid(),
        receiptRecord.getAuditEventId(),
        receiptRecord.getProducerService(),
        receiptRecord.getEventType(),
        receiptRecord.getOccurredAtSeconds(),
        receiptRecord.getOccurredAtNanos(),
        receiptRecord.getSchemaVersion(),
        receiptRecord.getPayloadDigestVersion(),
        receiptRecord.getPayloadDigest(),
        receiptRecord.getPayload(),
        receiptRecord.getStatus(),
        receiptRecord.getOutcome());
  }
}
