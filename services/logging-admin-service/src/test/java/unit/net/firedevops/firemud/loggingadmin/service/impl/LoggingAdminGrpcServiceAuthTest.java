package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventResponse;
import net.firedevops.firemud.loggingadmin.v1.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.v1.QueryLogsResponse;
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
}
