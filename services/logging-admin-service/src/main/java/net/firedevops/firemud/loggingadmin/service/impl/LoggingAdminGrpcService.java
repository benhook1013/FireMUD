package net.firedevops.firemud.loggingadmin.service.impl;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptDto;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.service.AuditReceiptNotFoundException;
import net.firedevops.firemud.loggingadmin.service.AuditStorageUnavailableException;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.loggingadmin.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.TransactionException;

@GrpcService
public class LoggingAdminGrpcService extends LoggingAdminServiceGrpc.LoggingAdminServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(LoggingAdminGrpcService.class);
  private static final String MODERATION_ACTION_UNAVAILABLE_MESSAGE =
      "Moderation actions are unavailable until the shared mutation gate is implemented";
  private static final String FEATURE_FLAG_TOGGLE_UNAVAILABLE_MESSAGE =
      "Feature-flag toggles are unavailable until the shared mutation gate is implemented";
  private static final Set<String> MODERATION_POLICY_CALLERS =
      Set.of("game-session-service", "social-groups-service");
  private static final String ACCOUNT_SERVICE = "account-service";
  private static final Map<String, Set<String>> ACCOUNT_AUDIT_CALLER_ALLOWLIST =
      Map.of(
          "CreateLogEvent", Set.of(ACCOUNT_SERVICE),
          "ReadLogEventReceipt", Set.of(ACCOUNT_SERVICE));

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
      throw new IllegalArgumentException("name size must be between 1 and 100");
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
      requireAccountAuditCaller("CreateLogEvent");
      AccountAuditReceiptDto receipt = logEventService.createLogEvent(toAuditRequest(request));
      CreateLogEventResponse response = toCreateLogEventResponse(receipt);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onError(
          Status.PERMISSION_DENIED.withDescription(ex.getMessage()).asRuntimeException());
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    } catch (AuditStorageUnavailableException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(ex.getMessage()).asRuntimeException());
    } catch (DataAccessException | TransactionException ex) {
      logger.warn("CreateLogEvent transaction is unavailable: {}", ex.getClass().getSimpleName());
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Account audit ingress storage is unavailable")
              .asRuntimeException());
    } catch (Exception ex) {
      logger.error("CreateLogEvent failed before producing an audit receipt", ex);
      responseObserver.onError(
          Status.INTERNAL.withDescription("Account audit ingress failed").asRuntimeException());
    }
  }

  @Override
  @Timed(value = "loggingadminGrpc.readLogEventReceipt")
  public void readLogEventReceipt(
      ReadLogEventReceiptRequest request,
      StreamObserver<ReadLogEventReceiptResponse> responseObserver) {
    try {
      requireAccountAuditCaller("ReadLogEventReceipt");
      AccountAuditReceiptDto receipt = logEventService.readLogEventReceipt(toAuditRequest(request));
      responseObserver.onNext(toReadLogEventReceiptResponse(receipt));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onError(
          Status.PERMISSION_DENIED.withDescription(ex.getMessage()).asRuntimeException());
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
    } catch (AuditReceiptNotFoundException ex) {
      responseObserver.onError(
          Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
    } catch (AuditStorageUnavailableException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(ex.getMessage()).asRuntimeException());
    } catch (DataAccessException | TransactionException ex) {
      logger.warn(
          "ReadLogEventReceipt transaction is unavailable: {}", ex.getClass().getSimpleName());
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Account audit receipt storage is unavailable")
              .asRuntimeException());
    } catch (Exception ex) {
      logger.error("ReadLogEventReceipt failed before producing a receipt", ex);
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Account audit receipt read failed")
              .asRuntimeException());
    }
  }

  private static net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest toAuditRequest(
      CreateLogEventRequest request) {
    return toAuditRequest(
        request.getScope(),
        request.getTenantId(),
        request.getAuditEventId(),
        request.getProducerService(),
        request.getEventType(),
        request.hasOccurredAt() ? request.getOccurredAt() : null,
        request.getSchemaVersion(),
        request.getPayload(),
        request.getPayloadDigestVersion(),
        request.getPayloadDigest());
  }

  private static net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest toAuditRequest(
      ReadLogEventReceiptRequest request) {
    return toAuditRequest(
        request.getScope(),
        request.getTenantId(),
        request.getAuditEventId(),
        request.getProducerService(),
        request.getEventType(),
        request.hasOccurredAt() ? request.getOccurredAt() : null,
        request.getSchemaVersion(),
        request.getPayload(),
        request.getPayloadDigestVersion(),
        request.getPayloadDigest());
  }

  private static net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest toAuditRequest(
      net.firedevops.firemud.loggingadmin.v1.AccountAuditScope requestScope,
      String tenantIdText,
      String auditEventId,
      String producerService,
      String eventType,
      Timestamp occurredAt,
      int schemaVersion,
      ByteString payload,
      int payloadDigestVersion,
      String payloadDigest) {
    AccountAuditScope scope =
        switch (requestScope) {
          case ACCOUNT_AUDIT_SCOPE_PLATFORM -> AccountAuditScope.PLATFORM;
          case ACCOUNT_AUDIT_SCOPE_TENANT -> AccountAuditScope.TENANT;
          default -> throw new IllegalArgumentException("scope must be platform or tenant");
        };
    Long tenantId;
    if (scope == AccountAuditScope.TENANT) {
      tenantId = RequestIdValidation.requirePositiveLong(tenantIdText, "tenantId");
    } else {
      if (!tenantIdText.isEmpty()) {
        throw new IllegalArgumentException("tenantId must be absent for platform scope");
      }
      tenantId = null;
    }
    if (!ACCOUNT_SERVICE.equals(producerService)) {
      throw new IllegalArgumentException(
          "producerService must match the authenticated account-service workload");
    }
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt is required");
    }
    if (occurredAt.getSeconds() < -62135596800L
        || occurredAt.getSeconds() > 253402300799L
        || occurredAt.getNanos() < 0
        || occurredAt.getNanos() > 999999999) {
      throw new IllegalArgumentException("occurredAt must be a valid protobuf timestamp");
    }
    return new net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest(
        scope,
        tenantId,
        auditEventId,
        ACCOUNT_SERVICE,
        eventType,
        Instant.ofEpochSecond(occurredAt.getSeconds(), occurredAt.getNanos()),
        schemaVersion,
        payload,
        payloadDigestVersion,
        payloadDigest);
  }

  private static CreateLogEventResponse toCreateLogEventResponse(AccountAuditReceiptDto receipt) {
    return CreateLogEventResponse.newBuilder()
        .setScope(toProtoScope(receipt.scope()))
        .setTenantId(receipt.tenantId() == null ? "" : receipt.tenantId().toString())
        .setAuditEventId(receipt.auditEventId())
        .setReceiptId(receipt.receiptId())
        .setLogEventId(Long.toString(receipt.logEventId()))
        .setSchemaVersion(receipt.schemaVersion())
        .setPayloadDigestVersion(receipt.payloadDigestVersion())
        .setPayloadDigest(receipt.payloadDigest())
        .setStatus(toProtoStatus(receipt.status()))
        .setOutcome(toProtoOutcome(receipt.outcome()))
        .build();
  }

  private static ReadLogEventReceiptResponse toReadLogEventReceiptResponse(
      AccountAuditReceiptDto receipt) {
    return ReadLogEventReceiptResponse.newBuilder()
        .setScope(toProtoScope(receipt.scope()))
        .setTenantId(receipt.tenantId() == null ? "" : receipt.tenantId().toString())
        .setAuditEventId(receipt.auditEventId())
        .setReceiptId(receipt.receiptId())
        .setLogEventId(Long.toString(receipt.logEventId()))
        .setSchemaVersion(receipt.schemaVersion())
        .setPayloadDigestVersion(receipt.payloadDigestVersion())
        .setPayloadDigest(receipt.payloadDigest())
        .setStatus(toProtoStatus(receipt.status()))
        .setOutcome(toProtoOutcome(receipt.outcome()))
        .build();
  }

  private static net.firedevops.firemud.loggingadmin.v1.AccountAuditScope toProtoScope(
      AccountAuditScope scope) {
    return switch (scope) {
      case PLATFORM ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditScope.ACCOUNT_AUDIT_SCOPE_PLATFORM;
      case TENANT ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditScope.ACCOUNT_AUDIT_SCOPE_TENANT;
    };
  }

  private static net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus toProtoStatus(
      AccountAuditReceiptStatus status) {
    return switch (status) {
      case COMMITTED ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus
              .ACCOUNT_AUDIT_RECEIPT_STATUS_COMMITTED;
      case MINIMIZED ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus
              .ACCOUNT_AUDIT_RECEIPT_STATUS_MINIMIZED;
      case CONFLICT ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus
              .ACCOUNT_AUDIT_RECEIPT_STATUS_CONFLICT;
    };
  }

  private static net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome toProtoOutcome(
      AccountAuditReceiptOutcome outcome) {
    return switch (outcome) {
      case ACCEPTED ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome
              .ACCOUNT_AUDIT_RECEIPT_OUTCOME_ACCEPTED;
      case DUPLICATE ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome
              .ACCOUNT_AUDIT_RECEIPT_OUTCOME_DUPLICATE;
      case NON_REPLAYABLE ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome
              .ACCOUNT_AUDIT_RECEIPT_OUTCOME_NON_REPLAYABLE;
      case IDEMPOTENCY_CONFLICT ->
          net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome
              .ACCOUNT_AUDIT_RECEIPT_OUTCOME_IDEMPOTENCY_CONFLICT;
    };
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
      requireAllowlistedInternalService(MODERATION_POLICY_CALLERS, "EvaluateModerationPolicy");
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
    } catch (AdminAuthorizationException ex) {
      EvaluateModerationPolicyResponse response =
          EvaluateModerationPolicyResponse.newBuilder()
              .setAllowed(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "EvaluateModerationPolicy",
                      "PERMISSION_DENIED",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
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

  private static void requireAllowlistedInternalService(
      Set<String> allowedServices, String methodName) {
    String serviceName = SessionContext.getServiceName();
    if (!SessionContext.isInternalService()
        || serviceName == null
        || !allowedServices.contains(serviceName)) {
      throw new AdminAuthorizationException(
          methodName + " requires an allowlisted internal service caller");
    }
  }

  private static void requireAccountAuditCaller(String methodName) {
    Set<String> allowedServices = ACCOUNT_AUDIT_CALLER_ALLOWLIST.get(methodName);
    GrpcPeerIdentity peerIdentity = GrpcPeerIdentity.current();
    if (allowedServices == null
        || peerIdentity == null
        || !allowedServices.contains(peerIdentity.service())) {
      throw new AdminAuthorizationException(
          methodName + " requires an allowlisted account-service mTLS peer");
    }
  }
}
