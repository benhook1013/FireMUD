package net.firedevops.firemud.accountservice.dto;

import java.time.Instant;
import java.util.UUID;

/** The immutable Account-owned delivery envelope; the SQL outbox is its authority. */
public record AccountAuditEnvelope(
    UUID auditEventId,
    String scope,
    Long tenantId,
    String producerService,
    String eventType,
    Instant occurredAt,
    int schemaVersion,
    int payloadDigestVersion,
    String payloadDigest,
    String payload) {}
