package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.config.AccountAuditGrpcAuthConfiguration;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptDto;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.dto.ModerationPolicyDecisionDto;
import net.firedevops.firemud.loggingadmin.service.AuditReceiptNotFoundException;
import net.firedevops.firemud.loggingadmin.service.AuditStorageUnavailableException;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.loggingadmin.v1.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.v1.ApplyModerationActionResponse;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventResponse;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyRequest;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse;
import net.firedevops.firemud.loggingadmin.v1.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.v1.QueryLogsResponse;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptResponse;
import net.firedevops.firemud.loggingadmin.v1.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.v1.ToggleFeatureFlagResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoggingAdminGrpcServiceAuthTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void auditMethodsBypassJwtRequirementButOtherMethodsRemainProtected() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withUserConfiguration(AccountAuditGrpcAuthConfiguration.class)
        .withPropertyValues(
            "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
            "firemud.auth.grpc.public-methods[0]=logging_admin.v1.LoggingAdminService/Ping")
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuthTokenInterceptor.class);
              GrpcAuthProperties properties = context.getBean(GrpcAuthProperties.class);
              assertThat(properties.getPublicMethods())
                  .containsExactly("logging_admin.v1.LoggingAdminService/Ping");
              AuthTokenInterceptor interceptor = context.getBean(AuthTokenInterceptor.class);
              assertTrue(
                  passesWithoutBearer(
                      interceptor, "logging_admin.v1.LoggingAdminService/CreateLogEvent"));
              assertTrue(
                  passesWithoutBearer(
                      interceptor, "logging_admin.v1.LoggingAdminService/ReadLogEventReceipt"));
              assertTrue(
                  passesWithoutBearer(interceptor, "logging_admin.v1.LoggingAdminService/Ping"));
              assertFalse(
                  passesWithoutBearer(
                      interceptor, "logging_admin.v1.LoggingAdminService/QueryLogs"));
            });
  }

  @Test
  void adminMethodsReturnPermissionDeniedErrorDetail() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            Mockito.mock(ModerationService.class),
            new SimpleMeterRegistry());

    AtomicReference<QueryLogsResponse> ref = new AtomicReference<>();
    service.queryLogs(
        QueryLogsRequest.newBuilder().setTenantId("1").setFilter("all").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryLogsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    assertEquals("Admin role required", ref.get().getError().getMessage());
  }

  @Test
  void accountMtlSPeerCreatesReceiptWithoutJwtOrAdminRole() {
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    String digest = digest("{\"accountId\":42}".getBytes(StandardCharsets.UTF_8));
    when(logEventService.createLogEvent(any()))
        .thenReturn(
            new AccountAuditReceiptDto(
                AccountAuditScope.PLATFORM,
                null,
                "d2719d4f-3b2a-4f64-a994-0f9ccdfdd2b3",
                "receipt-1",
                77L,
                1,
                1,
                digest,
                AccountAuditReceiptStatus.COMMITTED,
                AccountAuditReceiptOutcome.ACCEPTED));
    LoggingAdminGrpcService service = newService(logEventService);

    AtomicReference<CreateLogEventResponse> ref = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    invokeWithPeer(
        "account-service",
        () -> service.createLogEvent(validCreateRequest(), responseObserver(ref, error)));

    assertNotNull(ref.get());
    assertEquals("77", ref.get().getLogEventId());
    assertEquals(
        net.firedevops.firemud.loggingadmin.v1.AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM,
        ref.get().getScope());
    assertEquals(
        net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus
            .ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED,
        ref.get().getStatus());
    assertEquals(
        net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome
            .ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED,
        ref.get().getOutcome());
    assertNull(error.get());
    verify(logEventService).createLogEvent(any());
    verify(moderationService, never()).applyAction(any());
  }

  @Test
  void adminJwtClaimsDoNotAuthorizeAuditIngressWithoutAccountPeer() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    LoggingAdminGrpcService service = newService(logEventService);
    AtomicReference<CreateLogEventResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    service.createLogEvent(validCreateRequest(), responseObserver(response, error));

    assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error.get()).getCode());
    verifyNoInteractions(logEventService);
  }

  @Test
  void jwtClaimNamingAccountServiceCannotOverrideWrongMtlsPeer() {
    SessionContext.setContext(null, List.of(), Map.of(), true, "account-service", "instance-1");
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    LoggingAdminGrpcService service = newService(logEventService);
    AtomicReference<CreateLogEventResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    invokeWithPeer(
        "game-session-service",
        () -> service.createLogEvent(validCreateRequest(), responseObserver(response, error)));

    assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error.get()).getCode());
    verifyNoInteractions(logEventService);
  }

  @Test
  void accountAuditMethodsRejectAccountPeerFromWrongNamespace() {
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    LoggingAdminGrpcService service = newService(logEventService);
    AtomicReference<CreateLogEventResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    invokeWithPeerUri(
        "spiffe://firemud/ns/other/sa/account-service",
        () -> service.createLogEvent(validCreateRequest(), responseObserver(response, error)));

    assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error.get()).getCode());
    verifyNoInteractions(logEventService);
  }

  @Test
  void receiptReadReturnsCanonicalNotFoundStatus() {
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    when(logEventService.readLogEventReceipt(any())).thenThrow(new AuditReceiptNotFoundException());
    LoggingAdminGrpcService service = newService(logEventService);
    AtomicReference<ReadLogEventReceiptResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    invokeWithPeer(
        "account-service",
        () -> service.readLogEventReceipt(validReadRequest(), responseObserver(response, error)));

    assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(error.get()).getCode());
    assertNull(response.get());
  }

  @Test
  void accountMtlSPeerCanReadTypedReceiptWithoutJwt() {
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    String digest = digest("{\"accountId\":42}".getBytes(StandardCharsets.UTF_8));
    when(logEventService.readLogEventReceipt(any()))
        .thenReturn(
            new AccountAuditReceiptDto(
                AccountAuditScope.PLATFORM,
                null,
                "d2719d4f-3b2a-4f64-a994-0f9ccdfdd2b3",
                "receipt-1",
                77L,
                1,
                1,
                digest,
                AccountAuditReceiptStatus.COMMITTED,
                AccountAuditReceiptOutcome.DUPLICATE));
    LoggingAdminGrpcService service = newService(logEventService);
    AtomicReference<ReadLogEventReceiptResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    invokeWithPeer(
        "account-service",
        () -> service.readLogEventReceipt(validReadRequest(), responseObserver(response, error)));

    assertEquals(
        net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome
            .ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE,
        response.get().getOutcome());
    assertNull(error.get());
    verify(logEventService).readLogEventReceipt(any());
  }

  @Test
  void auditStorageFailureReturnsCanonicalUnavailableStatus() {
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    when(logEventService.createLogEvent(any()))
        .thenThrow(new AuditStorageUnavailableException(new IllegalStateException("database")));
    LoggingAdminGrpcService service = newService(logEventService);
    AtomicReference<CreateLogEventResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();

    invokeWithPeer(
        "account-service",
        () -> service.createLogEvent(validCreateRequest(), responseObserver(response, error)));

    assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(error.get()).getCode());
    assertNull(response.get());
  }

  @Test
  void toggleFeatureFlagRejectsZeroTenantIdBeforeDispatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());

    ToggleFeatureFlagResponse response = invokeToggleFeatureFlag("0", "demo");

    assertNotNull(response);
    assertFalse(response.getSuccess());
    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("tenantId must be positive", response.getError().getMessage());
  }

  @Test
  void toggleFeatureFlagRejectsAuthorizedCallerWhileMutationGateIsUnavailable() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());

    ToggleFeatureFlagResponse response = invokeToggleFeatureFlag("1", "demo");

    assertNotNull(response);
    assertFalse(response.getSuccess());
    assertEquals("UNAVAILABLE", response.getError().getCode());
    assertEquals(
        "Feature-flag toggles are unavailable until the shared mutation gate is implemented",
        response.getError().getMessage());
  }

  @Test
  void toggleFeatureFlagRejectsUnauthorizedCallerBeforeUnavailableResponse() {
    SessionContext.setContext("1", List.of("player"), Map.of());

    ToggleFeatureFlagResponse response = invokeToggleFeatureFlag("1", "demo");

    assertNotNull(response);
    assertFalse(response.getSuccess());
    assertEquals("PERMISSION_DENIED", response.getError().getCode());
  }

  @Test
  void toggleFeatureFlagRejectsEmptyNameBeforeUnavailableResponse() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());

    ToggleFeatureFlagResponse response = invokeToggleFeatureFlag("1", "");

    assertNotNull(response);
    assertFalse(response.getSuccess());
    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("name must not be blank", response.getError().getMessage());
  }

  @Test
  void toggleFeatureFlagRejectsBlankNameBeforeUnavailableResponse() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());

    ToggleFeatureFlagResponse response = invokeToggleFeatureFlag("1", "   ");

    assertNotNull(response);
    assertFalse(response.getSuccess());
    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("name must not be blank", response.getError().getMessage());
  }

  @Test
  void toggleFeatureFlagRejectsOverlongNameBeforeUnavailableResponse() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());

    ToggleFeatureFlagResponse response = invokeToggleFeatureFlag("1", "x".repeat(101));

    assertNotNull(response);
    assertFalse(response.getSuccess());
    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("name size must be between 1 and 100", response.getError().getMessage());
  }

  private ToggleFeatureFlagResponse invokeToggleFeatureFlag(String tenantId, String name) {
    LogQueryService logQueryService = Mockito.mock(LogQueryService.class);
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            logQueryService, logEventService, moderationService, new SimpleMeterRegistry());
    AtomicReference<ToggleFeatureFlagResponse> ref = new AtomicReference<>();

    service.toggleFeatureFlag(
        ToggleFeatureFlagRequest.newBuilder()
            .setTenantId(tenantId)
            .setName(name)
            .setEnabled(true)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ToggleFeatureFlagResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    verifyNoInteractions(logQueryService, logEventService, moderationService);
    return ref.get();
  }

  @Test
  void queryLogsRejectsZeroTenantIdBeforeDispatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    LogQueryService logQueryService = Mockito.mock(LogQueryService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            logQueryService,
            Mockito.mock(LogEventService.class),
            Mockito.mock(ModerationService.class),
            new SimpleMeterRegistry());

    AtomicReference<QueryLogsResponse> ref = new AtomicReference<>();
    service.queryLogs(
        QueryLogsRequest.newBuilder().setTenantId("0").setFilter("all").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryLogsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    verifyNoInteractions(logQueryService);
  }

  @Test
  void createLogEventMapsUnsupportedDigestVersionRejectionToInvalidArgument() {
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    when(logEventService.createLogEvent(any()))
        .thenThrow(new IllegalArgumentException("payloadDigestVersion must be 1"));
    LoggingAdminGrpcService service = newService(logEventService);

    AtomicReference<CreateLogEventResponse> ref = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    CreateLogEventRequest request =
        validCreateRequest().toBuilder().setPayloadDigestVersion(2).build();
    invokeWithPeer(
        "account-service", () -> service.createLogEvent(request, responseObserver(ref, error)));

    assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(error.get()).getCode());
    assertNull(ref.get());
    verify(logEventService).createLogEvent(argThat(dto -> dto.payloadDigestVersion() == 2));
  }

  @Test
  void applyModerationActionRejectsZeroSessionIdBeforeDispatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<ApplyModerationActionResponse> ref = new AtomicReference<>();
    service.applyModerationAction(
        ApplyModerationActionRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setSessionId("0")
            .setAction("ban")
            .setReason("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ApplyModerationActionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("sessionId must be positive", ref.get().getError().getMessage());
    verifyNoInteractions(moderationService);
  }

  @Test
  void applyModerationActionRejectsAuthorizedCallerWhileMutationGateIsUnavailable() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<ApplyModerationActionResponse> ref = new AtomicReference<>();
    service.applyModerationAction(
        ApplyModerationActionRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setSessionId("9")
            .setAction("ban")
            .setReason("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ApplyModerationActionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("UNAVAILABLE", ref.get().getError().getCode());
    assertEquals(
        "Moderation actions are unavailable until the shared mutation gate is implemented",
        ref.get().getError().getMessage());
    verifyNoInteractions(moderationService);
  }

  @Test
  void evaluateModerationPolicyRejectsZeroAccountIdBeforeDispatch() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "game-session-service", "test-instance");
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<EvaluateModerationPolicyResponse> ref = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("0")
            .setScope("chat")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(EvaluateModerationPolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getAllowed());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    verifyNoInteractions(moderationService);
  }

  @Test
  void evaluateModerationPolicyAllowsAllowlistedInternalService() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "game-session-service", "test-instance");
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    when(moderationService.evaluatePolicy(1L, 2L, "CHAT_SEND"))
        .thenReturn(new ModerationPolicyDecisionDto(true, "", "allowed", null));
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<EvaluateModerationPolicyResponse> ref = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setScope("CHAT_SEND")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(EvaluateModerationPolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().hasError());
    assertEquals(true, ref.get().getAllowed());
    verify(moderationService).evaluatePolicy(1L, 2L, "CHAT_SEND");
  }

  @Test
  void evaluateModerationPolicyAllowsSocialGroupsInternalService() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    when(moderationService.evaluatePolicy(1L, 2L, "CHAT_SEND"))
        .thenReturn(new ModerationPolicyDecisionDto(true, "", "allowed", null));
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<EvaluateModerationPolicyResponse> ref = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setScope("CHAT_SEND")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(EvaluateModerationPolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().hasError());
    assertEquals(true, ref.get().getAllowed());
    verify(moderationService).evaluatePolicy(1L, 2L, "CHAT_SEND");
  }

  @Test
  void evaluateModerationPolicyRejectsMissingCallerBeforeDispatch() {
    SessionContext.clear();
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<EvaluateModerationPolicyResponse> ref = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setScope("CHAT_SEND")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(EvaluateModerationPolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    verifyNoInteractions(moderationService);
  }

  @Test
  void evaluateModerationPolicyRejectsAuthenticatedEndUserBeforeDispatch() {
    SessionContext.setContext("42", List.of("player"), Map.of());
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<EvaluateModerationPolicyResponse> ref = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setScope("CHAT_SEND")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(EvaluateModerationPolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    verifyNoInteractions(moderationService);
  }

  @Test
  void evaluateModerationPolicyRejectsNonAllowlistedInternalServiceBeforeDispatch() {
    SessionContext.setContext("", List.of(), Map.of(), true, "account-service", "test-instance");
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<EvaluateModerationPolicyResponse> ref = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setScope("CHAT_SEND")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(EvaluateModerationPolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    verifyNoInteractions(moderationService);
  }

  @Test
  void evaluateModerationPolicyRejectsInternalCallerWithMissingServiceNameBeforeDispatch() {
    SessionContext.setContext("", List.of(), Map.of(), true, null, "test-instance");
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<EvaluateModerationPolicyResponse> ref = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setScope("CHAT_SEND")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(EvaluateModerationPolicyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    verifyNoInteractions(moderationService);
  }

  private static LoggingAdminGrpcService newService(LogEventService logEventService) {
    return new LoggingAdminGrpcService(
        Mockito.mock(LogQueryService.class),
        logEventService,
        Mockito.mock(ModerationService.class),
        new SimpleMeterRegistry(),
        "firemud");
  }

  private static boolean passesWithoutBearer(
      AuthTokenInterceptor interceptor, String fullMethodName) {
    MethodDescriptor<CreateLogEventRequest, CreateLogEventResponse> descriptor =
        MethodDescriptor.<CreateLogEventRequest, CreateLogEventResponse>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(fullMethodName)
            .setRequestMarshaller(ProtoUtils.marshaller(CreateLogEventRequest.getDefaultInstance()))
            .setResponseMarshaller(
                ProtoUtils.marshaller(CreateLogEventResponse.getDefaultInstance()))
            .build();
    @SuppressWarnings("unchecked")
    ServerCall<CreateLogEventRequest, CreateLogEventResponse> serverCall =
        (ServerCall<CreateLogEventRequest, CreateLogEventResponse>) Mockito.mock(ServerCall.class);
    when(serverCall.getMethodDescriptor()).thenReturn(descriptor);
    AtomicReference<Boolean> callStarted = new AtomicReference<>(false);
    ServerCallHandler<CreateLogEventRequest, CreateLogEventResponse> next =
        (call, headers) -> {
          callStarted.set(true);
          return new ServerCall.Listener<>() {};
        };

    interceptor.interceptCall(serverCall, new Metadata(), next);
    return callStarted.get();
  }

  private static <T> StreamObserver<T> responseObserver(
      AtomicReference<T> response, AtomicReference<Throwable> error) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        response.set(value);
      }

      @Override
      public void onError(Throwable value) {
        error.set(value);
      }

      @Override
      public void onCompleted() {}
    };
  }

  private static void invokeWithPeer(String serviceName, Runnable invocation) {
    String uri = "spiffe://firemud/ns/firemud/sa/" + serviceName;
    invokeWithPeerUri(uri, invocation);
  }

  private static void invokeWithPeerUri(String uri, Runnable invocation) {
    Context context =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
    Context previous = context.attach();
    try {
      invocation.run();
    } finally {
      context.detach(previous);
    }
  }

  private static CreateLogEventRequest validCreateRequest() {
    ByteString payload = ByteString.copyFrom("{\"accountId\":42}".getBytes(StandardCharsets.UTF_8));
    return CreateLogEventRequest.newBuilder()
        .setScope(
            net.firedevops.firemud.loggingadmin.v1.AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM)
        .setAuditEventId("d2719d4f-3b2a-4f64-a994-0f9ccdfdd2b3")
        .setProducerService("account-service")
        .setEventType("ACCOUNT_REGISTERED")
        .setOccurredAt(Timestamp.newBuilder().setSeconds(1).setNanos(234567890))
        .setSchemaVersion(1)
        .setPayload(payload)
        .setPayloadDigestVersion(1)
        .setPayloadDigest(digest(payload.toByteArray()))
        .build();
  }

  private static ReadLogEventReceiptRequest validReadRequest() {
    CreateLogEventRequest create = validCreateRequest();
    return ReadLogEventReceiptRequest.newBuilder()
        .setScope(create.getScope())
        .setAuditEventId(create.getAuditEventId())
        .setProducerService(create.getProducerService())
        .setEventType(create.getEventType())
        .setOccurredAt(create.getOccurredAt())
        .setSchemaVersion(create.getSchemaVersion())
        .setPayload(create.getPayload())
        .setPayloadDigestVersion(create.getPayloadDigestVersion())
        .setPayloadDigest(create.getPayloadDigest())
        .build();
  }

  private static String digest(byte[] payload) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
