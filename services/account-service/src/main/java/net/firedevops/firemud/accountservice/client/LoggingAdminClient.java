package net.firedevops.firemud.accountservice.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient
    extends AbstractReloadingBlockingGrpcClient<
        LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub> {
  private static final Logger logger = LoggerFactory.getLogger(LoggingAdminClient.class);

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
    AccountAuditScope scope;
    if ("platform".equals(envelope.scope())) {
      if (envelope.tenantId() != null) {
        throw new IllegalArgumentException("Platform audit events must not carry a tenant ID");
      }
      scope = AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM;
    } else if ("tenant".equals(envelope.scope())) {
      if (envelope.tenantId() == null || envelope.tenantId() <= 0) {
        throw new IllegalArgumentException("Tenant audit events require a positive tenant ID");
      }
      scope = AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT;
    } else {
      throw new IllegalArgumentException("Account audit scope must be platform or tenant");
    }

    CreateLogEventRequest.Builder builder =
        CreateLogEventRequest.newBuilder()
            .setScope(scope)
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
    CreateLogEventResponse response =
        stub().withDeadlineAfter(5, TimeUnit.SECONDS).createLogEvent(builder.build());
    if (response.hasError()) {
      String errorCode = response.getError().getCode();
      if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
        errorCode = "UNKNOWN";
      }
      throw new IllegalStateException("Account audit receiver returned error code " + errorCode);
    }
    if (response.getAuditProjectionVersion() != 1) {
      throw new IllegalStateException(
          "Account audit receiver did not prove a supported audit projection version");
    }
    if (!response.getAuditEventId().equals(envelope.auditEventId().toString())
        || !response.getPayloadDigest().equals(envelope.payloadDigest())
        || response.getSchemaVersion() != envelope.schemaVersion()
        || response.getPayloadDigestVersion() != envelope.payloadDigestVersion()
        || response.getScope() != builder.getScope()
        || !response.getTenantId().equals(builder.getTenantId())) {
      throw new IllegalStateException(
          "Account audit receiver returned mismatched receipt evidence");
    }
    boolean minimized =
        response.getStatus() == AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_MINIMIZED
            && response.getOutcome()
                == AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_NON_REPLAYABLE;
    boolean committed =
        response.getStatus() == AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED
            && (response.getOutcome()
                    == AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED
                || response.getOutcome()
                    == AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE);
    if ((!committed && !minimized)
        || response.getReceiptId().isBlank()
        || response.getLogEventId().isBlank()) {
      throw new IllegalStateException("Account audit receiver did not prove a terminal receipt");
    }
    return new AuditDeliveryResult(response.getReceiptId(), response.getLogEventId(), minimized);
  }

  /** Existing deferred-commerce path remains best effort until its own outbox convergence. */
  public void logPayment(long tenantId, long accountId, long transactionId) {
    String payload = "{\"accountId\":" + accountId + ",\"transactionId\":" + transactionId + "}";
    try {
      deliver(
          new AccountAuditEnvelope(
              UUID.randomUUID(),
              "tenant",
              tenantId,
              "account-service",
              "PAYMENT_TXN",
              Instant.now(),
              1,
              1,
              AccountAuditDigest.ofPayload(payload),
              payload));
    } catch (RuntimeException failure) {
      logger.warn(
          "Deferred payment audit delivery failed; cause={} tenantId={} accountId={} transactionId={}",
          failure.getClass().getSimpleName(),
          tenantId,
          accountId,
          transactionId);
    }
  }

  public record AuditDeliveryResult(String receiptId, String logEventId, boolean minimized) {}
}
