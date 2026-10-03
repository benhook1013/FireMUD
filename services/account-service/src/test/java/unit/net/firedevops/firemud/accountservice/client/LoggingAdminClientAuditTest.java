package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoggingAdminClientAuditTest {
  private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String TENANT_UUID = "33333333-3333-4333-8333-333333333333";

  @Test
  void canonicalUuidEnvelopeUsesExactReadbackAfterAmbiguousCreate() throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("lost ack")));
    when(stub.readLogEventReceipt(any())).thenReturn(canonicalReceipt());
    LoggingAdminClient client = newClient(stub);
    AccountAuditEnvelope envelope = canonicalEnvelope();

    LoggingAdminClient.AuditDeliveryResult result = client.deliver(envelope);

    assertThat(result)
        .isEqualTo(new LoggingAdminClient.AuditDeliveryResult("receipt-2", "log-9", false));
    ArgumentCaptor<CreateLogEventRequest> createCaptor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    ArgumentCaptor<ReadLogEventReceiptRequest> readCaptor =
        ArgumentCaptor.forClass(ReadLogEventReceiptRequest.class);
    verify(stub).createLogEvent(createCaptor.capture());
    verify(stub).readLogEventReceipt(readCaptor.capture());
    CreateLogEventRequest create = createCaptor.getValue();
    ReadLogEventReceiptRequest read = readCaptor.getValue();
    assertThat(create.getScope()).isEqualTo(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT);
    assertThat(create.getTenantIdentityVersion()).isEqualTo(2);
    assertThat(create.getTenantId()).isEmpty();
    assertThat(create.getTenantUuid()).isEqualTo(TENANT_UUID);
    assertThat(create.getPayload().toStringUtf8()).isEqualTo(envelope.payload());
    assertThat(read.getTenantIdentityVersion()).isEqualTo(create.getTenantIdentityVersion());
    assertThat(read.getTenantUuid()).isEqualTo(create.getTenantUuid());
    assertThat(read.getAuditEventId()).isEqualTo(create.getAuditEventId());
    assertThat(read.getProducerService()).isEqualTo(create.getProducerService());
    assertThat(read.getEventType()).isEqualTo(create.getEventType());
    assertThat(read.getOccurredAt()).isEqualTo(create.getOccurredAt());
    assertThat(read.getSchemaVersion()).isEqualTo(create.getSchemaVersion());
    assertThat(read.getPayload()).isEqualTo(create.getPayload());
    assertThat(read.getPayloadDigestVersion()).isEqualTo(create.getPayloadDigestVersion());
    assertThat(read.getPayloadDigest()).isEqualTo(create.getPayloadDigest());
    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub, times(1)).createLogEvent(any());
  }

  @Test
  void retainedNumericEnvelopeUsesExplicitVersionOneWireIdentity() throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    AccountAuditEnvelope envelope = retainedEnvelope();
    when(stub.createLogEvent(any()))
        .thenReturn(
            CreateLogEventResponse.newBuilder()
                .setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
                .setTenantId("73")
                .setTenantIdentityVersion(1)
                .setAuditEventId(EVENT_ID.toString())
                .setReceiptId("receipt-1")
                .setLogEventId("log-8")
                .setSchemaVersion(1)
                .setPayloadDigestVersion(1)
                .setPayloadDigest(envelope.payloadDigest())
                .setStatus(AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED)
                .setOutcome(AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED)
                .build());
    LoggingAdminClient client = newClient(stub);

    assertThat(client.deliver(envelope).receiptId()).isEqualTo("receipt-1");
    ArgumentCaptor<CreateLogEventRequest> requestCaptor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    verify(stub).createLogEvent(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getTenantIdentityVersion()).isEqualTo(1);
    assertThat(requestCaptor.getValue().getTenantId()).isEqualTo("73");
    assertThat(requestCaptor.getValue().getTenantUuid()).isEmpty();
    verify(stub, never()).readLogEventReceipt(any());
  }

  @Test
  void platformEnvelopeUsesVersionOneWithoutEitherTenantValue() throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    AccountAuditEnvelope envelope = platformEnvelope();
    when(stub.createLogEvent(any()))
        .thenReturn(
            CreateLogEventResponse.newBuilder()
                .setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM)
                .setTenantIdentityVersion(1)
                .setAuditEventId(EVENT_ID.toString())
                .setReceiptId("receipt-platform")
                .setLogEventId("log-platform")
                .setSchemaVersion(1)
                .setPayloadDigestVersion(1)
                .setPayloadDigest(envelope.payloadDigest())
                .setStatus(AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED)
                .setOutcome(AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED)
                .build());
    LoggingAdminClient client = newClient(stub);

    assertThat(client.deliver(envelope).receiptId()).isEqualTo("receipt-platform");
    ArgumentCaptor<CreateLogEventRequest> requestCaptor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    verify(stub).createLogEvent(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getTenantIdentityVersion()).isEqualTo(1);
    assertThat(requestCaptor.getValue().getTenantId()).isEmpty();
    assertThat(requestCaptor.getValue().getTenantUuid()).isEmpty();
  }

  @Test
  void changedTypedReceiptIdentityIsNotTerminalSuccess() throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenReturn(
            canonicalCreateReceipt().toBuilder()
                .setTenantUuid("44444444-4444-4444-8444-444444444444")
                .build());
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(canonicalEnvelope()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatched receipt evidence");
    verify(stub, never()).readLogEventReceipt(any());
  }

  @Test
  void notFoundReceiptAfterAmbiguousCreateRemainsPending() throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    when(stub.createLogEvent(any()))
        .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));
    StatusRuntimeException notFound = new StatusRuntimeException(Status.NOT_FOUND);
    when(stub.readLogEventReceipt(any())).thenThrow(notFound);
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(canonicalEnvelope())).isSameAs(notFound);
    verify(stub, times(1)).createLogEvent(any());
    verify(stub, times(1)).readLogEventReceipt(any());
  }

  @Test
  void unavailableReceiptAfterAmbiguousCreateRemainsPending() throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    when(stub.createLogEvent(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);
    when(stub.readLogEventReceipt(any())).thenThrow(unavailable);
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(canonicalEnvelope())).isSameAs(unavailable);
    verify(stub, times(1)).createLogEvent(any());
    verify(stub, times(1)).readLogEventReceipt(any());
  }

  private static LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub mockStub() {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub =
        mock(LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
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

  private static AccountAuditEnvelope canonicalEnvelope() {
    String payload = "{\"join\":\"exact bytes\"}";
    return new AccountAuditEnvelope(
        EVENT_ID,
        "tenant",
        AccountAuditTenantIdentity.canonicalTenantV2(TENANT_UUID),
        "account-service",
        "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
        Instant.parse("2026-10-01T12:34:56.123456Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static AccountAuditEnvelope retainedEnvelope() {
    String payload = "{\"retained\":true}";
    return new AccountAuditEnvelope(
        EVENT_ID,
        "tenant",
        AccountAuditTenantIdentity.retainedTenantV1(73L),
        "account-service",
        "ACCOUNT_MEMBERSHIP_LEFT",
        Instant.parse("2026-10-01T12:34:56Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static AccountAuditEnvelope platformEnvelope() {
    String payload = "{\"registration\":true}";
    return new AccountAuditEnvelope(
        EVENT_ID,
        "platform",
        AccountAuditTenantIdentity.platformV1(),
        "account-service",
        "ACCOUNT_REGISTERED",
        Instant.parse("2026-10-01T12:34:56Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static ReadLogEventReceiptResponse canonicalReceipt() {
    AccountAuditEnvelope envelope = canonicalEnvelope();
    return ReadLogEventReceiptResponse.newBuilder()
        .setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
        .setTenantIdentityVersion(2)
        .setTenantUuid(TENANT_UUID)
        .setAuditEventId(EVENT_ID.toString())
        .setReceiptId("receipt-2")
        .setLogEventId("log-9")
        .setSchemaVersion(1)
        .setPayloadDigestVersion(1)
        .setPayloadDigest(envelope.payloadDigest())
        .setStatus(AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED)
        .setOutcome(AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE)
        .build();
  }

  private static CreateLogEventResponse canonicalCreateReceipt() {
    AccountAuditEnvelope envelope = canonicalEnvelope();
    return CreateLogEventResponse.newBuilder()
        .setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
        .setTenantIdentityVersion(2)
        .setTenantUuid(TENANT_UUID)
        .setAuditEventId(EVENT_ID.toString())
        .setReceiptId("receipt-2")
        .setLogEventId("log-9")
        .setSchemaVersion(1)
        .setPayloadDigestVersion(1)
        .setPayloadDigest(envelope.payloadDigest())
        .setStatus(AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED)
        .setOutcome(AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED)
        .build();
  }
}
