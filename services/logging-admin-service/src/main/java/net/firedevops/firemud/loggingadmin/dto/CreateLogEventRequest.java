package net.firedevops.firemud.loggingadmin.dto;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;

/** The immutable Account audit envelope accepted by the existing CreateLogEvent RPC. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "Protobuf ByteString is immutable, so retaining and exposing the payload is safe.")
public record CreateLogEventRequest(
    AccountAuditScope scope,
    Long tenantId,
    String auditEventId,
    String producerService,
    String eventType,
    Instant occurredAt,
    int schemaVersion,
    ByteString payload,
    int payloadDigestVersion,
    String payloadDigest) {}
