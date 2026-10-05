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
    if (!payloadDigest.matches("sha256:[0-9a-f]{64}")
        || (payload != null && !payloadDigest.equals(AccountAuditDigest.ofPayload(payload)))) {
      throw new IllegalArgumentException(
          "Account audit payload digest does not match exact UTF-8 bytes");
    }
  }

  /** Retained V1 constructor for the current platform and numeric-tenant audit call sites. */
  public AccountAuditEnvelope(
      UUID auditEventId,
      String scope,
      Long tenantId,
      String producerService,
      String eventType,
      Instant occurredAt,
      int schemaVersion,
      int payloadDigestVersion,
      String payloadDigest,
      String payload) {
    this(
        auditEventId,
        scope,
        retainedIdentity(scope, tenantId),
        producerService,
        eventType,
        occurredAt,
        schemaVersion,
        payloadDigestVersion,
        payloadDigest,
        payload);
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

  private static AccountAuditTenantIdentity retainedIdentity(String scope, Long tenantId) {
    if ("platform".equals(scope) && tenantId == null) {
      return AccountAuditTenantIdentity.platformV1();
    }
    if ("tenant".equals(scope) && tenantId != null && tenantId > 0L) {
      return AccountAuditTenantIdentity.retainedTenantV1(tenantId);
    }
    throw new IllegalArgumentException("Audit scope and retained tenant identity must match");
  }
}
