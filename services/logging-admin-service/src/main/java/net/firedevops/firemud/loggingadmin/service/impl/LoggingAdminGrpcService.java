package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.RequireAdminRole;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.loggingadmin.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class LoggingAdminGrpcService extends LoggingAdminServiceGrpc.LoggingAdminServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(LoggingAdminGrpcService.class);

  private final FeatureFlagService featureFlagService;
  private final LogQueryService logQueryService;
  private final LogEventService logEventService;
  private final ModerationService moderationService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  public LoggingAdminGrpcService(
      FeatureFlagService featureFlagService,
      LogQueryService logQueryService,
      LogEventService logEventService,
      ModerationService moderationService,
      MeterRegistry meterRegistry) {
    this.featureFlagService = featureFlagService;
    this.logQueryService = logQueryService;
    this.logEventService = logEventService;
    this.moderationService = moderationService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "loggingadminGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    PingResponse response = PingResponse.newBuilder().setMessage("pong").build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "loggingadminGrpc.toggleFeatureFlag")
  @RequireAdminRole
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
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ToggleFeatureFlag",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ToggleFeatureFlag", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.queryLogs")
  @RequireAdminRole
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
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "QueryLogs", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      QueryLogsResponse response =
          QueryLogsResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "QueryLogs", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.createLogEvent")
  @RequireAdminRole
  public void createLogEvent(
      CreateLogEventRequest request, StreamObserver<CreateLogEventResponse> responseObserver) {
    try {
      var dto =
          logEventService.createLogEvent(
              new net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest(
                  Long.valueOf(request.getTenantId()),
                  request.getAccountId().isBlank() ? null : Long.valueOf(request.getAccountId()),
                  request.getType(),
                  request.getMessage()));
      CreateLogEventResponse response =
          CreateLogEventResponse.newBuilder().setLogEventId(String.valueOf(dto.id())).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreateLogEventResponse response =
          CreateLogEventResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "CreateLogEvent", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreateLogEventResponse response =
          CreateLogEventResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "CreateLogEvent", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.applyModerationAction")
  @RequireAdminRole
  public void applyModerationAction(
      ApplyModerationActionRequest request,
      StreamObserver<ApplyModerationActionResponse> responseObserver) {
    try {
      moderationService.applyAction(
          new net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              Long.valueOf(request.getSessionId()),
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
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ApplyModerationAction",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ApplyModerationActionResponse response =
          ApplyModerationActionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ApplyModerationAction", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
