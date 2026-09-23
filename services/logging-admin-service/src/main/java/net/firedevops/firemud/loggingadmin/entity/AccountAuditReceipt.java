package net.firedevops.firemud.loggingadmin.entity;

import java.util.UUID;

/** A durable receiver-side audit envelope and its readback receipt. */
public record AccountAuditReceipt(
    long id,
    UUID receiptId,
    String scope,
    Long tenantId,
    String auditEventId,
    String producerService,
    String eventType,
    long occurredAtSeconds,
    int occurredAtNanos,
    int schemaVersion,
    int payloadDigestVersion,
    String payloadDigest,
    byte[] payload,
    String status,
    String outcome) {
  public AccountAuditReceipt {
    payload = payload == null ? null : payload.clone();
  }

  @Override
  public byte[] payload() {
    return payload == null ? null : payload.clone();
  }
}
