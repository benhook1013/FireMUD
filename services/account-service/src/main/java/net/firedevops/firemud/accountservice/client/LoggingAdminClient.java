package net.firedevops.firemud.accountservice.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
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
import net.firedevops.firemud.shared.v1.ErrorDetail;
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
    return deliver(toCreateRequest(envelope));
  }

  /** Reconcile an ambiguous original delivery through an independent, exact receipt read. */
  public AuditDeliveryResult deliverOrRecover(AccountAuditEnvelope envelope) {
    CreateLogEventRequest request = toCreateRequest(envelope);
    try {
      return deliver(request);
    } catch (StatusRuntimeException failure) {
      return switch (failure.getStatus().getCode()) {
        case CANCELLED, DEADLINE_EXCEEDED, INTERNAL, UNKNOWN, UNAVAILABLE -> readReceipt(request);
        default -> throw failure;
      };
    }
  }

  private static CreateLogEventRequest toCreateRequest(AccountAuditEnvelope envelope) {
    envelope.tenantIdentity().requireScope(envelope.scope());
    AccountAuditScope scope =
        switch (envelope.scope()) {
          case "platform" -> AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM;
          case "tenant" -> AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT;
          default ->
              throw new IllegalArgumentException("Account audit scope must be platform or tenant");
        };

    CreateLogEventRequest.Builder builder =
        CreateLogEventRequest.newBuilder()
            .setScope(scope)
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

  private AuditDeliveryResult deliver(CreateLogEventRequest request) {
    CreateLogEventResponse response =
        stub().withDeadlineAfter(5, TimeUnit.SECONDS).createLogEvent(request);
    requireNoReceiverError(response.hasError() ? response.getError() : null);
    return verifyReceipt(
        request,
        response.getScope(),
        response.getTenantIdentityVersion(),
        response.getTenantId(),
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

  private AuditDeliveryResult readReceipt(CreateLogEventRequest original) {
    ReadLogEventReceiptRequest request =
        ReadLogEventReceiptRequest.newBuilder()
            .setScope(original.getScope())
            .setTenantIdentityVersion(original.getTenantIdentityVersion())
            .setTenantId(original.getTenantId())
            .setTenantUuid(original.getTenantUuid())
            .setAuditEventId(original.getAuditEventId())
            .setProducerService(original.getProducerService())
            .setEventType(original.getEventType())
            .setOccurredAt(original.getOccurredAt())
            .setSchemaVersion(original.getSchemaVersion())
            .setPayload(original.getPayload())
            .setPayloadDigestVersion(original.getPayloadDigestVersion())
            .setPayloadDigest(original.getPayloadDigest())
            .build();
    ReadLogEventReceiptResponse response =
        stub().withDeadlineAfter(5, TimeUnit.SECONDS).readLogEventReceipt(request);
    requireNoReceiverError(response.hasError() ? response.getError() : null);
    return verifyReceipt(
        original,
        response.getScope(),
        response.getTenantIdentityVersion(),
        response.getTenantId(),
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

  private static void requireNoReceiverError(ErrorDetail error) {
    if (error == null) {
      return;
    }
    String errorCode = error.getCode();
    if (!errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
      errorCode = "UNKNOWN";
    }
    throw new IllegalStateException("Account audit receiver returned error code " + errorCode);
  }

  private static AuditDeliveryResult verifyReceipt(
      CreateLogEventRequest original,
      AccountAuditScope scope,
      int identityVersion,
      String tenantId,
      String tenantUuid,
      String auditEventId,
      int schemaVersion,
      int digestVersion,
      String digest,
      AccountAuditReceiptStatus status,
      AccountAuditReceiptOutcome outcome,
      String receiptId,
      String logEventId) {
    // Absence of the version was the wire representation for V1 without a UUID.
    int receiptVersion =
        identityVersion == 0 && tenantUuid.isEmpty()
            ? AccountAuditTenantIdentity.VERSION_1
            : identityVersion;
    if (!auditEventId.equals(original.getAuditEventId())
        || !digest.equals(original.getPayloadDigest())
        || schemaVersion != original.getSchemaVersion()
        || digestVersion != original.getPayloadDigestVersion()
        || scope != original.getScope()
        || !tenantId.equals(original.getTenantId())
        || receiptVersion != original.getTenantIdentityVersion()
        || !tenantUuid.equals(original.getTenantUuid())) {
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
          "Deferred payment audit delivery failed; cause={} tenantId={} accountId={}"
              + " transactionId={}",
          failure.getClass().getSimpleName(),
          tenantId,
          accountId,
          transactionId);
    }
  }

  public record AuditDeliveryResult(String receiptId, String logEventId, boolean minimized) {}
}
