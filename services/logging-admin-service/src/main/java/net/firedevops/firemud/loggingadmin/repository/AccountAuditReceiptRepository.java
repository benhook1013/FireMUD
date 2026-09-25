package net.firedevops.firemud.loggingadmin.repository;

import static net.firedevops.firemud.loggingadmin.jooq.tables.AccountAuditReceipts.ACCOUNT_AUDIT_RECEIPTS;

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
  private final DSLContext dsl;

  public AccountAuditReceiptRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public AccountAuditReceiptInsertResult insertIfAbsent(
      CreateLogEventRequest request, UUID receiptId) {
    long tenantKey = request.tenantId() == null ? 0L : request.tenantId();
    int inserted =
        dsl.insertInto(ACCOUNT_AUDIT_RECEIPTS)
            .set(ACCOUNT_AUDIT_RECEIPTS.RECEIPT_ID, receiptId)
            .set(ACCOUNT_AUDIT_RECEIPTS.SCOPE, request.scope().databaseValue())
            .set(ACCOUNT_AUDIT_RECEIPTS.TENANT_ID, request.tenantId())
            .set(ACCOUNT_AUDIT_RECEIPTS.TENANT_KEY, tenantKey)
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
        findByIdentity(request, tenantKey)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Account audit receipt identity conflict did not resolve to a receipt"));
    return new AccountAuditReceiptInsertResult(receipt, inserted == 1);
  }

  public Optional<AccountAuditReceipt> findByIdentity(
      CreateLogEventRequest request, long tenantKey) {
    return dsl.selectFrom(ACCOUNT_AUDIT_RECEIPTS)
        .where(
            ACCOUNT_AUDIT_RECEIPTS
                .SCOPE
                .eq(request.scope().databaseValue())
                .and(ACCOUNT_AUDIT_RECEIPTS.TENANT_KEY.eq(tenantKey))
                .and(ACCOUNT_AUDIT_RECEIPTS.AUDIT_EVENT_ID.eq(request.auditEventId())))
        .fetchOptional(this::toEntity);
  }

  private AccountAuditReceipt toEntity(Record record) {
    AccountAuditReceiptsRecord receiptRecord = (AccountAuditReceiptsRecord) record;
    return new AccountAuditReceipt(
        receiptRecord.getId(),
        receiptRecord.getReceiptId(),
        receiptRecord.getScope(),
        receiptRecord.getTenantId(),
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
