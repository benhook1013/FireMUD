package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.loggingadmin.dto.ReportDto;
import net.firedevops.firemud.loggingadmin.service.ReportService;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import net.firedevops.firemud.loggingadmin.v1.ReportServiceGrpc;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class ReportGrpcService extends ReportServiceGrpc.ReportServiceImplBase {

  private final ReportService reportService;
  private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects ReportService and MeterRegistry beans")
  public ReportGrpcService(ReportService reportService, MeterRegistry meterRegistry) {
    this.reportService = reportService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "loggingadminGrpc.createReport")
  public void createReport(
      CreateReportRequest request, StreamObserver<CreateReportResponse> responseObserver) {
    try {
      ReportDto dto =
          reportService.createReport(
              new net.firedevops.firemud.loggingadmin.dto.CreateReportRequest(
                  Long.valueOf(request.getTenantId()),
                  Long.valueOf(request.getReporterAccountId()),
                  request.getTargetAccountId().isEmpty()
                      ? null
                      : Long.valueOf(request.getTargetAccountId()),
                  request.getType(),
                  request.getDescription()));
      CreateReportResponse response =
          CreateReportResponse.newBuilder().setReportId(dto.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreateReportResponse response =
          CreateReportResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }
}
