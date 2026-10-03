package net.firedevops.firemud.loggingadmin.dto;

import java.util.UUID;

public record AccountAuditReceiptDto(
    AccountAuditScope scope,
    int tenantIdentityVersion,
    Long tenantId,
    UUID tenantUuid,
    String auditEventId,
    String receiptId,
    long logEventId,
    int schemaVersion,
    int payloadDigestVersion,
    String payloadDigest,
    AccountAuditReceiptStatus status,
    AccountAuditReceiptOutcome outcome) {}
