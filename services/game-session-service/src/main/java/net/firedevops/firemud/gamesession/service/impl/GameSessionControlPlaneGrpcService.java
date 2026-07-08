package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateRequest;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceRequest;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceResponse;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorRequest;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorResponse;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResultRequest;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResultResponse;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasRequest;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityRequest;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected repository/services and config properties are internal Spring collaborators")
public final class GameSessionControlPlaneGrpcService
    extends GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase {
  private static final Logger logger =
      LoggerFactory.getLogger(GameSessionControlPlaneGrpcService.class);
  private final GameSessionCommandControlPlaneService commandControlPlaneService;
  private final GameSessionRemoteControlPlaneService remoteControlPlaneService;
  private final GameSessionRuntimeControlPlaneReadService runtimeControlPlaneReadService;
  private final GameSessionAdmissionPointerControlPlaneService admissionPointerControlPlaneService;
  private final GameSessionOperatorControlPlaneService operatorControlPlaneService;
  private final GameSessionVersionUpgradeControlPlaneService versionUpgradeControlPlaneService;
  private final MeterRegistry meterRegistry;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs = 1000L;

  @Autowired
  public GameSessionControlPlaneGrpcService(
      GameSessionCommandControlPlaneService commandControlPlaneService,
      GameSessionRemoteControlPlaneService remoteControlPlaneService,
      GameSessionRuntimeControlPlaneReadService runtimeControlPlaneReadService,
      GameSessionAdmissionPointerControlPlaneService admissionPointerControlPlaneService,
      GameSessionOperatorControlPlaneService operatorControlPlaneService,
      GameSessionVersionUpgradeControlPlaneService versionUpgradeControlPlaneService,
      MeterRegistry meterRegistry) {
    this.commandControlPlaneService = commandControlPlaneService;
    this.remoteControlPlaneService = remoteControlPlaneService;
    this.runtimeControlPlaneReadService = runtimeControlPlaneReadService;
    this.admissionPointerControlPlaneService = admissionPointerControlPlaneService;
    this.operatorControlPlaneService = operatorControlPlaneService;
    this.versionUpgradeControlPlaneService = versionUpgradeControlPlaneService;
    this.meterRegistry = meterRegistry;
  }

  private long parseTenantId(String tenantId) {
    return ControlPlaneRequestParser.parsePositiveLong(tenantId, "tenant_id");
  }

  private long parseGameInstanceId(String gameInstanceId) {
    return ControlPlaneRequestParser.parsePositiveLong(gameInstanceId, "game_instance_id");
  }

  private void requireAdminRole() {
    AdminRoleGuard.requireAdminRole();
  }

  private ErrorDetail authorizationError(String operation, AdminAuthorizationException ex) {
    return GrpcAppErrors.error(
        meterRegistry, logger, operation, "PERMISSION_DENIED", ex.getMessage());
  }

  private ErrorDetail invalidArgumentError(String operation, IllegalArgumentException ex) {
    return GrpcAppErrors.error(
        meterRegistry, logger, operation, "INVALID_ARGUMENT", ex.getMessage());
  }

  private ErrorDetail notFoundError(String operation, RuntimeException ex) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, "NOT_FOUND", ex.getMessage());
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listAdmissionPointers")
  public void listAdmissionPointers(
      ListAdmissionPointersRequest request,
      StreamObserver<ListAdmissionPointersResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(admissionPointerControlPlaneService.listAdmissionPointers());
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ListAdmissionPointersResponse response =
          ListAdmissionPointersResponse.newBuilder()
              .setError(authorizationError("ListAdmissionPointers", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ListAdmissionPointers failed", ex);
      ListAdmissionPointersResponse response =
          ListAdmissionPointersResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listAdmissionPointerAudit")
  public void listAdmissionPointerAudit(
      ListAdmissionPointerAuditRequest request,
      StreamObserver<ListAdmissionPointerAuditResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          admissionPointerControlPlaneService.listAdmissionPointerAudit(request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .setError(authorizationError("ListAdmissionPointerAudit", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListAdmissionPointerAudit",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ListAdmissionPointerAudit failed", ex);
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getGameplayCommandStatus")
  public void getGameplayCommandStatus(
      GetGameplayCommandStatusRequest request,
      StreamObserver<GetGameplayCommandStatusResponse> responseObserver) {
    try {
      requireAdminRole();
      GetGameplayCommandStatusResponse response =
          commandControlPlaneService.getGameplayCommandStatus(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      GetGameplayCommandStatusResponse response =
          GetGameplayCommandStatusResponse.newBuilder()
              .setError(authorizationError("GetGameplayCommandStatus", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetGameplayCommandStatusResponse response =
          GetGameplayCommandStatusResponse.newBuilder()
              .setError(invalidArgumentError("GetGameplayCommandStatus", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetGameplayCommandStatus failed", ex);
      GetGameplayCommandStatusResponse response =
          GetGameplayCommandStatusResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getRuntimeOwnershipStatus")
  public void getRuntimeOwnershipStatus(
      GetRuntimeOwnershipStatusRequest request,
      StreamObserver<GetRuntimeOwnershipStatusResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      RuntimeOwnershipStatus status =
          runtimeControlPlaneReadService.getRuntimeOwnershipStatus(
              tenantId, request, tickDurationMs);
      GetRuntimeOwnershipStatusResponse response =
          GetRuntimeOwnershipStatusResponse.newBuilder().setOwnership(status).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      GetRuntimeOwnershipStatusResponse response =
          GetRuntimeOwnershipStatusResponse.newBuilder()
              .setError(authorizationError("GetRuntimeOwnershipStatus", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetRuntimeOwnershipStatusResponse response =
          GetRuntimeOwnershipStatusResponse.newBuilder()
              .setError(invalidArgumentError("GetRuntimeOwnershipStatus", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetRuntimeOwnershipStatus failed", ex);
      GetRuntimeOwnershipStatusResponse response =
          GetRuntimeOwnershipStatusResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getRemoteCommandCoordinator")
  public void getRemoteCommandCoordinator(
      GetRemoteCommandCoordinatorRequest request,
      StreamObserver<GetRemoteCommandCoordinatorResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          remoteControlPlaneService.getRemoteCommandCoordinator(
              parseTenantId(request.getTenantId()), request.getCoordinatorId()));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetRemoteCommandCoordinatorResponse.newBuilder()
              .setError(authorizationError("GetRemoteCommandCoordinator", ex))
              .build());
      responseObserver.onCompleted();
    } catch (GameSessionRemoteControlPlaneService.NotFoundException ex) {
      responseObserver.onNext(
          GetRemoteCommandCoordinatorResponse.newBuilder()
              .setError(notFoundError("GetRemoteCommandCoordinator", ex))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetRemoteCommandCoordinatorResponse.newBuilder()
              .setError(invalidArgumentError("GetRemoteCommandCoordinator", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetRemoteCommandCoordinator failed", ex);
      responseObserver.onNext(
          GetRemoteCommandCoordinatorResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getRemoteFollowup")
  public void getRemoteFollowup(
      GetRemoteFollowupRequest request,
      StreamObserver<GetRemoteFollowupResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          remoteControlPlaneService.getRemoteFollowup(
              parseTenantId(request.getTenantId()), request.getFollowupId()));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetRemoteFollowupResponse.newBuilder()
              .setError(authorizationError("GetRemoteFollowup", ex))
              .build());
      responseObserver.onCompleted();
    } catch (GameSessionRemoteControlPlaneService.NotFoundException ex) {
      responseObserver.onNext(
          GetRemoteFollowupResponse.newBuilder()
              .setError(notFoundError("GetRemoteFollowup", ex))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetRemoteFollowupResponse.newBuilder()
              .setError(invalidArgumentError("GetRemoteFollowup", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetRemoteFollowup failed", ex);
      responseObserver.onNext(
          GetRemoteFollowupResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getRemoteFollowupResult")
  public void getRemoteFollowupResult(
      GetRemoteFollowupResultRequest request,
      StreamObserver<GetRemoteFollowupResultResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          remoteControlPlaneService.getRemoteFollowupResult(
              parseTenantId(request.getTenantId()), request.getResultId()));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetRemoteFollowupResultResponse.newBuilder()
              .setError(authorizationError("GetRemoteFollowupResult", ex))
              .build());
      responseObserver.onCompleted();
    } catch (GameSessionRemoteControlPlaneService.NotFoundException ex) {
      responseObserver.onNext(
          GetRemoteFollowupResultResponse.newBuilder()
              .setError(notFoundError("GetRemoteFollowupResult", ex))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetRemoteFollowupResultResponse.newBuilder()
              .setError(invalidArgumentError("GetRemoteFollowupResult", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetRemoteFollowupResult failed", ex);
      responseObserver.onNext(
          GetRemoteFollowupResultResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listRemoteCommandCoordinators")
  public void listRemoteCommandCoordinators(
      ListRemoteCommandCoordinatorsRequest request,
      StreamObserver<ListRemoteCommandCoordinatorsResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          remoteControlPlaneService.listRemoteCommandCoordinators(
              parseTenantId(request.getTenantId()), request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          ListRemoteCommandCoordinatorsResponse.newBuilder()
              .setError(authorizationError("ListRemoteCommandCoordinators", ex))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          ListRemoteCommandCoordinatorsResponse.newBuilder()
              .setError(invalidArgumentError("ListRemoteCommandCoordinators", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ListRemoteCommandCoordinators failed", ex);
      responseObserver.onNext(
          ListRemoteCommandCoordinatorsResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.scheduleRemoteFollowup")
  public void scheduleRemoteFollowup(
      ScheduleRemoteFollowupRequest request,
      StreamObserver<ScheduleRemoteFollowupResponse> responseObserver) {
    try {
      responseObserver.onNext(
          remoteControlPlaneService.scheduleRemoteFollowup(
              parseTenantId(request.getTenantId()), request));
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          ScheduleRemoteFollowupResponse.newBuilder()
              .setError(invalidArgumentError("ScheduleRemoteFollowup", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ScheduleRemoteFollowup failed", ex);
      responseObserver.onNext(
          ScheduleRemoteFollowupResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ScheduleRemoteFollowup", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listRemoteFollowups")
  public void listRemoteFollowups(
      ListRemoteFollowupsRequest request,
      StreamObserver<ListRemoteFollowupsResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          remoteControlPlaneService.listRemoteFollowups(
              parseTenantId(request.getTenantId()), request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          ListRemoteFollowupsResponse.newBuilder()
              .setError(authorizationError("ListRemoteFollowups", ex))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          ListRemoteFollowupsResponse.newBuilder()
              .setError(invalidArgumentError("ListRemoteFollowups", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ListRemoteFollowups failed", ex);
      responseObserver.onNext(
          ListRemoteFollowupsResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listRemoteFollowupResults")
  public void listRemoteFollowupResults(
      ListRemoteFollowupResultsRequest request,
      StreamObserver<ListRemoteFollowupResultsResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          remoteControlPlaneService.listRemoteFollowupResults(
              parseTenantId(request.getTenantId()), request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          ListRemoteFollowupResultsResponse.newBuilder()
              .setError(authorizationError("ListRemoteFollowupResults", ex))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          ListRemoteFollowupResultsResponse.newBuilder()
              .setError(invalidArgumentError("ListRemoteFollowupResults", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ListRemoteFollowupResults failed", ex);
      responseObserver.onNext(
          ListRemoteFollowupResultsResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.setAdmissionPointer")
  public void setAdmissionPointer(
      SetAdmissionPointerRequest request,
      StreamObserver<SetAdmissionPointerResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          admissionPointerControlPlaneService.setAdmissionPointer(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId()),
              request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(authorizationError("SetAdmissionPointer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(invalidArgumentError("SetAdmissionPointer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (CutoverPreparationValidationException ex) {
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, "CUTOVER_PREPARATION_INVALID", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdmissionPointerVersionMismatchException ex) {
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(meterRegistry, "POINTER_VERSION_MISMATCH", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("SetAdmissionPointer failed", ex);
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.executePreparedVersionCutover")
  public void executePreparedVersionCutover(
      ExecutePreparedVersionCutoverRequest request,
      StreamObserver<ExecutePreparedVersionCutoverResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          admissionPointerControlPlaneService.executePreparedVersionCutover(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getTargetGameInstanceId()),
              request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ExecutePreparedVersionCutoverResponse response =
          ExecutePreparedVersionCutoverResponse.newBuilder()
              .setError(authorizationError("ExecutePreparedVersionCutover", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ExecutePreparedVersionCutoverResponse response =
          ExecutePreparedVersionCutoverResponse.newBuilder()
              .setError(invalidArgumentError("ExecutePreparedVersionCutover", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (CutoverPreparationValidationException ex) {
      ExecutePreparedVersionCutoverResponse response =
          ExecutePreparedVersionCutoverResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, "CUTOVER_PREPARATION_INVALID", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdmissionPointerVersionMismatchException ex) {
      ExecutePreparedVersionCutoverResponse response =
          ExecutePreparedVersionCutoverResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(meterRegistry, "POINTER_VERSION_MISMATCH", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ExecutePreparedVersionCutover failed", ex);
      ExecutePreparedVersionCutoverResponse response =
          ExecutePreparedVersionCutoverResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getPinnedScriptPatchVersion")
  public void getPinnedScriptPatchVersion(
      GetPinnedScriptPatchVersionRequest request,
      StreamObserver<GetPinnedScriptPatchVersionResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.getPinnedScriptPatchVersion(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId())));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(authorizationError("GetPinnedScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(invalidArgumentError("GetPinnedScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetPinnedScriptPatchVersion failed", ex);
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getGameSessionPinConvergence")
  public void getGameSessionPinConvergence(
      GetGameSessionPinConvergenceRequest request,
      StreamObserver<GetGameSessionPinConvergenceResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.getGameSessionPinConvergence(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId())));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      GetGameSessionPinConvergenceResponse response =
          GetGameSessionPinConvergenceResponse.newBuilder()
              .setError(authorizationError("GetGameSessionPinConvergence", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetGameSessionPinConvergenceResponse response =
          GetGameSessionPinConvergenceResponse.newBuilder()
              .setError(invalidArgumentError("GetGameSessionPinConvergence", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetGameSessionPinConvergence failed", ex);
      GetGameSessionPinConvergenceResponse response =
          GetGameSessionPinConvergenceResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getGameInstanceRuntimeState")
  public void getGameInstanceRuntimeState(
      GetGameInstanceRuntimeStateRequest request,
      StreamObserver<GetGameInstanceRuntimeStateResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      GetGameInstanceRuntimeStateResponse response =
          GetGameInstanceRuntimeStateResponse.newBuilder()
              .setRuntimeState(
                  runtimeControlPlaneReadService.getGameInstanceRuntimeState(tenantId, request))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      GetGameInstanceRuntimeStateResponse response =
          GetGameInstanceRuntimeStateResponse.newBuilder()
              .setError(authorizationError("GetGameInstanceRuntimeState", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetGameInstanceRuntimeStateResponse response =
          GetGameInstanceRuntimeStateResponse.newBuilder()
              .setError(invalidArgumentError("GetGameInstanceRuntimeState", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetGameInstanceRuntimeState failed", ex);
      GetGameInstanceRuntimeStateResponse response =
          GetGameInstanceRuntimeStateResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.validateBuiltInCommandAlias")
  public void validateBuiltInCommandAlias(
      ValidateBuiltInCommandAliasRequest request,
      StreamObserver<ValidateBuiltInCommandAliasResponse> responseObserver) {
    try {
      requireAdminRole();
      ValidateBuiltInCommandAliasResponse response =
          commandControlPlaneService.validateBuiltInCommandAlias(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ValidateBuiltInCommandAliasResponse response =
          ValidateBuiltInCommandAliasResponse.newBuilder()
              .setError(authorizationError("ValidateBuiltInCommandAlias", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ValidateBuiltInCommandAliasResponse response =
          ValidateBuiltInCommandAliasResponse.newBuilder()
              .setError(invalidArgumentError("ValidateBuiltInCommandAlias", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ValidateBuiltInCommandAlias failed", ex);
      ValidateBuiltInCommandAliasResponse response =
          ValidateBuiltInCommandAliasResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.setPinnedScriptPatchVersion")
  public void setPinnedScriptPatchVersion(
      SetPinnedScriptPatchVersionRequest request,
      StreamObserver<SetPinnedScriptPatchVersionResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.setPinnedScriptPatchVersion(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId()),
              request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(authorizationError("SetPinnedScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(invalidArgumentError("SetPinnedScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("SetPinnedScriptPatchVersion failed", ex);
      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.rollbackScriptPatchVersion")
  public void rollbackScriptPatchVersion(
      RollbackScriptPatchVersionRequest request,
      StreamObserver<RollbackScriptPatchVersionResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.rollbackScriptPatchVersion(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId()),
              request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setError(authorizationError("RollbackScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setError(invalidArgumentError("RollbackScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("RollbackScriptPatchVersion failed", ex);
      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.validateInstanceCutoverCompatibility")
  public void validateInstanceCutoverCompatibility(
      ValidateInstanceCutoverCompatibilityRequest request,
      StreamObserver<ValidateInstanceCutoverCompatibilityResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          versionUpgradeControlPlaneService.validateInstanceCutoverCompatibility(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getSourceGameInstanceId()),
              parseGameInstanceId(request.getTargetVersionId())));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ValidateInstanceCutoverCompatibilityResponse response =
          ValidateInstanceCutoverCompatibilityResponse.newBuilder()
              .setError(authorizationError("ValidateInstanceCutoverCompatibility", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ValidateInstanceCutoverCompatibilityResponse response =
          ValidateInstanceCutoverCompatibilityResponse.newBuilder()
              .setError(invalidArgumentError("ValidateInstanceCutoverCompatibility", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ValidateInstanceCutoverCompatibility failed", ex);
      ValidateInstanceCutoverCompatibilityResponse response =
          ValidateInstanceCutoverCompatibilityResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.prepareVersionUpgrade")
  public void prepareVersionUpgrade(
      PrepareVersionUpgradeRequest request,
      StreamObserver<PrepareVersionUpgradeResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          versionUpgradeControlPlaneService.prepareVersionUpgrade(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getSourceGameInstanceId()),
              parseGameInstanceId(request.getTargetVersionId()),
              request.getControlPlaneRequestId()));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      PrepareVersionUpgradeResponse response =
          PrepareVersionUpgradeResponse.newBuilder()
              .setError(authorizationError("PrepareVersionUpgrade", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PrepareVersionUpgradeResponse response =
          PrepareVersionUpgradeResponse.newBuilder()
              .setError(invalidArgumentError("PrepareVersionUpgrade", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("PrepareVersionUpgrade failed", ex);
      PrepareVersionUpgradeResponse response =
          PrepareVersionUpgradeResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getPreparedVersionUpgrade")
  public void getPreparedVersionUpgrade(
      GetPreparedVersionUpgradeRequest request,
      StreamObserver<GetPreparedVersionUpgradeResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          versionUpgradeControlPlaneService.getPreparedVersionUpgrade(
              parseTenantId(request.getTenantId()), request.getPreparationId()));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      GetPreparedVersionUpgradeResponse response =
          GetPreparedVersionUpgradeResponse.newBuilder()
              .setError(authorizationError("GetPreparedVersionUpgrade", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetPreparedVersionUpgradeResponse response =
          GetPreparedVersionUpgradeResponse.newBuilder()
              .setError(invalidArgumentError("GetPreparedVersionUpgrade", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetPreparedVersionUpgrade failed", ex);
      GetPreparedVersionUpgradeResponse response =
          GetPreparedVersionUpgradeResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.enqueueAutomationCommandIfAbsent")
  public void enqueueAutomationCommandIfAbsent(
      EnqueueAutomationCommandIfAbsentRequest request,
      StreamObserver<EnqueueAutomationCommandIfAbsentResponse> responseObserver) {
    try {
      EnqueueAutomationCommandIfAbsentResponse response =
          commandControlPlaneService.enqueueAutomationCommandIfAbsent(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      EnqueueAutomationCommandIfAbsentResponse response =
          EnqueueAutomationCommandIfAbsentResponse.newBuilder()
              .setAccepted(false)
              .setAdmissionOutcome("REJECTED")
              .setError(invalidArgumentError("EnqueueAutomationCommandIfAbsent", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("EnqueueAutomationCommandIfAbsent failed", ex);
      EnqueueAutomationCommandIfAbsentResponse response =
          EnqueueAutomationCommandIfAbsentResponse.newBuilder()
              .setAccepted(false)
              .setAdmissionOutcome("INTERNAL_ERROR")
              .setError(
                  GrpcAppErrors.internal(
                      meterRegistry, logger, "EnqueueAutomationCommandIfAbsent", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.purgeQueuedTickCommandsForScriptPatch")
  public void purgeQueuedTickCommandsForScriptPatch(
      PurgeQueuedTickCommandsForScriptPatchRequest request,
      StreamObserver<PurgeQueuedTickCommandsForScriptPatchResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.purgeQueuedTickCommandsForScriptPatch(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId()),
              request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      PurgeQueuedTickCommandsForScriptPatchResponse response =
          PurgeQueuedTickCommandsForScriptPatchResponse.newBuilder()
              .setError(authorizationError("PurgeQueuedTickCommandsForScriptPatch", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PurgeQueuedTickCommandsForScriptPatchResponse response =
          PurgeQueuedTickCommandsForScriptPatchResponse.newBuilder()
              .setError(invalidArgumentError("PurgeQueuedTickCommandsForScriptPatch", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("PurgeQueuedTickCommandsForScriptPatch failed", ex);
      PurgeQueuedTickCommandsForScriptPatchResponse response =
          PurgeQueuedTickCommandsForScriptPatchResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.purgeQueuedTickCommandsForPluginVersion")
  public void purgeQueuedTickCommandsForPluginVersion(
      PurgeQueuedTickCommandsForPluginVersionRequest request,
      StreamObserver<PurgeQueuedTickCommandsForPluginVersionResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.purgeQueuedTickCommandsForPluginVersion(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId()),
              request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      PurgeQueuedTickCommandsForPluginVersionResponse response =
          PurgeQueuedTickCommandsForPluginVersionResponse.newBuilder()
              .setError(authorizationError("PurgeQueuedTickCommandsForPluginVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PurgeQueuedTickCommandsForPluginVersionResponse response =
          PurgeQueuedTickCommandsForPluginVersionResponse.newBuilder()
              .setError(invalidArgumentError("PurgeQueuedTickCommandsForPluginVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("PurgeQueuedTickCommandsForPluginVersion failed", ex);
      PurgeQueuedTickCommandsForPluginVersionResponse response =
          PurgeQueuedTickCommandsForPluginVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Timed(value = "gamesessionGrpc.controlPlane.pauseTicksForScope")
  public void pauseTicksForScope(
      PauseTicksForScopeRequest request,
      StreamObserver<PauseTicksForScopeResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.pauseTicksForScope(
              parseTenantId(request.getTenantId()), request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(authorizationError("PauseTicksForScope", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgumentError("PauseTicksForScope", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("PauseTicksForScope failed", ex);
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.resumeTicksForScope")
  public void resumeTicksForScope(
      ResumeTicksForScopeRequest request,
      StreamObserver<ResumeTicksForScopeResponse> responseObserver) {
    try {
      requireAdminRole();
      responseObserver.onNext(
          operatorControlPlaneService.resumeTicksForScope(
              parseTenantId(request.getTenantId()), request));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(authorizationError("ResumeTicksForScope", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(invalidArgumentError("ResumeTicksForScope", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ResumeTicksForScope failed", ex);
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
