package net.firedevops.firemud.accountservice.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventResponse;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoggingAdminClientTest {
  private static final long TENANT_ID = 20L;
  private static final long ACCOUNT_ID = 10L;
  private static final long TRANSACTION_ID = 30L;
  private static final String PAYMENT_PAYLOAD = "{\"accountId\":10,\"transactionId\":30}";

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

    assertDoesNotThrow(() -> client.logPayment(TENANT_ID, ACCOUNT_ID, TRANSACTION_ID));

    assertPaymentEnvelope(stub);
  }

  @Test
  void logPaymentIgnoresMismatchedReceiptAndPreservesAuditEnvelope() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenReturn(CreateLogEventResponse.newBuilder().setAuditEventId("other-event").build());
    LoggingAdminClient client = newClient(stub);

    assertDoesNotThrow(() -> client.logPayment(TENANT_ID, ACCOUNT_ID, TRANSACTION_ID));

    assertPaymentEnvelope(stub);
  }

  @Test
  void deliverStillThrowsWhenTheReceiverReturnsMismatchedReceiptEvidence() throws Exception {
    var stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenReturn(CreateLogEventResponse.newBuilder().setAuditEventId("other-event").build());
    LoggingAdminClient client = newClient(stub);

    assertThrows(IllegalStateException.class, () -> client.deliver(validEnvelope()));
  }

  private static AccountAuditEnvelope validEnvelope() {
    String payload = "{\"event\":\"test\"}";
    return new AccountAuditEnvelope(
        UUID.fromString("8a5f6238-f0d9-4992-80cb-e7f447e0f913"),
        "tenant",
        TENANT_ID,
        "account-service",
        "TEST_EVENT",
        Instant.parse("2026-01-01T00:00:00Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static void assertPaymentEnvelope(
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub) {
    ArgumentCaptor<CreateLogEventRequest> requestCaptor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    verify(stub).createLogEvent(requestCaptor.capture());
    CreateLogEventRequest request = requestCaptor.getValue();

    assertEquals(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT, request.getScope());
    assertEquals(Long.toString(TENANT_ID), request.getTenantId());
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
