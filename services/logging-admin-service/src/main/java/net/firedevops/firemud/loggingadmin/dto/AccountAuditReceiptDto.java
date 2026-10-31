package net.firedevops.firemud.loggingadmin.dto;

public record AccountAuditReceiptDto(
    AccountAuditScope scope,
    Long tenantId,
    String auditEventId,
    String receiptId,
    long logEventId,
    int schemaVersion,
    int payloadDigestVersion,
    String payloadDigest,
    AccountAuditReceiptStatus status,
    AccountAuditReceiptOutcome outcome) {}
