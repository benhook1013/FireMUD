package net.firedevops.firemud.accountservice.dto;

import java.time.Instant;
import java.util.UUID;

/** The immutable Account-owned delivery envelope; the SQL outbox is its authority. */
public record AccountAuditEnvelope(
    UUID auditEventId,
    String scope,
    AccountAuditTenantIdentity tenantIdentity,
    String producerService,
    String eventType,
    Instant occurredAt,
    int schemaVersion,
    int payloadDigestVersion,
    String payloadDigest,
    String payload) {
  public AccountAuditEnvelope {
    if (auditEventId == null
        || producerService == null
        || eventType == null
        || occurredAt == null
        || schemaVersion <= 0
        || payloadDigestVersion != 1
        || payloadDigest == null) {
      throw new IllegalArgumentException(
          "Account audit envelope fields are incomplete or unsupported");
    }
    if (tenantIdentity == null) {
      throw new IllegalArgumentException("Account audit tenant identity is required");
    }
    tenantIdentity.requireScope(scope);
    if (!AccountAuditDigest.isValid(payloadDigest)
        || (payload != null && !payloadDigest.equals(AccountAuditDigest.ofPayload(payload)))) {
      throw new IllegalArgumentException(
          "Account audit payload digest does not match exact UTF-8 bytes");
    }
  }

  public Long tenantId() {
    return tenantIdentity.tenantId();
  }

  public int tenantIdentityVersion() {
    return tenantIdentity.version();
  }

  public UUID tenantUuid() {
    return tenantIdentity.tenantUuid();
  }
}
