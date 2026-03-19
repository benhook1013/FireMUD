package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.loggingadmin.v1.*;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class LoggingAdminGrpcService extends LoggingAdminServiceGrpc.LoggingAdminServiceImplBase {

  private final FeatureFlagService featureFlagService;
  private final LogQueryService logQueryService;
  private final ModerationService moderationService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  public LoggingAdminGrpcService(
      FeatureFlagService featureFlagService,
      LogQueryService logQueryService,
      ModerationService moderationService,
      MeterRegistry meterRegistry) {
    this.featureFlagService = featureFlagService;
    this.logQueryService = logQueryService;
    this.moderationService = moderationService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "loggingadminGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    try {
      PingResponse response = PingResponse.newBuilder().setMessage("pong").build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.toggleFeatureFlag")
  public void toggleFeatureFlag(
      ToggleFeatureFlagRequest request,
      StreamObserver<ToggleFeatureFlagResponse> responseObserver) {
    try {
      featureFlagService.toggleFlag(
          new net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest(
              Long.valueOf(request.getTenantId()), request.getName(), request.getEnabled()));
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.queryLogs")
  public void queryLogs(
      QueryLogsRequest request, StreamObserver<QueryLogsResponse> responseObserver) {
    try {
      List<String> entries =
          logQueryService.queryLogs(
              new net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest(
                  Long.valueOf(request.getTenantId()), request.getFilter()));
      QueryLogsResponse response = QueryLogsResponse.newBuilder().addAllEntries(entries).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      QueryLogsResponse response =
          QueryLogsResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.applyModerationAction")
  public void applyModerationAction(
      ApplyModerationActionRequest request,
      StreamObserver<ApplyModerationActionResponse> responseObserver) {
    try {
      moderationService.applyAction(
          new net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getAction(),
              request.getReason()));
      ApplyModerationActionResponse response =
          ApplyModerationActionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ApplyModerationActionResponse response =
          ApplyModerationActionResponse.newBuilder()
              .setSuccess(false)
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
