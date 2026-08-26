package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.ReportDto;
import net.firedevops.firemud.loggingadmin.service.ReportService;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReportGrpcServiceTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void createReportReturnsId() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    ReportService reportService = Mockito.mock(ReportService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportDto dto = new ReportDto(1L, 1L, 2L, 3L, "BUG", "bad", Instant.now());
    Mockito.when(reportService.createReport(Mockito.any())).thenReturn(dto);
    ReportGrpcService service = new ReportGrpcService(reportService, meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setTargetAccountId("3")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("1", ref.get().getReportId());
  }

  @Test
  void createReportValidationErrorReturnsErrorDetail() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    ReportService reportService = Mockito.mock(ReportService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    Mockito.when(reportService.createReport(Mockito.any()))
        .thenThrow(new IllegalArgumentException("bad"));
    ReportGrpcService service = new ReportGrpcService(reportService, meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setTargetAccountId("3")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void createReportRejectsZeroReporterAccountIdBeforeDispatch() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    ReportService reportService = Mockito.mock(ReportService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(reportService, meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("0")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("reporterAccountId must be positive", ref.get().getError().getMessage());
    verifyNoInteractions(reportService);
  }

  @Test
  void createReportRejectsZeroTargetAccountIdBeforeDispatch() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    ReportService reportService = Mockito.mock(ReportService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(reportService, meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setTargetAccountId("0")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("targetAccountId must be positive", ref.get().getError().getMessage());
    verifyNoInteractions(reportService);
  }

  @Test
  void createReportRejectsMissingCallerBeforePersistence() {
    SessionContext.clear();
    ReportService reportService = Mockito.mock(ReportService.class);
    ReportGrpcService service = new ReportGrpcService(reportService, new SimpleMeterRegistry());

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    verifyNoInteractions(reportService);
  }

  @Test
  void createReportRejectsAuthenticatedEndUserBeforePersistence() {
    SessionContext.setContext("42", List.of("player"), Map.of());
    ReportService reportService = Mockito.mock(ReportService.class);
    ReportGrpcService service = new ReportGrpcService(reportService, new SimpleMeterRegistry());

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    verifyNoInteractions(reportService);
  }

  @Test
  void createReportRejectsWrongInternalServiceBeforePersistence() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "game-session-service", "test-instance");
    ReportService reportService = Mockito.mock(ReportService.class);
    ReportGrpcService service = new ReportGrpcService(reportService, new SimpleMeterRegistry());

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    verifyNoInteractions(reportService);
  }
}
