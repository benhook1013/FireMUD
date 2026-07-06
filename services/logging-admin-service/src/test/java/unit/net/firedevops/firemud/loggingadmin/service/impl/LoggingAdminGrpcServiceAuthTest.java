package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.LogEventDto;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
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
import net.firedevops.firemud.loggingadmin.v1.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.v1.ToggleFeatureFlagResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LoggingAdminGrpcServiceAuthTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void adminMethodsReturnPermissionDeniedErrorDetail() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(FeatureFlagService.class),
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
  void createLogEventUsesLogEventServiceWithoutCallingModeration() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    when(logEventService.createLogEvent(any()))
        .thenReturn(
            new LogEventDto(
                77L,
                1L,
                42L,
                "ACCOUNT_CREATED",
                "account created",
                Instant.parse("2026-01-01T00:00:00Z")));
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(FeatureFlagService.class),
            Mockito.mock(LogQueryService.class),
            logEventService,
            moderationService,
            new SimpleMeterRegistry());

    AtomicReference<CreateLogEventResponse> ref = new AtomicReference<>();
    service.createLogEvent(
        CreateLogEventRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("42")
            .setType("ACCOUNT_CREATED")
            .setMessage("account created")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateLogEventResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("77", ref.get().getLogEventId());
    verify(logEventService).createLogEvent(any());
    verify(moderationService, never()).applyAction(any());
  }

  @Test
  void toggleFeatureFlagRejectsZeroTenantIdBeforeDispatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            featureFlagService,
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            Mockito.mock(ModerationService.class),
            new SimpleMeterRegistry());

    AtomicReference<ToggleFeatureFlagResponse> ref = new AtomicReference<>();
    service.toggleFeatureFlag(
        ToggleFeatureFlagRequest.newBuilder()
            .setTenantId("0")
            .setName("demo")
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

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    verifyNoInteractions(featureFlagService);
  }

  @Test
  void queryLogsRejectsZeroTenantIdBeforeDispatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    LogQueryService logQueryService = Mockito.mock(LogQueryService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(FeatureFlagService.class),
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
  void createLogEventRejectsZeroAccountIdBeforeDispatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(FeatureFlagService.class),
            Mockito.mock(LogQueryService.class),
            logEventService,
            Mockito.mock(ModerationService.class),
            new SimpleMeterRegistry());

    AtomicReference<CreateLogEventResponse> ref = new AtomicReference<>();
    service.createLogEvent(
        CreateLogEventRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("0")
            .setType("ACCOUNT_CREATED")
            .setMessage("account created")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateLogEventResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    verifyNoInteractions(logEventService);
  }

  @Test
  void applyModerationActionRejectsZeroSessionIdBeforeDispatch() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(FeatureFlagService.class),
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
  void evaluateModerationPolicyRejectsZeroAccountIdBeforeDispatch() {
    ModerationService moderationService = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(FeatureFlagService.class),
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
}
