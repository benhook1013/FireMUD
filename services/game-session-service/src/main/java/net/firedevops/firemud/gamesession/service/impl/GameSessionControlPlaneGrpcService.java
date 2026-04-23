package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.InstanceCutoverCompatibilityService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.service.VersionUpgradePreparationService;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.CutoverCompatibilityResult;
import net.firedevops.firemud.gamesession.v1.CutoverParticipantResult;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GameplayCommandStatus;
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
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.PreparedVersionUpgrade;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityRequest;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public final class GameSessionControlPlaneGrpcService
    extends GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase {
  private static final Logger logger =
      LoggerFactory.getLogger(GameSessionControlPlaneGrpcService.class);

  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final InstanceCutoverCompatibilityService instanceCutoverCompatibilityService;
  private final VersionUpgradePreparationService versionUpgradePreparationService;
  private final TickService tickService;
  private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repository/services are internal Spring collaborators")
  public GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService,
      TickService tickService,
      MeterRegistry meterRegistry) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.instanceCutoverCompatibilityService = instanceCutoverCompatibilityService;
    this.versionUpgradePreparationService = versionUpgradePreparationService;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
  }

  private long parseTenantId(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenant_id is required");
    }
    try {
      return Long.parseLong(tenantId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("tenant_id must be a number");
    }
  }

  private long parseGameInstanceId(String gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      throw new IllegalArgumentException("game_instance_id is required");
    }
    try {
      return Long.parseLong(gameInstanceId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("game_instance_id must be a number");
    }
  }

  private GameInstance getInstanceOrThrow(long gameInstanceId) {
    return gameInstanceRepository
        .findById(gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Game instance not found"));
  }

  private void requireAdminRole() {
    AdminRoleGuard.requireAdminRole();
  }

  private ErrorDetail authorizationError(String operation, AdminAuthorizationException ex) {
    return GrpcAppErrors.error(
        meterRegistry, logger, operation, "PERMISSION_DENIED", ex.getMessage());
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listAdmissionPointers")
  public void listAdmissionPointers(
      ListAdmissionPointersRequest request,
      StreamObserver<ListAdmissionPointersResponse> responseObserver) {
    try {
      requireAdminRole();
      ListAdmissionPointersResponse response =
          ListAdmissionPointersResponse.newBuilder()
              .addAllPointers(
                  gameplayAdmissionPointerAuthorityService.listPointers().stream()
                      .flatMap(
                          pointer ->
                              gameplayAdmissionPointerAuthorityService
                                  .listPointerAudit(pointer.worldSlug(), pointer.realmSlug())
                                  .stream()
                                  .limit(1)
                                  .map(this::toEntry))
                      .toList())
              .build();
      responseObserver.onNext(response);
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
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .addAllAudit(
                  gameplayAdmissionPointerAuthorityService
                      .listPointerAudit(request.getWorldSlug(), request.getRealmSlug())
                      .stream()
                      .map(this::toEntry)
                      .toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .setError(authorizationError("ListAdmissionPointerAudit", ex))
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
      if (request.getCommandId().isBlank()) {
        throw new IllegalArgumentException("command_id is required");
      }
      GameplayCommand command =
          gameplayCommandRepository
              .findByCommandId(request.getCommandId())
              .orElseThrow(() -> new IllegalArgumentException("Gameplay command not found"));
      GetGameplayCommandStatusResponse response =
          GetGameplayCommandStatusResponse.newBuilder().setCommand(toStatus(command)).build();
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      RuntimeRegionStatus status =
          runtimeRegionStatusRepository
              .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
              .orElseThrow(() -> new IllegalArgumentException("Runtime ownership not found"));
      GetRuntimeOwnershipStatusResponse response =
          GetRuntimeOwnershipStatusResponse.newBuilder().setOwnership(toStatus(status)).build();
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
  @Timed(value = "gamesessionGrpc.controlPlane.setAdmissionPointer")
  public void setAdmissionPointer(
      SetAdmissionPointerRequest request,
      StreamObserver<SetAdmissionPointerResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      validatePreparedUpgradeForPointerChange(request, tenantId, gameInstanceId);
      gameplayAdmissionPointerAuthorityService.upsertPointer(
          new GameplayAdmissionPointerMutation(
              request.getWorldSlug(),
              request.getWorldDisplayName(),
              request.getRealmSlug(),
              request.getRealmDisplayName(),
              tenantId,
              gameInstanceId,
              request.getVisible(),
              request.getRequiresCharacterSelection(),
              request.getStateScope(),
              request.getCharacterCreationPolicy(),
              request.getActorPrincipal(),
              request.getReason(),
              request.getControlPlaneRequestId(),
              request.hasExpectedPointerVersion() ? request.getExpectedPointerVersion() : null,
              normalizeBlank(request.getPreparedVersionUpgradeId())));
      AdmissionPointerControlPlaneEntry entry =
          gameplayAdmissionPointerAuthorityService
              .listPointerAudit(request.getWorldSlug(), request.getRealmSlug())
              .stream()
              .findFirst()
              .map(this::toEntry)
              .orElseThrow(() -> new IllegalStateException("Admission pointer audit missing"));
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder().setPointer(entry).build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = parseTenantId(request.getTenantId());
      long targetGameInstanceId = parseGameInstanceId(request.getTargetGameInstanceId());
      requireText(request.getWorldSlug(), "world_slug is required");
      requireText(request.getRealmSlug(), "realm_slug is required");
      requireText(request.getPreparedVersionUpgradeId(), "prepared_version_upgrade_id is required");
      requireText(request.getActorPrincipal(), "actor_principal is required");
      requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
      GameplayAdmissionPointerSnapshot currentPointer =
          gameplayAdmissionPointerAuthorityService
              .findPointer(request.getWorldSlug(), request.getRealmSlug())
              .orElseThrow(() -> new IllegalArgumentException("Admission pointer not found"));
      if (currentPointer.tenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own admission pointer");
      }
      if (currentPointer.gameInstanceId() == targetGameInstanceId) {
        AdmissionPointerControlPlaneEntry idempotentEntry =
            currentExecutedCutoverEntryIfSameRequest(
                request.getWorldSlug(),
                request.getRealmSlug(),
                tenantId,
                targetGameInstanceId,
                request.getPreparedVersionUpgradeId(),
                request.getControlPlaneRequestId());
        ExecutePreparedVersionCutoverResponse response =
            ExecutePreparedVersionCutoverResponse.newBuilder().setPointer(idempotentEntry).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
        return;
      }
      validatePreparedUpgradeForPointerChange(
          request.getWorldSlug(),
          request.getRealmSlug(),
          tenantId,
          targetGameInstanceId,
          request.getPreparedVersionUpgradeId(),
          currentPointer);
      gameplayAdmissionPointerAuthorityService.upsertPointer(
          new GameplayAdmissionPointerMutation(
              currentPointer.worldSlug(),
              currentPointer.worldDisplayName(),
              currentPointer.realmSlug(),
              currentPointer.realmDisplayName(),
              tenantId,
              targetGameInstanceId,
              currentPointer.visible(),
              currentPointer.requiresCharacterSelection(),
              currentPointer.stateScope(),
              currentPointer.characterCreationPolicy(),
              request.getActorPrincipal(),
              request.getReason(),
              request.getControlPlaneRequestId(),
              request.hasExpectedPointerVersion() ? request.getExpectedPointerVersion() : null,
              request.getPreparedVersionUpgradeId()));
      AdmissionPointerControlPlaneEntry entry =
          gameplayAdmissionPointerAuthorityService
              .listPointerAudit(request.getWorldSlug(), request.getRealmSlug())
              .stream()
              .findFirst()
              .map(this::toEntry)
              .orElseThrow(() -> new IllegalStateException("Admission pointer audit missing"));
      versionUpgradePreparationService.markPreparedVersionUpgradeExecuted(
          tenantId,
          request.getPreparedVersionUpgradeId(),
          targetGameInstanceId,
          entry.getPointerVersion(),
          request.getControlPlaneRequestId());
      ExecutePreparedVersionCutoverResponse response =
          ExecutePreparedVersionCutoverResponse.newBuilder().setPointer(entry).build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setPinnedAtMs(
                  instance.getScriptPatchPinnedAt() == null
                      ? 0
                      : instance.getScriptPatchPinnedAt().toEpochMilli())
              .setPinnedBy(
                  instance.getScriptPatchPinnedBy() == null
                      ? ""
                      : instance.getScriptPatchPinnedBy())
              .setControlPlaneRequestId(
                  instance.getScriptPatchPinnedControlPlaneRequestId() == null
                      ? ""
                      : instance.getScriptPatchPinnedControlPlaneRequestId())
              .build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      GetGameSessionPinConvergenceResponse response =
          GetGameSessionPinConvergenceResponse.newBuilder()
              .setTenantId(Long.toString(instance.getTenantId()))
              .setGameInstanceId(Long.toString(instance.getId()))
              .setObservedPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setLastObservedControlPlaneRequestId(
                  instance.getScriptPatchPinnedControlPlaneRequestId() == null
                      ? ""
                      : instance.getScriptPatchPinnedControlPlaneRequestId())
              .setObservedAtMs(
                  instance.getScriptPatchPinnedAt() == null
                      ? 0L
                      : instance.getScriptPatchPinnedAt().toEpochMilli())
              .build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      GetGameInstanceRuntimeStateResponse response =
          GetGameInstanceRuntimeStateResponse.newBuilder()
              .setRuntimeState(
                  GameInstanceRuntimeState.newBuilder()
                      .setTenantId(Long.toString(instance.getTenantId()))
                      .setGameInstanceId(Long.toString(instance.getId()))
                      .setRuntimeVersionId(instance.getRuntimeVersion())
                      .setPinnedScriptPatchVersion(
                          instance.getScriptPatchVersion() == null
                              ? ""
                              : instance.getScriptPatchVersion())
                      .setLaunchDescriptorId(
                          instance.getLaunchDescriptorId() == null
                              ? ""
                              : instance.getLaunchDescriptorId())
                      .setStatus(instance.getStatus() == null ? "" : instance.getStatus())
                      .setVersionId(
                          instance.getVersionId() == null
                              ? ""
                              : Long.toString(instance.getVersionId()))
                      .setReleaseBundleId(
                          instance.getReleaseBundleId() == null
                              ? ""
                              : Long.toString(instance.getReleaseBundleId()))
                      .setVersionStateEpoch(
                          instance.getVersionStateEpoch() == null
                              ? 0L
                              : instance.getVersionStateEpoch())
                      .setScriptPatchPinnedAtMs(
                          instance.getScriptPatchPinnedAt() == null
                              ? 0L
                              : instance.getScriptPatchPinnedAt().toEpochMilli())
                      .setScriptPatchPinnedBy(
                          instance.getScriptPatchPinnedBy() == null
                              ? ""
                              : instance.getScriptPatchPinnedBy())
                      .setScriptPatchPinnedReason(
                          instance.getScriptPatchPinnedReason() == null
                              ? ""
                              : instance.getScriptPatchPinnedReason())
                      .setScriptPatchPinnedControlPlaneRequestId(
                          instance.getScriptPatchPinnedControlPlaneRequestId() == null
                              ? ""
                              : instance.getScriptPatchPinnedControlPlaneRequestId())
                      .build())
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
  @Timed(value = "gamesessionGrpc.controlPlane.setPinnedScriptPatchVersion")
  public void setPinnedScriptPatchVersion(
      SetPinnedScriptPatchVersionRequest request,
      StreamObserver<SetPinnedScriptPatchVersionResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }

      String previous = instance.getScriptPatchVersion();
      instance.setScriptPatchVersion(request.getTargetScriptPatchVersion());
      instance.setScriptPatchPinnedAt(Instant.now());
      instance.setScriptPatchPinnedBy(request.getActorPrincipal());
      instance.setScriptPatchPinnedReason(request.getReason());
      instance.setScriptPatchPinnedControlPlaneRequestId(request.getControlPlaneRequestId());
      gameInstanceRepository.save(instance);

      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setPreviousScriptPatchVersion(previous == null ? "" : previous)
              .setPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setControlPlaneRequestId(request.getControlPlaneRequestId())
              .build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }

      String previous = instance.getScriptPatchVersion();
      instance.setScriptPatchVersion(request.getTargetScriptPatchVersion());
      instance.setScriptPatchPinnedAt(Instant.now());
      instance.setScriptPatchPinnedBy(request.getActorPrincipal());
      instance.setScriptPatchPinnedReason(request.getReason());
      instance.setScriptPatchPinnedControlPlaneRequestId(request.getControlPlaneRequestId());
      gameInstanceRepository.save(instance);

      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setPreviousScriptPatchVersion(previous == null ? "" : previous)
              .setPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setControlPlaneRequestId(request.getControlPlaneRequestId())
              .build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      var validation =
          instanceCutoverCompatibilityService.validateInstanceCutoverCompatibility(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getSourceGameInstanceId()),
              parseGameInstanceId(request.getTargetVersionId()));
      ValidateInstanceCutoverCompatibilityResponse.Builder response =
          ValidateInstanceCutoverCompatibilityResponse.newBuilder()
              .setResult(toCutoverCompatibilityResult(validation.result()))
              .addAllReasons(validation.reasons())
              .addAllCheckedParticipants(validation.checkedParticipants())
              .setCheckedAtMs(validation.checkedAt().toEpochMilli())
              .addAllParticipantResults(
                  validation.participantResults().stream().map(this::toParticipantResult).toList());
      if (validation.remapSetId() != null) {
        response.setRemapSetId(validation.remapSetId());
      }
      responseObserver.onNext(response.build());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      var preparation =
          versionUpgradePreparationService.prepareVersionUpgrade(
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getSourceGameInstanceId()),
              parseGameInstanceId(request.getTargetVersionId()),
              request.getControlPlaneRequestId());
      responseObserver.onNext(
          PrepareVersionUpgradeResponse.newBuilder()
              .setPreparation(toPreparedVersionUpgrade(preparation))
              .build());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      var preparation =
          versionUpgradePreparationService.getPreparedVersionUpgrade(
              parseTenantId(request.getTenantId()), request.getPreparationId());
      responseObserver.onNext(
          GetPreparedVersionUpgradeResponse.newBuilder()
              .setPreparation(toPreparedVersionUpgrade(preparation))
              .build());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      EnqueueAutomationCommandIfAbsentResponse response = enqueueAutomationCommand(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      EnqueueAutomationCommandIfAbsentResponse response =
          EnqueueAutomationCommandIfAbsentResponse.newBuilder()
              .setAccepted(false)
              .setAdmissionOutcome("REJECTED")
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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

  private EnqueueAutomationCommandIfAbsentResponse enqueueAutomationCommand(
      EnqueueAutomationCommandIfAbsentRequest request) {
    long tenantId = parseTenantId(request.getTenantId());
    long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
    requireText(request.getRegionId(), "region_id is required");
    if (request.getRegionEpoch() <= 0) {
      throw new IllegalArgumentException("region_epoch must be positive");
    }
    requireText(request.getAutomationDispatchId(), "automation_dispatch_id is required");
    requireText(request.getAutomationWorkItemId(), "automation_work_item_id is required");
    requireText(request.getScriptId(), "script_id is required");
    requireText(request.getScriptPatchVersion(), "script_patch_version is required");
    requireText(request.getTargetEntityId(), "target_entity_id is required");
    requireText(request.getCommand(), "command is required");

    GameInstance instance = getInstanceOrThrow(gameInstanceId);
    if (instance.getTenantId() != tenantId) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }

    return gameplayCommandRepository
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
            tenantId,
            gameInstanceId,
            request.getRegionId(),
            request.getRegionEpoch(),
            request.getAutomationDispatchId())
        .map(this::duplicateAutomationResponse)
        .orElseGet(() -> enqueueNewAutomationCommand(request, tenantId, gameInstanceId));
  }

  private EnqueueAutomationCommandIfAbsentResponse duplicateAutomationResponse(
      GameplayCommand command) {
    return EnqueueAutomationCommandIfAbsentResponse.newBuilder()
        .setAccepted(true)
        .setAdmissionOutcome("DUPLICATE_NOOP")
        .setCommandId(command.getCommandId())
        .build();
  }

  private EnqueueAutomationCommandIfAbsentResponse enqueueNewAutomationCommand(
      EnqueueAutomationCommandIfAbsentRequest request, long tenantId, long gameInstanceId) {
    GameplayCommand command = acceptedAutomationCommand(request, tenantId, gameInstanceId);
    gameplayCommandRepository.save(command);
    try {
      tickService.enqueueCommand(
          tenantId,
          gameInstanceId,
          command.getCommandId(),
          request.getCommand(),
          request.getRequiresSoloTick());
      markAutomationStaged(command);
      triggerImmediateAutomationTick(tenantId, gameInstanceId);
      return EnqueueAutomationCommandIfAbsentResponse.newBuilder()
          .setAccepted(true)
          .setAdmissionOutcome("ENQUEUED")
          .setCommandId(command.getCommandId())
          .build();
    } catch (IllegalArgumentException ex) {
      markAutomationFailed(command, "INVALID_ARGUMENT", ex.getMessage());
      return EnqueueAutomationCommandIfAbsentResponse.newBuilder()
          .setAccepted(false)
          .setAdmissionOutcome("REJECTED")
          .setCommandId(command.getCommandId())
          .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
          .build();
    }
  }

  private GameplayCommand acceptedAutomationCommand(
      EnqueueAutomationCommandIfAbsentRequest request, long tenantId, long gameInstanceId) {
    Instant now = Instant.now();
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("auto-" + UUID.randomUUID());
    command.setTenantId(tenantId);
    command.setGameInstanceId(gameInstanceId);
    command.setSessionId(0L);
    command.setCommandName(commandName(request.getCommand()));
    command.setCommandText(request.getCommand());
    command.setSanitizedCommandText(GameSessionCommandLogSanitizer.sanitize(request.getCommand()));
    command.setRequiresSoloTick(request.getRequiresSoloTick());
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(now);
    command.setLastAttemptAt(now);
    command.setAttemptCount(1);
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId(request.getAutomationDispatchId());
    command.setAutomationWorkItemId(request.getAutomationWorkItemId());
    command.setScriptId(request.getScriptId());
    command.setScriptPatchVersion(request.getScriptPatchVersion());
    command.setPluginId(normalizeBlank(request.getPluginId()));
    command.setPluginVersionId(normalizeBlank(request.getPluginVersionId()));
    command.setTargetEntityId(request.getTargetEntityId());
    command.setRegionId(request.getRegionId());
    command.setRegionEpoch(request.getRegionEpoch());
    command.setDueTickId(request.getDueTickId() > 0 ? request.getDueTickId() : null);
    return command;
  }

  private void markAutomationStaged(GameplayCommand command) {
    Instant now = Instant.now();
    command.setExecutionOutcome("STAGED");
    command.setStagedAt(now);
    command.setLastAttemptAt(now);
    gameplayCommandRepository.save(command);
  }

  private void markAutomationFailed(GameplayCommand command, String code, String message) {
    Instant now = Instant.now();
    command.setExecutionOutcome("FAILED");
    command.setGameplayResult("NOT_APPLIED");
    command.setCompletedAt(now);
    command.setLastAttemptAt(now);
    command.setFailureCode(code);
    command.setFailureMessage(message);
    gameplayCommandRepository.save(command);
  }

  private void triggerImmediateAutomationTick(long tenantId, long gameInstanceId) {
    try {
      tickService.processTick(tenantId, gameInstanceId);
    } catch (RuntimeException ex) {
      logger.warn(
          "Immediate automation tick kick failed tenantId={} gameInstanceId={}",
          tenantId,
          gameInstanceId,
          ex);
    }
  }

  private String commandName(String command) {
    String trimmed = command == null ? "" : command.trim();
    if (trimmed.isEmpty()) {
      return "UNKNOWN";
    }
    int firstSpace = trimmed.indexOf(' ');
    String token = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    return token.toUpperCase(java.util.Locale.ROOT);
  }

  private AdmissionPointerControlPlaneEntry toEntry(GameplayAdmissionPointerAuditEntry entry) {
    AdmissionPointerControlPlaneEntry.Builder builder =
        AdmissionPointerControlPlaneEntry.newBuilder()
            .setWorldSlug(entry.worldSlug())
            .setWorldDisplayName(entry.worldDisplayName())
            .setRealmSlug(entry.realmSlug())
            .setRealmDisplayName(entry.realmDisplayName())
            .setTenantId(Long.toString(entry.tenantId()))
            .setGameInstanceId(Long.toString(entry.gameInstanceId()))
            .setPointerVersion(entry.pointerVersion())
            .setVisible(entry.visible())
            .setRequiresCharacterSelection(entry.requiresCharacterSelection())
            .setStateScope(entry.stateScope())
            .setCharacterCreationPolicy(entry.characterCreationPolicy())
            .setActorPrincipal(entry.actorPrincipal())
            .setReason(entry.reason())
            .setControlPlaneRequestId(entry.controlPlaneRequestId())
            .setOccurredAtMs(entry.occurredAt().toEpochMilli());
    if (!normalizeBlank(entry.preparedVersionUpgradeId()).isEmpty()) {
      builder.setPreparedVersionUpgradeId(entry.preparedVersionUpgradeId());
    }
    return builder.build();
  }

  private void validatePreparedUpgradeForPointerChange(
      SetAdmissionPointerRequest request, long tenantId, long targetGameInstanceId) {
    GameplayAdmissionPointerSnapshot currentPointer =
        gameplayAdmissionPointerAuthorityService
            .findPointer(request.getWorldSlug(), request.getRealmSlug())
            .orElse(null);
    validatePreparedUpgradeForPointerChange(
        request.getWorldSlug(),
        request.getRealmSlug(),
        tenantId,
        targetGameInstanceId,
        request.getPreparedVersionUpgradeId(),
        currentPointer);
  }

  private void validatePreparedUpgradeForPointerChange(
      String worldSlug,
      String realmSlug,
      long tenantId,
      long targetGameInstanceId,
      String preparedVersionUpgradeId,
      GameplayAdmissionPointerSnapshot currentPointer) {
    GameInstance targetInstance = getInstanceOrThrow(targetGameInstanceId);
    if (!Long.valueOf(tenantId).equals(targetInstance.getTenantId())) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }
    if (currentPointer == null
        || currentPointer.gameInstanceId() == targetGameInstanceId
        || currentPointer.tenantId() != tenantId) {
      return;
    }
    if (preparedVersionUpgradeId == null || preparedVersionUpgradeId.isBlank()) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id is required when changing admission pointer target");
    }
    PreparedVersionUpgradeDto preparation =
        versionUpgradePreparationService.getPreparedVersionUpgrade(
            tenantId, preparedVersionUpgradeId);
    if (!"COMPATIBLE".equals(preparation.result())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id must reference a COMPATIBLE preparation");
    }
    if (!Long.valueOf(currentPointer.gameInstanceId()).equals(preparation.sourceGameInstanceId())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id does not match the current admission-pointer source instance");
    }
    if (!Long.valueOf(targetGameInstanceId).equals(targetInstance.getId())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id target does not match game_instance_id");
    }
    if (!Long.valueOf(preparation.targetVersionId()).equals(targetInstance.getVersionId())) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id targetVersionId does not match target instance version");
    }
    if (!normalizeBlank(preparation.targetLaunchDescriptorId())
        .equals(normalizeBlank(targetInstance.getLaunchDescriptorId()))) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id targetLaunchDescriptorId does not match target instance");
    }
    if (!normalizeBlank(preparation.remapSetId())
        .equals(normalizeBlank(targetInstance.getRemapSetId()))) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id remapSetId does not match target instance");
    }
  }

  private AdmissionPointerControlPlaneEntry currentExecutedCutoverEntryIfSameRequest(
      String worldSlug,
      String realmSlug,
      long tenantId,
      long targetGameInstanceId,
      String preparedVersionUpgradeId,
      String controlPlaneRequestId) {
    PreparedVersionUpgradeDto preparation =
        versionUpgradePreparationService.getPreparedVersionUpgrade(
            tenantId, preparedVersionUpgradeId);
    if (!Long.valueOf(targetGameInstanceId).equals(preparation.executedTargetGameInstanceId())
        || !controlPlaneRequestId.equals(preparation.executionControlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "target_game_instance_id must differ from the current admission pointer target");
    }
    AdmissionPointerControlPlaneEntry entry =
        gameplayAdmissionPointerAuthorityService.listPointerAudit(worldSlug, realmSlug).stream()
            .findFirst()
            .map(this::toEntry)
            .orElseThrow(() -> new IllegalStateException("Admission pointer audit missing"));
    if (preparation.executedPointerVersion() != null
        && entry.getPointerVersion() != preparation.executedPointerVersion()) {
      throw new CutoverPreparationValidationException(
          "prepared_version_upgrade_id execution state does not match current admission pointer");
    }
    return entry;
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }

  private static final class CutoverPreparationValidationException extends RuntimeException {
    private CutoverPreparationValidationException(String message) {
      super(message);
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.purgeQueuedTickCommandsForScriptPatch")
  public void purgeQueuedTickCommandsForScriptPatch(
      PurgeQueuedTickCommandsForScriptPatchRequest request,
      StreamObserver<PurgeQueuedTickCommandsForScriptPatchResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      requireText(request.getScriptPatchVersion(), "script_patch_version is required");
      requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
      requireText(request.getActorPrincipal(), "actor_principal is required");
      requireInstanceOwner(tenantId, gameInstanceId);
      long purged =
          tickService.purgeQueuedAutomationCommandsForScriptPatch(
              tenantId,
              gameInstanceId,
              normalizeBlank(request.getRegionId()),
              request.getScriptPatchVersion(),
              request.getReason());
      PurgeQueuedTickCommandsForScriptPatchResponse response =
          PurgeQueuedTickCommandsForScriptPatchResponse.newBuilder().setPurgedCount(purged).build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      requireText(request.getPluginId(), "plugin_id is required");
      requireText(request.getPluginVersionId(), "plugin_version_id is required");
      requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
      requireText(request.getActorPrincipal(), "actor_principal is required");
      requireInstanceOwner(tenantId, gameInstanceId);
      long purged =
          tickService.purgeQueuedAutomationCommandsForPluginVersion(
              tenantId,
              gameInstanceId,
              normalizeBlank(request.getRegionId()),
              request.getPluginId(),
              request.getPluginVersionId(),
              request.getReason());
      PurgeQueuedTickCommandsForPluginVersionResponse response =
          PurgeQueuedTickCommandsForPluginVersionResponse.newBuilder()
              .setPurgedCount(purged)
              .build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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

  private void requireInstanceOwner(long tenantId, long gameInstanceId) {
    GameInstance instance = getInstanceOrThrow(gameInstanceId);
    if (instance.getTenantId() != tenantId) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }
  }

  private RuntimeOwnershipStatus toStatus(RuntimeRegionStatus status) {
    return RuntimeOwnershipStatus.newBuilder()
        .setTenantId(Long.toString(status.getTenantId()))
        .setGameInstanceId(Long.toString(status.getGameInstanceId()))
        .setRegionEpoch(status.getRegionEpoch())
        .setExecutorFence(status.getExecutorFence())
        .setOwnerService(status.getOwnerService())
        .setOwnerInstanceId(status.getOwnerInstanceId())
        .setPaused(status.isPaused())
        .setLastCommittedTickBatchId(
            status.getLastCommittedTickBatchId() == null
                ? ""
                : status.getLastCommittedTickBatchId())
        .setUpdatedAtMs(status.getUpdatedAt() == null ? 0L : status.getUpdatedAt().toEpochMilli())
        .build();
  }

  private CutoverParticipantResult toParticipantResult(
      net.firedevops.firemud.gamesession.dto.CutoverParticipantCompatibilityDto result) {
    return CutoverParticipantResult.newBuilder()
        .setParticipant(result.participant())
        .addAllStateClassesChecked(result.stateClassesChecked())
        .addAllCheckedFamilies(result.checkedFamilies())
        .setHasS2Rows(result.hasS2Rows())
        .setResult(toCutoverCompatibilityResult(result.result()))
        .addAllReasons(result.reasons())
        .build();
  }

  private PreparedVersionUpgrade toPreparedVersionUpgrade(
      net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto preparation) {
    PreparedVersionUpgrade.Builder builder =
        PreparedVersionUpgrade.newBuilder()
            .setPreparationId(preparation.preparationId())
            .setControlPlaneRequestId(preparation.controlPlaneRequestId())
            .setTenantId(Long.toString(preparation.tenantId()))
            .setSourceGameInstanceId(Long.toString(preparation.sourceGameInstanceId()))
            .setSourceVersionId(Long.toString(preparation.sourceVersionId()))
            .setTargetVersionId(Long.toString(preparation.targetVersionId()))
            .setTargetLaunchDescriptorId(preparation.targetLaunchDescriptorId())
            .setResult(toCutoverCompatibilityResult(preparation.result()))
            .addAllReasons(preparation.reasons())
            .addAllCheckedParticipants(preparation.checkedParticipants())
            .setCheckedAtMs(preparation.checkedAt().toEpochMilli())
            .addAllParticipantResults(
                preparation.participantResults().stream().map(this::toParticipantResult).toList());
    if (preparation.remapSetId() != null) {
      builder.setRemapSetId(preparation.remapSetId());
    }
    if (preparation.executedTargetGameInstanceId() != null) {
      builder.setExecutedTargetGameInstanceId(
          Long.toString(preparation.executedTargetGameInstanceId()));
    }
    if (preparation.executedPointerVersion() != null) {
      builder.setExecutedPointerVersion(preparation.executedPointerVersion());
    }
    if (preparation.executedAt() != null) {
      builder.setExecutedAtMs(preparation.executedAt().toEpochMilli());
    }
    if (preparation.executionControlPlaneRequestId() != null) {
      builder.setExecutionControlPlaneRequestId(preparation.executionControlPlaneRequestId());
    }
    return builder.build();
  }

  private CutoverCompatibilityResult toCutoverCompatibilityResult(String result) {
    return switch (result) {
      case "COMPATIBLE" -> CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_COMPATIBLE;
      case "INCOMPATIBLE" -> CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_INCOMPATIBLE;
      case "UNAVAILABLE" -> CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_UNAVAILABLE;
      default -> CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_UNSPECIFIED;
    };
  }

  private GameplayCommandStatus toStatus(GameplayCommand command) {
    GameplayCommandStatus.Builder builder =
        GameplayCommandStatus.newBuilder()
            .setCommandId(command.getCommandId())
            .setTenantId(command.getTenantId().toString())
            .setGameInstanceId(command.getGameInstanceId().toString())
            .setSessionId(command.getSessionId().toString())
            .setCommandName(command.getCommandName())
            .setSanitizedCommandText(command.getSanitizedCommandText())
            .setRequiresSoloTick(command.isRequiresSoloTick())
            .setExecutionOutcome(command.getExecutionOutcome())
            .setGameplayResult(command.getGameplayResult())
            .setAcceptedAtMs(toEpochMillis(command.getAcceptedAt()))
            .setLastAttemptAtMs(toEpochMillis(command.getLastAttemptAt()))
            .setAttemptCount(command.getAttemptCount());
    if (command.getAccountId() != null) {
      builder.setAccountId(command.getAccountId().toString());
    }
    if (command.getCharacterId() != null) {
      builder.setCharacterId(command.getCharacterId().toString());
    }
    if (command.getStagedAt() != null) {
      builder.setStagedAtMs(toEpochMillis(command.getStagedAt()));
    }
    if (command.getCompletedAt() != null) {
      builder.setCompletedAtMs(toEpochMillis(command.getCompletedAt()));
    }
    if (command.getFailureCode() != null) {
      builder.setFailureCode(command.getFailureCode());
    }
    if (command.getFailureMessage() != null) {
      builder.setFailureMessage(command.getFailureMessage());
    }
    if (command.getSourceType() != null) {
      builder.setSourceType(command.getSourceType());
    }
    if (command.getAutomationDispatchId() != null) {
      builder.setAutomationDispatchId(command.getAutomationDispatchId());
    }
    if (command.getAutomationWorkItemId() != null) {
      builder.setAutomationWorkItemId(command.getAutomationWorkItemId());
    }
    if (command.getScriptId() != null) {
      builder.setScriptId(command.getScriptId());
    }
    if (command.getScriptPatchVersion() != null) {
      builder.setScriptPatchVersion(command.getScriptPatchVersion());
    }
    if (command.getTargetEntityId() != null) {
      builder.setTargetEntityId(command.getTargetEntityId());
    }
    if (command.getRegionId() != null) {
      builder.setRegionId(command.getRegionId());
    }
    if (command.getRegionEpoch() != null) {
      builder.setRegionEpoch(command.getRegionEpoch());
    }
    if (command.getDueTickId() != null) {
      builder.setDueTickId(command.getDueTickId());
    }
    return builder.build();
  }

  private long toEpochMillis(Instant instant) {
    return instant == null ? 0L : instant.toEpochMilli();
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.pauseTicksForScope")
  public void pauseTicksForScope(
      PauseTicksForScopeRequest request,
      StreamObserver<PauseTicksForScopeResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      if (!request.getRegionId().isBlank()) {
        throw new IllegalArgumentException("region_id is not supported; set it empty");
      }
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      tickService.pauseTicksForGameInstance(gameInstanceId, request.getReason());
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = parseTenantId(request.getTenantId());
      if (!request.getRegionId().isBlank()) {
        throw new IllegalArgumentException("region_id is not supported; set it empty");
      }
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      tickService.resumeTicksForGameInstance(gameInstanceId, request.getReason());
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
