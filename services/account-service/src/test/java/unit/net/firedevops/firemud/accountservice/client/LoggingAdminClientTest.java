package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventResponse;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoggingAdminClientTest {
  private static final UUID AUDIT_EVENT_ID =
      UUID.fromString("f2b6e93a-d948-40b2-9b31-7291a5116237");
  private static final long TENANT_ID = 37L;

  @Test
  void acceptsOnlyExactCommittedReceiptWithCurrentProjectionVersion() throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    when(stub.createLogEvent(any())).thenReturn(response(1));
    LoggingAdminClient client = newClient(stub);

    LoggingAdminClient.AuditDeliveryResult result = client.deliver(envelope());

    assertThat(result.receiptId()).isEqualTo("receipt-1");
    assertThat(result.logEventId()).isEqualTo("projection-1");
    assertThat(result.minimized()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, -1})
  void rejectsMissingOrUnsupportedProjectionVersion(int version) throws Exception {
    LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub = mockStub();
    when(stub.createLogEvent(any())).thenReturn(response(version));
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(envelope()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account audit receiver did not prove a supported audit projection version");
  }

  private static AccountAuditEnvelope envelope() {
    String payload = "{\"accountId\":19,\"tenantId\":37}";
    return new AccountAuditEnvelope(
        AUDIT_EVENT_ID,
        "tenant",
        TENANT_ID,
        "account-service",
        "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
        Instant.parse("2026-09-25T00:00:00Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static CreateLogEventResponse response(int projectionVersion) {
    AccountAuditEnvelope envelope = envelope();
    return CreateLogEventResponse.newBuilder()
        .setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
        .setTenantId(Long.toString(TENANT_ID))
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
