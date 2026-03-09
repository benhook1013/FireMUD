package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.loggingadmin.dto.ReportDto;
import net.firedevops.firemud.loggingadmin.service.ReportService;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReportGrpcServiceTest {
  @Test
  void createReportReturnsId() {
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
}
