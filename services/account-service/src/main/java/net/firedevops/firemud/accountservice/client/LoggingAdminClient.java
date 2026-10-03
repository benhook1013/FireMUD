package net.firedevops.firemud.accountservice.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventResponse;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptResponse;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient
    extends AbstractReloadingBlockingGrpcClient<
        LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub> {
  public LoggingAdminClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, LoggingAdminClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getLoggingAdminService();
  }

  @Override
  protected String defaultTarget() {
    return "logging-admin-service:6565";
  }

  @Override
  protected LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        LoggingAdminServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /** Send one unchanged owner-local outbox envelope and verify the exact receiver receipt. */
  public AuditDeliveryResult deliver(AccountAuditEnvelope envelope) {
    CreateLogEventRequest request = toCreateRequest(envelope);
    try {
      CreateLogEventResponse response =
          stub().withDeadlineAfter(5, TimeUnit.SECONDS).createLogEvent(request);
      return verifyReceipt(
          envelope,
          response.getScope(),
          response.getTenantId(),
          response.getTenantIdentityVersion(),
          response.getTenantUuid(),
          response.getAuditEventId(),
          response.getSchemaVersion(),
          response.getPayloadDigestVersion(),
          response.getPayloadDigest(),
          response.getStatus(),
          response.getOutcome(),
          response.getReceiptId(),
          response.getLogEventId());
    } catch (StatusRuntimeException ex) {
      if (!mayHaveCommitted(ex.getStatus().getCode())) {
        throw ex;
      }
      ReadLogEventReceiptResponse response =
          stub().withDeadlineAfter(5, TimeUnit.SECONDS).readLogEventReceipt(toReadRequest(request));
      return verifyReceipt(
          envelope,
          response.getScope(),
          response.getTenantId(),
          response.getTenantIdentityVersion(),
          response.getTenantUuid(),
          response.getAuditEventId(),
          response.getSchemaVersion(),
          response.getPayloadDigestVersion(),
          response.getPayloadDigest(),
          response.getStatus(),
          response.getOutcome(),
          response.getReceiptId(),
          response.getLogEventId());
    }
  }

  private static CreateLogEventRequest toCreateRequest(AccountAuditEnvelope envelope) {
    CreateLogEventRequest.Builder builder =
        CreateLogEventRequest.newBuilder()
            .setScope(scopeFor(envelope))
            .setTenantIdentityVersion(envelope.tenantIdentityVersion())
            .setAuditEventId(envelope.auditEventId().toString())
            .setProducerService(envelope.producerService())
            .setEventType(envelope.eventType())
            .setOccurredAt(
                Timestamp.newBuilder()
                    .setSeconds(envelope.occurredAt().getEpochSecond())
                    .setNanos(envelope.occurredAt().getNano())
                    .build())
            .setSchemaVersion(envelope.schemaVersion())
            .setPayload(ByteString.copyFrom(envelope.payload(), StandardCharsets.UTF_8))
            .setPayloadDigestVersion(envelope.payloadDigestVersion())
            .setPayloadDigest(envelope.payloadDigest());
    if (envelope.tenantId() != null) {
      builder.setTenantId(envelope.tenantId().toString());
    }
    if (envelope.tenantUuid() != null) {
      builder.setTenantUuid(envelope.tenantUuid().toString());
    }
    return builder.build();
  }

  private static ReadLogEventReceiptRequest toReadRequest(CreateLogEventRequest request) {
    return ReadLogEventReceiptRequest.newBuilder()
        .setScope(request.getScope())
        .setTenantId(request.getTenantId())
        .setTenantIdentityVersion(request.getTenantIdentityVersion())
        .setTenantUuid(request.getTenantUuid())
        .setAuditEventId(request.getAuditEventId())
        .setProducerService(request.getProducerService())
        .setEventType(request.getEventType())
        .setOccurredAt(request.getOccurredAt())
        .setSchemaVersion(request.getSchemaVersion())
        .setPayload(request.getPayload())
        .setPayloadDigestVersion(request.getPayloadDigestVersion())
        .setPayloadDigest(request.getPayloadDigest())
        .build();
  }

  private static AccountAuditScope scopeFor(AccountAuditEnvelope envelope) {
    return "platform".equals(envelope.scope())
        ? AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM
        : AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT;
  }

  private static boolean mayHaveCommitted(Status.Code code) {
    return code == Status.Code.CANCELLED
        || code == Status.Code.DEADLINE_EXCEEDED
        || code == Status.Code.INTERNAL
        || code == Status.Code.UNKNOWN
        || code == Status.Code.UNAVAILABLE;
  }

  private static AuditDeliveryResult verifyReceipt(
      AccountAuditEnvelope envelope,
      AccountAuditScope scope,
      String tenantId,
      int tenantIdentityVersion,
      String tenantUuid,
      String auditEventId,
      int schemaVersion,
      int payloadDigestVersion,
      String payloadDigest,
      AccountAuditReceiptStatus status,
      AccountAuditReceiptOutcome outcome,
      String receiptId,
      String logEventId) {
    String expectedTenantId = envelope.tenantId() == null ? "" : envelope.tenantId().toString();
    String expectedTenantUuid =
        envelope.tenantUuid() == null ? "" : envelope.tenantUuid().toString();
    if (!auditEventId.equals(envelope.auditEventId().toString())
        || !payloadDigest.equals(envelope.payloadDigest())
        || schemaVersion != envelope.schemaVersion()
        || payloadDigestVersion != envelope.payloadDigestVersion()
        || scope != scopeFor(envelope)
        || !tenantId.equals(expectedTenantId)
        || tenantIdentityVersion != envelope.tenantIdentityVersion()
        || !tenantUuid.equals(expectedTenantUuid)) {
      throw new IllegalStateException(
          "Account audit receiver returned mismatched receipt evidence");
    }
    boolean minimized =
        status == AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_MINIMIZED
            && outcome == AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_NON_REPLAYABLE;
    boolean committed =
        status == AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED
            && (outcome == AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED
                || outcome == AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE);
    if ((!committed && !minimized) || receiptId.isBlank() || logEventId.isBlank()) {
      throw new IllegalStateException("Account audit receiver did not prove a terminal receipt");
    }
    return new AuditDeliveryResult(receiptId, logEventId, minimized);
  }

  /** Existing deferred-commerce path remains best effort until its own outbox convergence. */
  public void logPayment(long tenantId, long accountId, long transactionId) {
    String payload = "{\"accountId\":" + accountId + ",\"transactionId\":" + transactionId + "}";
    deliver(
        new AccountAuditEnvelope(
            UUID.randomUUID(),
            "tenant",
            AccountAuditTenantIdentity.retainedTenantV1(tenantId),
            "account-service",
            "PAYMENT_TXN",
            Instant.now(),
            1,
            1,
            AccountAuditDigest.ofPayload(payload),
            payload));
  }

  public record AuditDeliveryResult(String receiptId, String logEventId, boolean minimized) {}
}
