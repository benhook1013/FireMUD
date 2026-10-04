package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import net.firedevops.firemud.loggingadmin.v1.ReportServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class ReportGrpcService extends ReportServiceGrpc.ReportServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(ReportGrpcService.class);
  private static final String SOCIAL_GROUPS_SERVICE = "social-groups-service";
  private static final String REPORT_CREATE_UNAVAILABLE_MESSAGE =
      "Report creation is unavailable until the shared mutation gate is implemented";

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects the thread-safe MeterRegistry used for metrics only.")
  private final MeterRegistry meterRegistry;

  public ReportGrpcService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "loggingadminGrpc.createReport")
  public void createReport(
      CreateReportRequest request, StreamObserver<CreateReportResponse> responseObserver) {
    try {
      requireSocialGroupsInternalService();
      RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId");
      RequestIdValidation.requirePositiveLong(request.getReporterAccountId(), "reporterAccountId");
      RequestIdValidation.parseOptionalPositiveLong(
          request.getTargetAccountId(), "targetAccountId");
      CreateReportResponse response =
          CreateReportResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "CreateReport",
                      "UNAVAILABLE",
                      REPORT_CREATE_UNAVAILABLE_MESSAGE))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      CreateReportResponse response =
          CreateReportResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "CreateReport", "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreateReportResponse response =
          CreateReportResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "CreateReport", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreateReportResponse response =
          CreateReportResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "CreateReport", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private static void requireSocialGroupsInternalService() {
    if (!SessionContext.isInternalService()
        || !SOCIAL_GROUPS_SERVICE.equals(SessionContext.getServiceName())) {
      throw new AdminAuthorizationException(
          "CreateReport requires social-groups-service as an internal service caller");
    }
  }
}
