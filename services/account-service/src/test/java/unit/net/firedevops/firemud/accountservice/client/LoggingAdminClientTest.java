package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.service.AccountAuditDeliveryJob;
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
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class LoggingAdminClientTest {
  private static final long TENANT_ID = 20L;
  private static final long ACCOUNT_ID = 10L;
  private static final long TRANSACTION_ID = 30L;
  private static final String PAYMENT_PAYLOAD = "{\"accountId\":10,\"transactionId\":30}";

  @Test
  void acceptsOnlyExactCommittedReceiptWithCurrentProjectionVersion() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any())).thenReturn(response(1));
    AccountAuditEnvelope envelope = validEnvelope();
    LoggingAdminClient.AuditDeliveryResult result = newClient(stub).deliver(envelope);
    assertThat(result.receiptId()).isEqualTo("receipt-1");
    assertThat(result.logEventId()).isEqualTo("projection-1");
    assertThat(result.minimized()).isFalse();

    ArgumentCaptor<CreateLogEventRequest> requestCaptor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    verify(stub).createLogEvent(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getTenantIdentityVersion())
        .isEqualTo(envelope.tenantIdentityVersion());
    assertThat(requestCaptor.getValue().getTenantId()).isEqualTo(Long.toString(TENANT_ID));
    assertThat(requestCaptor.getValue().getTenantUuid()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, -1})
  void rejectsMissingOrUnsupportedProjectionVersion(int version) throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any())).thenReturn(response(version));
    assertThatThrownBy(() -> newClient(stub).deliver(validEnvelope()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account audit receiver did not prove a supported audit projection version");
  }

  @Test
  void logPaymentIgnoresTransportFailureAndPreservesAuditEnvelope() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("receiver down")));
    LoggingAdminClient client = newClient(stub);

    assertDoesNotThrow(() -> client.logPayment(TENANT_ID, ACCOUNT_ID, TRANSACTION_ID));

    assertPaymentEnvelope(stub);
  }

  @Test
  void logPaymentIgnoresUnexpectedRuntimeFailureAndPreservesAuditEnvelope() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any())).thenThrow(new IllegalStateException("private detail"));
    LoggingAdminClient client = newClient(stub);
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoggingAdminClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertDoesNotThrow(() -> client.logPayment(TENANT_ID, ACCOUNT_ID, TRANSACTION_ID));

      assertEquals(1, appender.list.size());
      ILoggingEvent warning = appender.list.get(0);
      String message = warning.getFormattedMessage();
      assertTrue(message.contains("cause=IllegalStateException"));
      assertTrue(message.contains("tenantId=" + TENANT_ID));
      assertTrue(message.contains("accountId=" + ACCOUNT_ID));
      assertTrue(message.contains("transactionId=" + TRANSACTION_ID));
      assertFalse(message.contains("private detail"));
      assertFalse(message.contains(PAYMENT_PAYLOAD));
      assertNull(warning.getThrowableProxy());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertPaymentEnvelope(stub);
  }

  @Test
  void logPaymentIgnoresMismatchedReceiptAndPreservesAuditEnvelope() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenReturn(response(1).toBuilder().setAuditEventId("other-event").build());
    LoggingAdminClient client = newClient(stub);

    assertDoesNotThrow(() -> client.logPayment(TENANT_ID, ACCOUNT_ID, TRANSACTION_ID));

    assertPaymentEnvelope(stub);
  }

  @Test
  void deliverStillThrowsWhenTheReceiverReturnsMismatchedReceiptEvidence() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenReturn(response(1).toBuilder().setAuditEventId("other-event").build());
    LoggingAdminClient client = newClient(stub);

    assertThrows(IllegalStateException.class, () -> client.deliver(validEnvelope()));
  }

  @Test
  void receiverErrorKeepsAuditPendingAndOmitsFreeFormErrorDetails() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenReturn(
            CreateLogEventResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_EVENT")
                        .setMessage("sensitive receiver detail"))
                .build());
    LoggingAdminClient client = newClient(stub);
    AccountAuditEnvelope envelope = validEnvelope();

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> client.deliver(envelope));

    assertEquals("Account audit receiver returned error code INVALID_EVENT", failure.getMessage());
    assertFalse(failure.getMessage().contains("sensitive receiver detail"));

    AccountAuditOutboxRepository outbox = mock(AccountAuditOutboxRepository.class);
    when(outbox.pending(org.mockito.ArgumentMatchers.eq(50), any(Instant.class)))
        .thenReturn(java.util.List.of(envelope));
    new AccountAuditDeliveryJob(outbox, client).deliverPending();

    verify(outbox).recordAttempt(envelope.auditEventId());
    verify(outbox, never())
        .markDelivered(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void deliverSanitizesMalformedReceiverErrorCode() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenReturn(
            CreateLogEventResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_EVENT\nprivate")
                        .setMessage("sensitive receiver detail"))
                .build());
    LoggingAdminClient client = newClient(stub);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> client.deliver(validEnvelope()));

    assertEquals("Account audit receiver returned error code UNKNOWN", failure.getMessage());
    assertFalse(failure.getMessage().contains("sensitive receiver detail"));
    assertFalse(failure.getMessage().contains("private"));
  }

  @Test
  void deliverRejectsNullOrNoncanonicalScopeBeforeTransport() throws Exception {
    var stub = mockStub();
    LoggingAdminClient client = newClient(stub);

    assertThrows(NullPointerException.class, () -> client.deliver(envelopeWithScope(null, null)));
    assertThrows(
        IllegalArgumentException.class, () -> client.deliver(envelopeWithScope("other", null)));
    assertThrows(
        IllegalArgumentException.class, () -> client.deliver(envelopeWithScope("tenant", null)));
    assertThrows(
        IllegalArgumentException.class, () -> client.deliver(envelopeWithScope("platform", 7L)));

    verify(stub, never()).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub, never()).createLogEvent(any());
  }

  private static AccountAuditEnvelope validEnvelope() {
    String payload = "{\"event\":\"test\"}";
    return new AccountAuditEnvelope(
        UUID.fromString("8a5f6238-f0d9-4992-80cb-e7f447e0f913"),
        "tenant",
        AccountAuditTenantIdentity.retainedTenantV1(TENANT_ID),
        "account-service",
        "TEST_EVENT",
        Instant.parse("2026-01-01T00:00:00Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static CreateLogEventResponse response(int projectionVersion) {
    AccountAuditEnvelope envelope = validEnvelope();
    return CreateLogEventResponse.newBuilder()
        .setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
        .setTenantId(envelope.tenantId() == null ? "" : Long.toString(envelope.tenantId()))
        .setTenantIdentityVersion(envelope.tenantIdentityVersion())
        .setTenantUuid(envelope.tenantUuid() == null ? "" : envelope.tenantUuid().toString())
        .setAuditEventId(envelope.auditEventId().toString())
        .setReceiptId("receipt-1")
        .setLogEventId("projection-1")
        .setSchemaVersion(envelope.schemaVersion())
        .setPayloadDigestVersion(envelope.payloadDigestVersion())
        .setPayloadDigest(envelope.payloadDigest())
        .setStatus(AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED)
        .setOutcome(AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED)
        .setAuditProjectionVersion(projectionVersion)
        .build();
  }

  private static AccountAuditEnvelope envelopeWithScope(String scope, Long tenantId) {
    AccountAuditEnvelope envelope = validEnvelope();
    AccountAuditTenantIdentity identity;
    if ("tenant".equals(scope)) {
      identity =
          tenantId == null
              ? new AccountAuditTenantIdentity(AccountAuditTenantIdentity.VERSION_1, null, null)
              : AccountAuditTenantIdentity.retainedTenantV1(tenantId);
    } else {
      identity =
          tenantId == null
              ? AccountAuditTenantIdentity.platformV1()
              : AccountAuditTenantIdentity.retainedTenantV1(tenantId);
    }
    return new AccountAuditEnvelope(
        envelope.auditEventId(),
        scope,
        identity,
        envelope.producerService(),
        envelope.eventType(),
        envelope.occurredAt(),
        envelope.schemaVersion(),
        envelope.payloadDigestVersion(),
        envelope.payloadDigest(),
        envelope.payload());
  }

  private static void assertPaymentEnvelope(
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub) {
    ArgumentCaptor<CreateLogEventRequest> requestCaptor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    verify(stub).createLogEvent(requestCaptor.capture());
    CreateLogEventRequest request = requestCaptor.getValue();

    assertEquals(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT, request.getScope());
    assertEquals(Long.toString(TENANT_ID), request.getTenantId());
    assertEquals(AccountAuditTenantIdentity.VERSION_1, request.getTenantIdentityVersion());
    assertEquals("", request.getTenantUuid());
    assertEquals(4, UUID.fromString(request.getAuditEventId()).version());
    assertEquals("account-service", request.getProducerService());
    assertEquals("PAYMENT_TXN", request.getEventType());
    assertEquals(1, request.getSchemaVersion());
    assertEquals(1, request.getPayloadDigestVersion());
    assertEquals(PAYMENT_PAYLOAD, request.getPayload().toStringUtf8());
    assertEquals(AccountAuditDigest.ofPayload(PAYMENT_PAYLOAD), request.getPayloadDigest());
  }

  private static LoggingAdminClient newClient(
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub) throws Exception {
    LoggingAdminClient client =
        new LoggingAdminClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop());
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub mockStub() {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub =
        mock(LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }
}
