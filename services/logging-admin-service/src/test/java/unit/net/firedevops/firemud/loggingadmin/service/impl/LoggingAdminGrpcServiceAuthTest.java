package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
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
}
