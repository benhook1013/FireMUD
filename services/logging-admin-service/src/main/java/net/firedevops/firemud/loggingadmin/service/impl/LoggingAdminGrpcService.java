package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.common.security.RequestIdValidation;
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
  private static final String MODERATION_ACTION_UNAVAILABLE_MESSAGE =
      "Moderation actions are unavailable until the shared mutation gate is implemented";
  private static final String FEATURE_FLAG_TOGGLE_UNAVAILABLE_MESSAGE =
      "Feature-flag toggles are unavailable until the shared mutation gate is implemented";

  private final LogQueryService logQueryService;
  private final LogEventService logEventService;
  private final ModerationService moderationService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  public LoggingAdminGrpcService(
      LogQueryService logQueryService,
      LogEventService logEventService,
      ModerationService moderationService,
      MeterRegistry meterRegistry) {
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
  public void toggleFeatureFlag(
      ToggleFeatureFlagRequest request,
      StreamObserver<ToggleFeatureFlagResponse> responseObserver) {
    try {
      AdminRoleGuard.requireAdminRole();
      RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId");
      validateFeatureFlagName(request.getName());
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ToggleFeatureFlag",
                      "UNAVAILABLE",
                      FEATURE_FLAG_TOGGLE_UNAVAILABLE_MESSAGE))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ToggleFeatureFlag",
                      "PERMISSION_DENIED",
                      ex.getMessage()))
              .build();
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

  private void validateFeatureFlagName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (name.length() > 100) {
      throw new IllegalArgumentException("name size must be between 0 and 100");
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.queryLogs")
  public void queryLogs(
      QueryLogsRequest request, StreamObserver<QueryLogsResponse> responseObserver) {
    try {
      AdminRoleGuard.requireAdminRole();
      List<String> entries =
          logQueryService.queryLogs(
              new net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest(
                  RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
                  request.getFilter()));
      QueryLogsResponse response = QueryLogsResponse.newBuilder().addAllEntries(entries).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      QueryLogsResponse response =
          QueryLogsResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "QueryLogs", "PERMISSION_DENIED", ex.getMessage()))
              .build();
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
  public void createLogEvent(
      CreateLogEventRequest request, StreamObserver<CreateLogEventResponse> responseObserver) {
    try {
      AdminRoleGuard.requireAdminRole();
      var dto =
          logEventService.createLogEvent(
              new net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest(
                  RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
                  RequestIdValidation.parseOptionalPositiveLong(
                      request.getAccountId(), "accountId"),
                  request.getType(),
                  request.getMessage()));
      CreateLogEventResponse response =
          CreateLogEventResponse.newBuilder().setLogEventId(String.valueOf(dto.id())).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      CreateLogEventResponse response =
          CreateLogEventResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "CreateLogEvent",
                      "PERMISSION_DENIED",
                      ex.getMessage()))
              .build();
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
  public void applyModerationAction(
      ApplyModerationActionRequest request,
      StreamObserver<ApplyModerationActionResponse> responseObserver) {
    try {
      AdminRoleGuard.requireAdminRole();
      RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId");
      RequestIdValidation.requirePositiveLong(request.getAccountId(), "accountId");
      RequestIdValidation.requirePositiveLong(request.getSessionId(), "sessionId");
      ApplyModerationActionResponse response =
          ApplyModerationActionResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ApplyModerationAction",
                      "UNAVAILABLE",
                      MODERATION_ACTION_UNAVAILABLE_MESSAGE))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ApplyModerationActionResponse response =
          ApplyModerationActionResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ApplyModerationAction",
                      "PERMISSION_DENIED",
                      ex.getMessage()))
              .build();
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

  @Override
  @Timed(value = "loggingadminGrpc.evaluateModerationPolicy")
  public void evaluateModerationPolicy(
      EvaluateModerationPolicyRequest request,
      StreamObserver<EvaluateModerationPolicyResponse> responseObserver) {
    try {
      var decision =
          moderationService.evaluatePolicy(
              RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
              RequestIdValidation.requirePositiveLong(request.getAccountId(), "accountId"),
              request.getScope());
      EvaluateModerationPolicyResponse.Builder response =
          EvaluateModerationPolicyResponse.newBuilder()
              .setAllowed(decision.allowed())
              .setAction(decision.action() == null ? "" : decision.action())
              .setReason(decision.reason() == null ? "" : decision.reason());
      if (decision.expiresAt() != null) {
        response.setExpiresAtEpochSeconds(decision.expiresAt().getEpochSecond());
      }
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      EvaluateModerationPolicyResponse response =
          EvaluateModerationPolicyResponse.newBuilder()
              .setAllowed(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "EvaluateModerationPolicy",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      EvaluateModerationPolicyResponse response =
          EvaluateModerationPolicyResponse.newBuilder()
              .setAllowed(false)
              .setError(
                  GrpcAppErrors.internal(meterRegistry, logger, "EvaluateModerationPolicy", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
