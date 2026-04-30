package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.command.text.BuiltInTextCommandAliasResolver;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupResultRepository;
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
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorRequest;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorResponse;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.PreparedVersionUpgrade;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchResponse;
import net.firedevops.firemud.gamesession.v1.RemoteCommandCoordinatorEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupResultEntry;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
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
  private static final List<String> ACTIVE_GAMEPLAY_COMMAND_OUTCOMES =
      List.of("ACCEPTED", "STAGED", "RETRY_QUEUED", "DRAINED");

  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private final RemoteFollowupResultRepository remoteFollowupResultRepository;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final InstanceCutoverCompatibilityService instanceCutoverCompatibilityService;
  private final VersionUpgradePreparationService versionUpgradePreparationService;
  private final TickService tickService;
  private final BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver;
  private final MeterRegistry meterRegistry;
  private final GameSessionProperties gameSessionProperties;

  @Autowired
  public GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService,
      BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver,
      TickService tickService,
      MeterRegistry meterRegistry) {
    this(
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        null,
        null,
        null,
        gameplayAdmissionPointerAuthorityService,
        instanceCutoverCompatibilityService,
        versionUpgradePreparationService,
        builtInTextCommandAliasResolver,
        tickService,
        meterRegistry,
        new GameSessionProperties());
  }

  public GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService,
      BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver,
      TickService tickService,
      MeterRegistry meterRegistry,
      GameSessionProperties gameSessionProperties) {
    this(
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        null,
        null,
        null,
        gameplayAdmissionPointerAuthorityService,
        instanceCutoverCompatibilityService,
        versionUpgradePreparationService,
        builtInTextCommandAliasResolver,
        tickService,
        meterRegistry,
        gameSessionProperties);
  }

  public GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService,
      BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver,
      TickService tickService,
      MeterRegistry meterRegistry) {
    this(
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        remoteFollowupRepository,
        remoteCommandCoordinatorRepository,
        remoteFollowupResultRepository,
        gameplayAdmissionPointerAuthorityService,
        instanceCutoverCompatibilityService,
        versionUpgradePreparationService,
        builtInTextCommandAliasResolver,
        tickService,
        meterRegistry,
        new GameSessionProperties());
  }

  public GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService,
      BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver,
      TickService tickService,
      MeterRegistry meterRegistry,
      GameSessionProperties gameSessionProperties) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
    this.remoteFollowupResultRepository = remoteFollowupResultRepository;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.instanceCutoverCompatibilityService = instanceCutoverCompatibilityService;
    this.versionUpgradePreparationService = versionUpgradePreparationService;
    this.builtInTextCommandAliasResolver = builtInTextCommandAliasResolver;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
    this.gameSessionProperties = gameSessionProperties;
  }

  GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService,
      TickService tickService,
      MeterRegistry meterRegistry) {
    this(
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        null,
        null,
        null,
        gameplayAdmissionPointerAuthorityService,
        instanceCutoverCompatibilityService,
        versionUpgradePreparationService,
        tickService,
        meterRegistry,
        new GameSessionProperties());
  }

  GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      InstanceCutoverCompatibilityService instanceCutoverCompatibilityService,
      VersionUpgradePreparationService versionUpgradePreparationService,
      TickService tickService,
      MeterRegistry meterRegistry,
      GameSessionProperties gameSessionProperties) {
    this(
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        remoteFollowupRepository,
        remoteCommandCoordinatorRepository,
        remoteFollowupResultRepository,
        gameplayAdmissionPointerAuthorityService,
        instanceCutoverCompatibilityService,
        versionUpgradePreparationService,
        BuiltInTextCommandAliasResolver.unsupported(),
        tickService,
        meterRegistry,
        gameSessionProperties);
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
      GameplayCommand command = findGameplayCommandStatus(request);
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

  private GameplayCommand findGameplayCommandStatus(GetGameplayCommandStatusRequest request) {
    if (!request.getCommandId().isBlank()) {
      return gameplayCommandRepository
          .findByCommandId(request.getCommandId())
          .orElseThrow(() -> new IllegalArgumentException("Gameplay command not found"));
    }
    long tenantId = parseTenantId(request.getTenantId());
    long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
    requireText(request.getRegionId(), "region_id is required");
    if (request.getRegionEpoch() <= 0) {
      throw new IllegalArgumentException("region_epoch must be positive");
    }
    requireText(request.getAutomationDispatchId(), "automation_dispatch_id is required");
    return gameplayCommandRepository
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
            tenantId,
            gameInstanceId,
            request.getRegionId(),
            request.getRegionEpoch(),
            request.getAutomationDispatchId())
        .orElseThrow(() -> new IllegalArgumentException("Gameplay command not found"));
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getRuntimeOwnershipStatus")
  public void getRuntimeOwnershipStatus(
      GetRuntimeOwnershipStatusRequest request,
      StreamObserver<GetRuntimeOwnershipStatusResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      RuntimeRegionStatus status = findRuntimeOwnershipStatus(request, tenantId);
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
  @Timed(value = "gamesessionGrpc.controlPlane.getRemoteCommandCoordinator")
  public void getRemoteCommandCoordinator(
      GetRemoteCommandCoordinatorRequest request,
      StreamObserver<GetRemoteCommandCoordinatorResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      requireText(request.getCoordinatorId(), "coordinator_id is required");
      RemoteCommandCoordinator coordinator =
          remoteCommandCoordinatorRepository
              .findByTenantIdAndCoordinatorId(tenantId, request.getCoordinatorId())
              .orElseThrow(
                  () -> new IllegalArgumentException("Remote command coordinator not found"));
      responseObserver.onNext(
          GetRemoteCommandCoordinatorResponse.newBuilder()
              .setCoordinator(toRemoteCoordinatorEntry(coordinator))
              .build());
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetRemoteCommandCoordinatorResponse.newBuilder()
              .setError(authorizationError("GetRemoteCommandCoordinator", ex))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetRemoteCommandCoordinatorResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
  @Timed(value = "gamesessionGrpc.controlPlane.listRemoteFollowups")
  public void listRemoteFollowups(
      ListRemoteFollowupsRequest request,
      StreamObserver<ListRemoteFollowupsResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      requireText(request.getTargetRegionId(), "target_region_id is required");
      java.util.List<RemoteFollowup> followups =
          request.getStatus().isBlank()
              ? remoteFollowupRepository.findByTenantIdAndTargetRegionIdOrderByDueTickIdAsc(
                  tenantId, request.getTargetRegionId())
              : remoteFollowupRepository
                  .findByTenantIdAndTargetRegionIdAndStatusOrderByDueTickIdAsc(
                      tenantId, request.getTargetRegionId(), request.getStatus());
      ListRemoteFollowupsResponse.Builder response = ListRemoteFollowupsResponse.newBuilder();
      followups.forEach(followup -> response.addFollowups(toRemoteFollowupEntry(followup)));
      responseObserver.onNext(response.build());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = parseTenantId(request.getTenantId());
      requireText(request.getCoordinatorId(), "coordinator_id is required");
      java.util.List<RemoteFollowupResult> results =
          remoteFollowupResultRepository.findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
              tenantId, request.getCoordinatorId());
      ListRemoteFollowupResultsResponse.Builder response =
          ListRemoteFollowupResultsResponse.newBuilder();
      results.forEach(result -> response.addResults(toRemoteFollowupResultEntry(result)));
      responseObserver.onNext(response.build());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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

  private RuntimeRegionStatus findRuntimeOwnershipStatus(
      GetRuntimeOwnershipStatusRequest request, long tenantId) {
    if (!request.getRegionId().isBlank()) {
      return runtimeRegionStatusRepository
          .findByTenantIdAndRegionId(tenantId, request.getRegionId())
          .orElseThrow(() -> new IllegalArgumentException("Runtime ownership not found"));
    }
    long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
    return runtimeRegionStatusRepository
        .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Runtime ownership not found"));
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
              request.getPublicProductionRealm(),
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
              currentPointer.publicProductionRealm(),
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
              .setObservedAtMs(toEpochMillis(instance.getScriptPatchPinnedAt()))
              .setIsStale(isPinConvergenceStale(instance.getScriptPatchPinnedAt()))
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
      GameplayRoutingBundle routingBundle = resolveGameplayRouting(instance);
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
                      .setPlayableStateScope(routingBundle.playableStateScope())
                      .setWorldSlug(routingBundle.worldSlug())
                      .setRealmSlug(routingBundle.realmSlug())
                      .setPointerVersion(routingBundle.pointerVersion())
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
  @Timed(value = "gamesessionGrpc.controlPlane.validateBuiltInCommandAlias")
  public void validateBuiltInCommandAlias(
      ValidateBuiltInCommandAliasRequest request,
      StreamObserver<ValidateBuiltInCommandAliasResponse> responseObserver) {
    try {
      requireAdminRole();
      String alias = request.getAlias();
      if (alias == null || alias.isBlank()) {
        throw new IllegalArgumentException("alias is required");
      }
      ValidateBuiltInCommandAliasResponse response =
          builtInTextCommandAliasResolver
              .resolve(alias)
              .map(
                  normalized ->
                      ValidateBuiltInCommandAliasResponse.newBuilder()
                          .setSupported(true)
                          .setNormalizedAlias(normalized)
                          .build())
              .orElseGet(() -> ValidateBuiltInCommandAliasResponse.newBuilder().build());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
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
    Optional<EnqueueAutomationCommandIfAbsentResponse> rejected =
        rejectIfAutomationOwnershipClosed(request, tenantId, gameInstanceId);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    GameplayCommand command = acceptedAutomationCommand(request, tenantId, gameInstanceId);
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

  private Optional<EnqueueAutomationCommandIfAbsentResponse> rejectIfAutomationOwnershipClosed(
      EnqueueAutomationCommandIfAbsentRequest request, long tenantId, long gameInstanceId) {
    Optional<RuntimeRegionStatus> maybeStatus =
        runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    if (maybeStatus.isEmpty()) {
      return Optional.of(
          rejectedAutomationCommand(
              "OWNERSHIP_UNAVAILABLE",
              "runtime_ownership_not_found",
              "Runtime ownership not found"));
    }
    RuntimeRegionStatus status = maybeStatus.get();
    if (status.getRegionEpoch() != request.getRegionEpoch()) {
      return Optional.of(
          rejectedAutomationCommand(
              "STALE_TIMELINE",
              "stale_region_epoch",
              "region_epoch does not match current runtime ownership"));
    }
    if (status.isPaused()) {
      return Optional.of(
          rejectedAutomationCommand(
              "RUNTIME_PAUSED", "runtime_paused", "Runtime ownership is paused"));
    }
    return Optional.empty();
  }

  private EnqueueAutomationCommandIfAbsentResponse rejectedAutomationCommand(
      String admissionOutcome, String errorCode, String message) {
    return EnqueueAutomationCommandIfAbsentResponse.newBuilder()
        .setAccepted(false)
        .setAdmissionOutcome(admissionOutcome)
        .setError(GrpcAppErrors.error(meterRegistry, errorCode, message))
        .build();
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
    command.setPlayableStateScope(normalizePlayableStateScope(request.getPlayableStateScope()));
    command.setWorldSlug(normalizeBlank(request.getWorldSlug()));
    command.setRealmSlug(normalizeBlank(request.getRealmSlug()));
    command.setPointerVersion(parsePointerVersionClaim(request.getPointerVersion()));
    command.setOriginSourceKind(normalizeBlank(request.getOriginSourceKind()));
    command.setOriginSourceState(normalizeBlank(request.getOriginSourceState()));
    command.setOriginSourceOrdinal(
        request.getOriginSourceOrdinal() > 0 ? request.getOriginSourceOrdinal() : null);
    command.setOriginSourceDueTickId(
        request.getOriginSourceDueTickId() > 0 ? request.getOriginSourceDueTickId() : null);
    command.setOriginSourceDueAtMs(
        request.getOriginSourceDueAtMs() > 0 ? request.getOriginSourceDueAtMs() : null);
    command.setTargetEntityId(request.getTargetEntityId());
    command.setCharacterId(parseGameplayCharacterId(request.getTargetEntityId()));
    command.setRegionId(request.getRegionId());
    command.setRegionEpoch(request.getRegionEpoch());
    command.setDueTickId(request.getDueTickId() > 0 ? request.getDueTickId() : null);
    return gameplayCommandRepository.save(command);
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
            .setPublicProductionRealm(entry.publicProductionRealm())
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

  private GameplayRoutingBundle resolveGameplayRouting(GameInstance instance) {
    return gameplayAdmissionPointerAuthorityService
        .findByRuntimeTarget(instance.getTenantId(), instance.getId())
        .map(
            pointer ->
                new GameplayRoutingBundle(
                    switch (normalizeBlank(pointer.stateScope())) {
                      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
                      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
                      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
                    },
                    normalizeBlank(pointer.worldSlug()),
                    normalizeBlank(pointer.realmSlug()),
                    pointer.pointerVersion()))
        .orElse(
            new GameplayRoutingBundle(
                PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED, "", "", 0L));
  }

  private static String normalizePlayableStateScope(PlayableStateScope playableStateScope) {
    if (playableStateScope == null) {
      return "";
    }
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED -> "";
    };
  }

  private static Long parsePointerVersionClaim(String pointerVersion) {
    if (pointerVersion == null || pointerVersion.isBlank()) {
      return null;
    }
    return Long.parseLong(pointerVersion);
  }

  private static Long parseGameplayCharacterId(String targetEntityId) {
    if (targetEntityId == null || targetEntityId.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(targetEntityId);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private record GameplayRoutingBundle(
      PlayableStateScope playableStateScope,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {}

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
    long pendingGameplayCommandCount =
        gameplayCommandRepository
            .countByTenantIdAndGameInstanceIdAndCompletedAtIsNullAndExecutionOutcomeIn(
                status.getTenantId(), status.getGameInstanceId(), ACTIVE_GAMEPLAY_COMMAND_OUTCOMES);
    long dueRemoteFollowupCount =
        remoteFollowupRepository.countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
            status.getTenantId(),
            status.getRegionId(),
            RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
            status.getLastCommittedTickId() + 1L);
    long oldestDueRemoteFollowupTickId =
        remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                status.getTenantId(),
                status.getRegionId(),
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                status.getLastCommittedTickId() + 1L)
            .map(RemoteFollowup::getDueTickId)
            .orElse(0L);
    return RuntimeOwnershipStatus.newBuilder()
        .setTenantId(Long.toString(status.getTenantId()))
        .setGameInstanceId(Long.toString(status.getGameInstanceId()))
        .setRegionId(status.getRegionId() == null ? "" : status.getRegionId())
        .setRegionEpoch(status.getRegionEpoch())
        .setExecutorFence(status.getExecutorFence())
        .setOwnerService(status.getOwnerService())
        .setOwnerInstanceId(status.getOwnerInstanceId())
        .setPaused(status.isPaused())
        .setLastCommittedTickBatchId(
            status.getLastCommittedTickBatchId() == null
                ? ""
                : status.getLastCommittedTickBatchId())
        .setLastCommittedTickId(status.getLastCommittedTickId())
        .setUpdatedAtMs(status.getUpdatedAt() == null ? 0L : status.getUpdatedAt().toEpochMilli())
        .setPendingGameplayCommandCount(pendingGameplayCommandCount)
        .setDueRemoteFollowupCount(dueRemoteFollowupCount)
        .setOldestDueRemoteFollowupTickId(oldestDueRemoteFollowupTickId)
        .build();
  }

  private RemoteCommandCoordinatorEntry toRemoteCoordinatorEntry(
      RemoteCommandCoordinator coordinator) {
    RemoteCommandCoordinatorEntry.Builder builder =
        RemoteCommandCoordinatorEntry.newBuilder()
            .setCoordinatorId(coordinator.getCoordinatorId())
            .setTenantId(Long.toString(coordinator.getTenantId()))
            .setCommandId(coordinator.getCommandId())
            .setFollowupId(coordinator.getFollowupId())
            .setOriginGameInstanceId(Long.toString(coordinator.getOriginGameInstanceId()))
            .setOriginRegionId(coordinator.getOriginRegionId())
            .setOriginRegionEpoch(coordinator.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(coordinator.getTargetGameInstanceId()))
            .setTargetRegionId(coordinator.getTargetRegionId())
            .setTargetRegionEpoch(coordinator.getTargetRegionEpoch())
            .setTargetDueTickId(coordinator.getTargetDueTickId())
            .setOriginDeadlineRegionEpoch(coordinator.getOriginDeadlineRegionEpoch())
            .setOriginDeadlineTickId(coordinator.getOriginDeadlineTickId())
            .setState(coordinator.getState())
            .setLateResultPolicy(coordinator.getLateResultPolicy())
            .setUpdatedAtMs(
                coordinator.getUpdatedAt() == null
                    ? 0L
                    : coordinator.getUpdatedAt().toEpochMilli());
    if (coordinator.getExecutionOutcome() != null) {
      builder.setExecutionOutcome(coordinator.getExecutionOutcome());
    }
    if (coordinator.getGameplayResult() != null) {
      builder.setGameplayResult(coordinator.getGameplayResult());
    }
    return builder.build();
  }

  private RemoteFollowupEntry toRemoteFollowupEntry(RemoteFollowup followup) {
    RemoteFollowupEntry.Builder builder =
        RemoteFollowupEntry.newBuilder()
            .setFollowupId(followup.getFollowupId())
            .setTenantId(Long.toString(followup.getTenantId()))
            .setOriginGameInstanceId(Long.toString(followup.getOriginGameInstanceId()))
            .setOriginRegionId(followup.getOriginRegionId())
            .setOriginRegionEpoch(followup.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(followup.getTargetGameInstanceId()))
            .setTargetRegionId(followup.getTargetRegionId())
            .setTargetRegionEpoch(followup.getTargetRegionEpoch())
            .setDueTickId(followup.getDueTickId())
            .setEffectKey(followup.getEffectKey())
            .setStatus(followup.getStatus())
            .setCreatedAtMs(
                followup.getCreatedAt() == null ? 0L : followup.getCreatedAt().toEpochMilli())
            .setUpdatedAtMs(
                followup.getUpdatedAt() == null ? 0L : followup.getUpdatedAt().toEpochMilli());
    if (followup.getTargetEntityId() != null) {
      builder.setTargetEntityId(followup.getTargetEntityId());
    }
    if (followup.getClaimedTickBatchId() != null) {
      builder.setClaimedTickBatchId(followup.getClaimedTickBatchId());
    }
    if (followup.getPayloadJson() != null) {
      builder.setPayloadJson(followup.getPayloadJson());
    }
    if (followup.getFailureCode() != null) {
      builder.setFailureCode(followup.getFailureCode());
    }
    if (followup.getFailureMessage() != null) {
      builder.setFailureMessage(followup.getFailureMessage());
    }
    return builder.build();
  }

  private RemoteFollowupResultEntry toRemoteFollowupResultEntry(RemoteFollowupResult result) {
    RemoteFollowupResultEntry.Builder builder =
        RemoteFollowupResultEntry.newBuilder()
            .setResultId(result.getResultId())
            .setTenantId(Long.toString(result.getTenantId()))
            .setCoordinatorId(result.getCoordinatorId())
            .setFollowupId(result.getFollowupId())
            .setOriginRegionId(result.getOriginRegionId())
            .setOriginRegionEpoch(result.getOriginRegionEpoch())
            .setTargetRegionId(result.getTargetRegionId())
            .setTargetRegionEpoch(result.getTargetRegionEpoch())
            .setOutcome(result.getOutcome())
            .setObservedAtMs(
                result.getObservedAt() == null ? 0L : result.getObservedAt().toEpochMilli());
    if (result.getResultPayloadJson() != null) {
      builder.setResultPayloadJson(result.getResultPayloadJson());
    }
    return builder.build();
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
            .setAttemptCount(command.getAttemptCount())
            .setPlayableStateScope(toPlayableStateScopeStatus(command.getPlayableStateScope()));
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
    if (command.getPluginId() != null) {
      builder.setPluginId(command.getPluginId());
    }
    if (command.getPluginVersionId() != null) {
      builder.setPluginVersionId(command.getPluginVersionId());
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
    if (command.getEnqueueSeq() != null) {
      builder.setEnqueueSeq(command.getEnqueueSeq());
    }
    if (command.getWorldSlug() != null) {
      builder.setWorldSlug(command.getWorldSlug());
    }
    if (command.getRealmSlug() != null) {
      builder.setRealmSlug(command.getRealmSlug());
    }
    if (command.getPointerVersion() != null) {
      builder.setPointerVersion(command.getPointerVersion());
    }
    if (command.getOriginSourceKind() != null) {
      builder.setOriginSourceKind(command.getOriginSourceKind());
    }
    if (command.getOriginSourceState() != null) {
      builder.setOriginSourceState(command.getOriginSourceState());
    }
    if (command.getOriginSourceOrdinal() != null) {
      builder.setOriginSourceOrdinal(command.getOriginSourceOrdinal());
    }
    if (command.getOriginSourceDueTickId() != null) {
      builder.setOriginSourceDueTickId(command.getOriginSourceDueTickId());
    }
    if (command.getOriginSourceDueAtMs() != null) {
      builder.setOriginSourceDueAtMs(command.getOriginSourceDueAtMs());
    }
    if (command.getQueueSourceKind() != null) {
      builder.setQueueSourceKind(command.getQueueSourceKind());
    }
    if (command.getQueueSourceState() != null) {
      builder.setQueueSourceState(command.getQueueSourceState());
    }
    if (command.getQueueSourceOrdinal() != null) {
      builder.setQueueSourceOrdinal(command.getQueueSourceOrdinal());
    }
    if (command.getQueueSourceDueTickId() != null) {
      builder.setQueueSourceDueTickId(command.getQueueSourceDueTickId());
    }
    if (command.getQueueSourceDueAtMs() != null) {
      builder.setQueueSourceDueAtMs(command.getQueueSourceDueAtMs());
    }
    return builder.build();
  }

  private static PlayableStateScope toPlayableStateScopeStatus(String playableStateScope) {
    if (playableStateScope == null || playableStateScope.isBlank()) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    }
    return switch (playableStateScope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private long toEpochMillis(Instant instant) {
    return instant == null ? 0L : instant.toEpochMilli();
  }

  private boolean isPinConvergenceStale(Instant pinnedAt) {
    if (pinnedAt == null) {
      return true;
    }
    long ageMs = Instant.now().toEpochMilli() - pinnedAt.toEpochMilli();
    return ageMs > gameSessionProperties.getPinConvergenceStaleThresholdMs();
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
