package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class LoggingAdminClientAuditTest {
  private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String TENANT_UUID = "a3333333-3333-4333-8333-333333333333";
  private static final String OTHER_TENANT_UUID = "b4444444-4444-4444-8444-444444444444";

  @Test
  void canonicalUuidEnvelopeSendsExactVersionTwoCreateWireIdentity() throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope = canonicalEnvelope();
    when(stub.createLogEvent(any())).thenReturn(receipt(envelope).build());

    assertThat(newClient(stub).deliver(envelope))
        .isEqualTo(new LoggingAdminClient.AuditDeliveryResult("receipt-2", "log-9", false));

    CreateLogEventRequest request = capturedRequest(stub);
    CreateLogEventRequest wire = CreateLogEventRequest.parseFrom(request.toByteArray());
    assertThat(wire).isEqualTo(request);
    assertThat(wire.getScope()).isEqualTo(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT);
    assertThat(wire.getTenantIdentityVersion()).isEqualTo(2);
    assertThat(wire.getTenantId()).isEmpty();
    assertThat(wire.getTenantUuid()).isEqualTo(TENANT_UUID);
    assertThat(wire.getAuditEventId()).isEqualTo(EVENT_ID.toString());
    assertThat(wire.getProducerService()).isEqualTo(envelope.producerService());
    assertThat(wire.getEventType()).isEqualTo(envelope.eventType());
    assertThat(wire.getOccurredAt().getSeconds()).isEqualTo(envelope.occurredAt().getEpochSecond());
    assertThat(wire.getOccurredAt().getNanos()).isEqualTo(envelope.occurredAt().getNano());
    assertThat(wire.getSchemaVersion()).isEqualTo(3);
    assertThat(wire.getPayloadDigestVersion()).isEqualTo(1);
    assertThat(wire.getPayloadDigest()).isEqualTo(envelope.payloadDigest());
    assertThat(wire.getPayload().toStringUtf8()).isEqualTo(envelope.payload());
    verifyOnlyCreate(stub);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void retainedNumericEnvelopeSendsExplicitVersionOneAndAcceptsOnlyItsReceipt(int receiptVersion)
      throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope =
        envelope("tenant", AccountAuditTenantIdentity.retainedTenantV1(73L));
    when(stub.createLogEvent(any()))
        .thenReturn(receipt(envelope).setTenantIdentityVersion(receiptVersion).build());

    assertThat(newClient(stub).deliver(envelope).receiptId()).isEqualTo("receipt-2");

    CreateLogEventRequest request = capturedRequest(stub);
    assertThat(request.getScope()).isEqualTo(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT);
    assertThat(request.getTenantIdentityVersion()).isEqualTo(1);
    assertThat(request.getTenantId()).isEqualTo("73");
    assertThat(request.getTenantUuid()).isEmpty();
    verifyOnlyCreate(stub);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void platformEnvelopeSendsVersionOneWithoutEitherTenantValue(int receiptVersion)
      throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope = envelope("platform", AccountAuditTenantIdentity.platformV1());
    when(stub.createLogEvent(any()))
        .thenReturn(receipt(envelope).setTenantIdentityVersion(receiptVersion).build());

    assertThat(newClient(stub).deliver(envelope).receiptId()).isEqualTo("receipt-2");

    CreateLogEventRequest request = capturedRequest(stub);
    assertThat(request.getScope()).isEqualTo(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM);
    assertThat(request.getTenantIdentityVersion()).isEqualTo(1);
    assertThat(request.getTenantId()).isEmpty();
    assertThat(request.getTenantUuid()).isEmpty();
    verifyOnlyCreate(stub);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("changedCanonicalIdentities")
  void canonicalReceiptRejectsChangedMissingOrMixedIdentity(
      String description, int version, String tenantId, String tenantUuid) throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope = canonicalEnvelope();
    when(stub.createLogEvent(any()))
        .thenReturn(
            receipt(envelope)
                .setTenantIdentityVersion(version)
                .setTenantId(tenantId)
                .setTenantUuid(tenantUuid)
                .build());
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(envelope))
        .as(description)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatched receipt evidence");

    capturedRequest(stub);
    verifyOnlyCreate(stub);
  }

  private static Stream<Arguments> changedCanonicalIdentities() {
    return Stream.of(
        arguments("changed UUID", 2, "", OTHER_TENANT_UUID),
        arguments("missing UUID", 2, "", ""),
        arguments("noncanonical UUID", 2, "", TENANT_UUID.toUpperCase(Locale.ROOT)),
        arguments("nil UUID", 2, "", "00000000-0000-0000-0000-000000000000"),
        arguments("missing version with UUID", 0, "", TENANT_UUID),
        arguments("missing version and UUID", 0, "", ""),
        arguments("V1 version with UUID", 1, "", TENANT_UUID),
        arguments("V1 fallback without UUID", 1, "", ""),
        arguments("legacy numeric fallback", 0, "73", ""),
        arguments("explicit numeric V1 fallback", 1, "73", ""),
        arguments("unknown version", 3, "", TENANT_UUID),
        arguments("negative version", -1, "", TENANT_UUID),
        arguments("mixed V2 numeric and UUID", 2, "73", TENANT_UUID),
        arguments("numeric-only V2", 2, "73", ""));
  }

  @ParameterizedTest(name = "{0}, version={1}, numeric={2}, UUID={3}")
  @MethodSource("changedVersionOneIdentities")
  void versionOneReceiptRejectsChangedVersionOrTenantShape(
      String scope, int version, String tenantId, String tenantUuid) throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope =
        envelope(
            scope,
            "platform".equals(scope)
                ? AccountAuditTenantIdentity.platformV1()
                : AccountAuditTenantIdentity.retainedTenantV1(73L));
    when(stub.createLogEvent(any()))
        .thenReturn(
            receipt(envelope)
                .setTenantIdentityVersion(version)
                .setTenantId(tenantId)
                .setTenantUuid(tenantUuid)
                .build());
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatched receipt evidence");
  }

  private static Stream<Arguments> changedVersionOneIdentities() {
    return Stream.of(
        arguments("tenant", 0, "73", TENANT_UUID),
        arguments("tenant", 1, "73", TENANT_UUID),
        arguments("tenant", 2, "73", ""),
        arguments("tenant", 3, "73", ""),
        arguments("tenant", 1, "74", ""),
        arguments("tenant", 0, "073", ""),
        arguments("tenant", 1, "073", ""),
        arguments("tenant", 0, "", ""),
        arguments("platform", 0, "", TENANT_UUID),
        arguments("platform", 1, "", TENANT_UUID),
        arguments("platform", 2, "", ""),
        arguments("platform", 1, "73", ""),
        arguments("platform", 0, "73", ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"scope", "auditEventId", "schemaVersion", "payloadDigestVersion", "payloadDigest"})
  void matchingTypedIdentityDoesNotMaskOtherReceiptMismatches(String changedField)
      throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope = canonicalEnvelope();
    CreateLogEventResponse.Builder response = receipt(envelope);
    switch (changedField) {
      case "scope" -> response.setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM);
      case "auditEventId" -> response.setAuditEventId("22222222-2222-4222-8222-222222222222");
      case "schemaVersion" -> response.setSchemaVersion(envelope.schemaVersion() + 1);
      case "payloadDigestVersion" -> response.setPayloadDigestVersion(2);
      case "payloadDigest" -> response.setPayloadDigest(AccountAuditDigest.ofPayload("changed"));
      default -> throw new IllegalArgumentException(changedField);
    }
    when(stub.createLogEvent(any())).thenReturn(response.build());
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatched receipt evidence");
  }

  @ParameterizedTest
  @MethodSource("terminalReceipts")
  void exactTypedReceiptPreservesExistingTerminalSemantics(
      AccountAuditReceiptStatus status, AccountAuditReceiptOutcome outcome, boolean minimized)
      throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope = canonicalEnvelope();
    when(stub.createLogEvent(any()))
        .thenReturn(receipt(envelope).setStatus(status).setOutcome(outcome).build());

    assertThat(newClient(stub).deliver(envelope))
        .isEqualTo(new LoggingAdminClient.AuditDeliveryResult("receipt-2", "log-9", minimized));
  }

  private static Stream<Arguments> terminalReceipts() {
    return Stream.of(
        arguments(
            AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED,
            AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED,
            false),
        arguments(
            AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED,
            AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE,
            false),
        arguments(
            AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_MINIMIZED,
            AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_NON_REPLAYABLE,
            true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"status", "outcome", "receiptId", "logEventId"})
  void matchingTypedIdentityStillRequiresTerminalReceiptEvidence(String changedField)
      throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope = canonicalEnvelope();
    CreateLogEventResponse.Builder response = receipt(envelope);
    switch (changedField) {
      case "status" -> response.clearStatus();
      case "outcome" ->
          response.setOutcome(
              AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_NON_REPLAYABLE);
      case "receiptId" -> response.setReceiptId(" ");
      case "logEventId" -> response.clearLogEventId();
      default -> throw new IllegalArgumentException(changedField);
    }
    when(stub.createLogEvent(any())).thenReturn(response.build());
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not prove a terminal receipt");
  }

  @Test
  void matchingTypedReceiptCannotOverrideReceiverError() throws Exception {
    var stub = mockStub();
    AccountAuditEnvelope envelope = canonicalEnvelope();
    when(stub.createLogEvent(any()))
        .thenReturn(
            receipt(envelope)
                .setError(
                    ErrorDetail.newBuilder().setCode("UNAVAILABLE").setMessage("private detail"))
                .build());
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account audit receiver returned error code UNAVAILABLE");
  }

  @Test
  void ambiguousCreatePropagatesWithoutRetryOrReadback() throws Exception {
    var stub = mockStub();
    StatusRuntimeException failure = new StatusRuntimeException(Status.UNAVAILABLE);
    when(stub.createLogEvent(any())).thenThrow(failure);
    LoggingAdminClient client = newClient(stub);

    assertThatThrownBy(() -> client.deliver(canonicalEnvelope())).isSameAs(failure);

    capturedRequest(stub);
    verifyOnlyCreate(stub);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ambiguityReadsTheExactOriginalTupleOnceAndRequiresMatchingIdentity(boolean wrongTenant)
      throws Exception {
    var stub = mockStub();
    var envelope = canonicalEnvelope();
    when(stub.createLogEvent(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
    when(stub.readLogEventReceipt(any()))
        .thenReturn(
            ReadLogEventReceiptResponse.newBuilder()
                .setScope(AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
                .setTenantIdentityVersion(2)
                .setTenantUuid(wrongTenant ? OTHER_TENANT_UUID : TENANT_UUID)
                .setAuditEventId(envelope.auditEventId().toString())
                .setSchemaVersion(envelope.schemaVersion())
                .setPayloadDigestVersion(envelope.payloadDigestVersion())
                .setPayloadDigest(envelope.payloadDigest())
                .setReceiptId("receipt-2")
                .setLogEventId("log-9")
                .setStatus(AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED)
                .setOutcome(AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE)
                .build());
    var client = newClient(stub);
    if (wrongTenant) {
      assertThatThrownBy(() -> client.deliverOrRecover(envelope))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mismatched receipt evidence");
    } else {
      assertThat(client.deliverOrRecover(envelope))
          .isEqualTo(new LoggingAdminClient.AuditDeliveryResult("receipt-2", "log-9", false));
    }
    var original = capturedRequest(stub);
    var captor = ArgumentCaptor.forClass(ReadLogEventReceiptRequest.class);
    verify(stub).readLogEventReceipt(captor.capture());
    var read = captor.getValue();
    assertThat(read.getScope()).isEqualTo(original.getScope());
    assertThat(read.getTenantIdentityVersion()).isEqualTo(original.getTenantIdentityVersion());
    assertThat(read.getTenantId()).isEqualTo(original.getTenantId());
    assertThat(read.getTenantUuid()).isEqualTo(original.getTenantUuid());
    assertThat(read.getAuditEventId()).isEqualTo(original.getAuditEventId());
    assertThat(read.getProducerService()).isEqualTo(original.getProducerService());
    assertThat(read.getEventType()).isEqualTo(original.getEventType());
    assertThat(read.getOccurredAt()).isEqualTo(original.getOccurredAt());
    assertThat(read.getSchemaVersion()).isEqualTo(original.getSchemaVersion());
    assertThat(read.getPayloadDigestVersion()).isEqualTo(original.getPayloadDigestVersion());
    assertThat(read.getPayloadDigest()).isEqualTo(original.getPayloadDigest());
    assertThat(read.getPayload()).isEqualTo(original.getPayload());
    verify(stub, times(2)).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verifyNoMoreInteractions(stub);
  }

  @Test
  void definitiveDenialCannotTriggerReceiptRead() throws Exception {
    var stub = mockStub();
    var failure = Status.PERMISSION_DENIED.asRuntimeException();
    when(stub.createLogEvent(any())).thenThrow(failure);
    var client = newClient(stub);

    assertThatThrownBy(() -> client.deliverOrRecover(canonicalEnvelope())).isSameAs(failure);

    capturedRequest(stub);
    verifyOnlyCreate(stub);
  }

  private static AccountAuditEnvelope canonicalEnvelope() {
    return envelope("tenant", AccountAuditTenantIdentity.canonicalTenantV2(TENANT_UUID));
  }

  private static AccountAuditEnvelope envelope(String scope, AccountAuditTenantIdentity identity) {
    String payload = "{ \"join\": \"exact UTF-8 bytes: café\" }\n";
    return new AccountAuditEnvelope(
        EVENT_ID,
        scope,
        identity,
        "account-service",
        "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
        Instant.parse("2026-10-01T12:34:56.123456Z"),
        3,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }

  private static CreateLogEventResponse.Builder receipt(AccountAuditEnvelope envelope) {
    return CreateLogEventResponse.newBuilder()
        .setScope(
            "platform".equals(envelope.scope())
                ? AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM
                : AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT)
        .setTenantIdentityVersion(envelope.tenantIdentityVersion())
        .setTenantId(envelope.tenantId() == null ? "" : envelope.tenantId().toString())
        .setTenantUuid(envelope.tenantUuid() == null ? "" : envelope.tenantUuid().toString())
        .setAuditEventId(envelope.auditEventId().toString())
        .setReceiptId("receipt-2")
        .setLogEventId("log-9")
        .setSchemaVersion(envelope.schemaVersion())
        .setPayloadDigestVersion(envelope.payloadDigestVersion())
        .setPayloadDigest(envelope.payloadDigest())
        .setStatus(AccountAuditReceiptStatus.ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED)
        .setOutcome(AccountAuditReceiptOutcome.ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED);
  }

  private static CreateLogEventRequest capturedRequest(
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub) {
    ArgumentCaptor<CreateLogEventRequest> captor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    verify(stub).createLogEvent(captor.capture());
    return captor.getValue();
  }

  private static void verifyOnlyCreate(
      LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub) {
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verifyNoMoreInteractions(stub);
  }

  private static LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub mockStub() {
    var stub = mock(LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub.class);
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
}
