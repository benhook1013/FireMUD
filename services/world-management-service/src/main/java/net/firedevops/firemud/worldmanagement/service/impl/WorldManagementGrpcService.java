package net.firedevops.firemud.worldmanagement.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldDto;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto.RoomExitSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RuntimeRoomDto;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationRequest;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationResponse;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHoldStatus;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RuntimeRoom;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.UpgradeValidationResult;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsRequest;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** gRPC endpoints for the World Management Service. */
@GrpcService
public class WorldManagementGrpcService
    extends WorldManagementServiceGrpc.WorldManagementServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(WorldManagementGrpcService.class);
  private final PingService pingService;
  private final RoomService roomService;
  private final WorldInstanceActivationService worldInstanceActivationService;
  private final WorldDraftDesignDigestService worldDraftDesignDigestService;
  private final WorldUpgradeValidationService worldUpgradeValidationService;
  private final GameplaySessionAttestationService gameplaySessionAttestationService;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final PublicationReadGuard publicationReadGuard;
  private InitialAdmissionBindHoldService initialAdmissionBindHoldService;
  private InitialAdmissionBindWorkloadGuard initialAdmissionBindWorkloadGuard;

  private WorldManagementGrpcService(
      PublicationReadGuard publicationReadGuard,
      PingService pingService,
      RoomService roomService,
      WorldInstanceActivationService worldInstanceActivationService,
      WorldDraftDesignDigestService worldDraftDesignDigestService,
      WorldDesignMutationService worldDesignMutationService,
      WorldUpgradeValidationService worldUpgradeValidationService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper) {
    this.pingService = pingService;
    this.roomService = roomService;
    this.worldInstanceActivationService = worldInstanceActivationService;
    this.worldDraftDesignDigestService = worldDraftDesignDigestService;
    this.worldUpgradeValidationService = worldUpgradeValidationService;
    this.gameplaySessionAttestationService = gameplaySessionAttestationService;
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
    this.publicationReadGuard = publicationReadGuard;
  }

  @Autowired
  public WorldManagementGrpcService(
      PingService pingService,
      RoomService roomService,
      WorldInstanceActivationService worldInstanceActivationService,
      WorldDraftDesignDigestService worldDraftDesignDigestService,
      WorldDesignMutationService worldDesignMutationService,
      WorldUpgradeValidationService worldUpgradeValidationService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this(
        PublicationReadGuard.configured(workloadNamespace),
        pingService,
        roomService,
        worldInstanceActivationService,
        worldDraftDesignDigestService,
        worldDesignMutationService,
        worldUpgradeValidationService,
        gameplaySessionAttestationService,
        meterRegistry,
        objectMapper);
  }

  @Autowired
  public void configureInitialAdmissionBindHoldBoundary(
      InitialAdmissionBindHoldService holdService,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.initialAdmissionBindHoldService = holdService;
    this.initialAdmissionBindWorkloadGuard =
        new InitialAdmissionBindWorkloadGuard(workloadNamespace);
  }

  public WorldManagementGrpcService(
      PingService pingService,
      RoomService roomService,
      WorldInstanceActivationService worldInstanceActivationService,
      WorldDraftDesignDigestService worldDraftDesignDigestService,
      WorldDesignMutationService worldDesignMutationService,
      WorldUpgradeValidationService worldUpgradeValidationService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper,
      PublicationReadGuard publicationReadGuard) {
    this(
        publicationReadGuard,
        pingService,
        roomService,
        worldInstanceActivationService,
        worldDraftDesignDigestService,
        worldDesignMutationService,
        worldUpgradeValidationService,
        gameplaySessionAttestationService,
        meterRegistry,
        objectMapper);
  }

  @Override
  @Timed(value = "worldGrpc.prepareWorldInstance")
  public void prepareWorldInstance(
      PrepareWorldInstanceRequest request,
      StreamObserver<PrepareWorldInstanceResponse> responseObserver) {
    PrepareWorldInstanceResponse.Builder builder = PrepareWorldInstanceResponse.newBuilder();
    try {
      var snapshot =
          worldInstanceActivationService.prepareWorldInstance(
              new PreparedWorldInstanceRequest(
                  RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
                  RequestIdValidation.requirePositiveLong(
                      request.getGameInstanceId(), "gameInstanceId"),
                  RequestIdValidation.requirePositiveLong(
                      request.getGameTemplateId(), "gameTemplateId"),
                  request.getControlPlaneRequestId(),
                  request.getLaunchDescriptorId(),
                  RequestIdValidation.requirePositiveLong(request.getVersionId(), "versionId"),
                  request.getScriptPatchVersion(),
                  request.getRuntimeFlagsJson(),
                  request.getGenerationConfigRevision(),
                  RequestIdValidation.requirePositiveLong(
                      request.getReleaseBundleId(), "releaseBundleId"),
                  request.getPublishedReleaseBundleRef(),
                  request.getVersionStateEpoch(),
                  request.getRemapSetId().isBlank() ? null : request.getRemapSetId()));
      builder.setWorldInstance(toProto(snapshot));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "PrepareWorldInstance", errorCodeFor(ex), ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "PrepareWorldInstance", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.activatePreparedWorldInstance")
  public void activatePreparedWorldInstance(
      ActivatePreparedWorldInstanceRequest request,
      StreamObserver<ActivatePreparedWorldInstanceResponse> responseObserver) {
    ActivatePreparedWorldInstanceResponse.Builder builder =
        ActivatePreparedWorldInstanceResponse.newBuilder();
    try {
      var snapshot =
          worldInstanceActivationService.activatePreparedWorldInstance(
              RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
              RequestIdValidation.requirePositiveLong(
                  request.getGameInstanceId(), "gameInstanceId"),
              request.getExpectedLifecycleEpoch());
      builder.setWorldInstance(toProto(snapshot));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ActivatePreparedWorldInstance",
              errorCodeFor(ex),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "ActivatePreparedWorldInstance", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.failPreparedWorldInstance")
  public void failPreparedWorldInstance(
      FailPreparedWorldInstanceRequest request,
      StreamObserver<FailPreparedWorldInstanceResponse> responseObserver) {
    FailPreparedWorldInstanceResponse.Builder builder =
        FailPreparedWorldInstanceResponse.newBuilder();
    try {
      var snapshot =
          worldInstanceActivationService.failPreparedWorldInstance(
              RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
              RequestIdValidation.requirePositiveLong(
                  request.getGameInstanceId(), "gameInstanceId"),
              request.getExpectedLifecycleEpoch(),
              request.getReason());
      builder.setWorldInstance(toProto(snapshot));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "FailPreparedWorldInstance",
              errorCodeFor(ex),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "FailPreparedWorldInstance", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.getWorldInstanceLifecycle")
  public void getWorldInstanceLifecycle(
      GetWorldInstanceLifecycleRequest request,
      StreamObserver<GetWorldInstanceLifecycleResponse> responseObserver) {
    GetWorldInstanceLifecycleResponse.Builder builder =
        GetWorldInstanceLifecycleResponse.newBuilder();
    try {
      builder.setWorldInstance(
          toProto(
              worldInstanceActivationService.getWorldInstanceLifecycle(
                  RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
                  RequestIdValidation.requirePositiveLong(
                      request.getGameInstanceId(), "gameInstanceId"))));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetWorldInstanceLifecycle",
              errorCodeFor(ex),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "GetWorldInstanceLifecycle", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.acquireInitialAdmissionBindHold")
  public void acquireInitialAdmissionBindHold(
      AcquireInitialAdmissionBindHoldRequest request,
      StreamObserver<AcquireInitialAdmissionBindHoldResponse> responseObserver) {
    AcquireInitialAdmissionBindHoldResponse.Builder builder =
        AcquireInitialAdmissionBindHoldResponse.newBuilder();
    try {
      if (initialAdmissionBindHoldService == null || initialAdmissionBindWorkloadGuard == null) {
        throw new AdminAuthorizationException(
            "Initial admission hold authorization is not configured");
      }
      initialAdmissionBindWorkloadGuard.requireGameSessionAcquireCaller();
      var hold =
          initialAdmissionBindHoldService.acquire(
              new InitialAdmissionBindHoldRequest(
                  RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
                  RequestIdValidation.requirePositiveLong(
                      request.getGameInstanceId(), "gameInstanceId"),
                  RequestIdValidation.requirePositiveLong(request.getVersionId(), "versionId"),
                  request.getExpectedActiveLifecycleEpoch(),
                  request.getInitialAdmissionRequestId(),
                  request.getRequestDigest(),
                  request.getRealmUuid(),
                  request.getPlayableStateNamespaceUuid(),
                  normalizePlayableStateScope(request.getPlayableStateScope()),
                  request.getExpectedNoPriorPointer(),
                  request.getExpectedCatalogRevision()));
      builder.setHold(toProto(hold));
    } catch (AdminAuthorizationException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "AcquireInitialAdmissionBindHold",
              "PERMISSION_DENIED",
              ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "AcquireInitialAdmissionBindHold",
              errorCodeFor(ex),
              errorMessageFor(ex)));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "AcquireInitialAdmissionBindHold", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.terminateWorldInstance")
  public void terminateWorldInstance(
      TerminateWorldInstanceRequest request,
      StreamObserver<TerminateWorldInstanceResponse> responseObserver) {
    TerminateWorldInstanceResponse.Builder builder = TerminateWorldInstanceResponse.newBuilder();
    try {
      builder.setWorldInstance(
          toProto(
              worldInstanceActivationService.terminateWorldInstance(
                  RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
                  RequestIdValidation.requirePositiveLong(
                      request.getGameInstanceId(), "gameInstanceId"),
                  request.getExpectedLifecycleEpoch(),
                  request.getTerminationRequestId(),
                  request.getReason())));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "TerminateWorldInstance", errorCodeFor(ex), ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "TerminateWorldInstance", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.getDraftDesignDigest")
  public void getDraftDesignDigest(
      GetDraftDesignDigestRequest request,
      StreamObserver<GetDraftDesignDigestResponse> responseObserver) {
    try {
      requirePublicationRead();
      if (request.getScopeCase() != GetDraftDesignDigestRequest.ScopeCase.VERSION_ID) {
        responseObserver.onNext(
            GetDraftDesignDigestResponse.newBuilder()
                .setError(
                    GrpcAppErrors.error(
                        meterRegistry,
                        logger,
                        "GetDraftDesignDigest",
                        "UNSUPPORTED_SCOPE",
                        "world management supports version_id scope only"))
                .build());
        responseObserver.onCompleted();
        return;
      }
      PublicationDigestRequestBinding binding =
          PublicationDigestRequestBinding.forScope(
              PublicationDigestRequestBinding.ScopeKind.FULL_VERSION,
              request.getTenantId(),
              request.getVersionId(),
              request.getBaseVersionId(),
              request.getScriptPatchVersion(),
              request.getPublishRequestId());
      binding.validateSupplied(request.getDerivedWorkflowIdentity(), request.getRequestDigest());
      var digest =
          worldDraftDesignDigestService.getDraftDesignDigest(
              request.getTenantId(), request.getVersionId());
      binding.requireOwnerScope(digest.tenantId(), digest.scopeValue());
      GetDraftDesignDigestResponse.Builder response =
          GetDraftDesignDigestResponse.newBuilder()
              .setTenantId(binding.tenantId())
              .setVersionId(binding.versionId())
              .setAppliedCommitId(digest.appliedCommitId())
              .setContentDigest(digest.contentDigest())
              .setDigestSchemaVersion(digest.digestSchemaVersion());
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "GetDraftDesignDigest",
                      "PERMISSION_DENIED",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "GetDraftDesignDigest",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "GetDraftDesignDigest", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  private void requirePublicationRead() {
    if (publicationReadGuard == null) {
      throw new AdminAuthorizationException("Publication read authorization is not configured");
    }
    publicationReadGuard.requirePublicationRead(
        PublicationReadGuard.WORLD_MANAGEMENT_DIGEST_METHOD);
  }

  @Override
  @Timed(value = "worldGrpc.validateWorldUpgradeMappings")
  public void validateWorldUpgradeMappings(
      ValidateWorldUpgradeMappingsRequest request,
      StreamObserver<ValidateWorldUpgradeMappingsResponse> responseObserver) {
    ValidateWorldUpgradeMappingsResponse.Builder builder =
        ValidateWorldUpgradeMappingsResponse.newBuilder();
    try {
      var validation =
          worldUpgradeValidationService.validateWorldUpgradeMappings(
              RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
              RequestIdValidation.requirePositiveLong(
                  request.getSourceGameInstanceId(), "sourceGameInstanceId"),
              RequestIdValidation.requirePositiveLong(
                  request.getTargetVersionId(), "targetVersionId"),
              request.getRemapSetId().isBlank() ? null : request.getRemapSetId());
      builder
          .addAllStateClassesChecked(validation.stateClassesChecked())
          .addAllCheckedFamilies(validation.checkedFamilies())
          .setHasS2Rows(validation.hasS2Rows())
          .setResult(toUpgradeValidationResult(validation.result()))
          .setRemapSetRequired(validation.remapSetRequired())
          .addAllReasons(validation.reasons());
      if (validation.remapSetId() != null) {
        builder.setRemapSetId(validation.remapSetId());
      }
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ValidateWorldUpgradeMappings",
              errorCodeFor(ex),
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "ValidateWorldUpgradeMappings", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.applyWorldDesignMutation")
  public void applyWorldDesignMutation(
      ApplyWorldDesignMutationRequest request,
      StreamObserver<ApplyWorldDesignMutationResponse> responseObserver) {
    ApplyWorldDesignMutationResponse.Builder builder =
        ApplyWorldDesignMutationResponse.newBuilder();
    builder.setError(
        GrpcAppErrors.error(
            meterRegistry,
            logger,
            "ApplyWorldDesignMutation",
            "FAILED_PRECONDITION",
            "Canonical World Draft writes are unavailable until current Account commit authorization is verified."));
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "worldGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    try {
      String msg = pingService.ping();
      PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "Ping", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "Ping", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "worldGrpc.getRoom")
  public void getRoom(GetRoomRequest request, StreamObserver<GetRoomResponse> responseObserver) {
    try {
      GameplayRoomScope roomScope =
          requireGameplayRoomScope(request.getTenantId(), request.getRoomInstance());
      requireGameplayAttestation(
          request.getSessionAttestation(),
          roomScope.tenantIdText(),
          roomScope.gameInstanceIdText(),
          roomScope.roomInstanceIdText());
      requireTenantAccessWhenPresent(roomScope.tenantId());
      Optional<RuntimeRoom> room =
          Optional.ofNullable(
                  roomService.getRoom(
                      roomScope.tenantId(),
                      roomScope.gameInstanceId(),
                      roomScope.roomInstanceRowId()))
              .map(this::toProto);
      if (room.isPresent()) {
        GetRoomResponse response = GetRoomResponse.newBuilder().setRoom(room.get()).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } else {
        GetRoomResponse response =
            GetRoomResponse.newBuilder()
                .setError(
                    GrpcAppErrors.error(
                        meterRegistry, logger, "GetRoom", "NOT_FOUND", "room not found"))
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (GameplaySessionAttestationException ex) {
      GetRoomResponse response =
          GetRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "GetRoom", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetRoomResponse response =
          GetRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "GetRoom", errorCodeFor(ex), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      GetRoomResponse response =
          GetRoomResponse.newBuilder().setError(appError("GetRoom", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      GetRoomResponse response =
          GetRoomResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "GetRoom", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "worldGrpc.getRoomSnapshot")
  public void getRoomSnapshot(
      GetRoomSnapshotRequest request, StreamObserver<GetRoomSnapshotResponse> responseObserver) {
    try {
      GameplayRoomScope roomScope =
          requireGameplayRoomScope(request.getTenantId(), request.getRoomInstance());
      requireGameplayAttestation(
          request.getSessionAttestation(),
          roomScope.tenantIdText(),
          roomScope.gameInstanceIdText(),
          roomScope.roomInstanceIdText());
      requireTenantAccessWhenPresent(roomScope.tenantId());
      RoomSnapshotDto snapshot =
          roomService.getRoomSnapshot(
              roomScope.tenantId(),
              roomScope.gameInstanceId(),
              roomScope.roomInstanceRowId(),
              request.getPreferredLocale());
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder().setSnapshot(toProto(snapshot)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "GetRoomSnapshot", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "GetRoomSnapshot", errorCodeFor(ex), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder().setError(appError("GetRoomSnapshot", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "GetRoomSnapshot", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private RoomSnapshot toProto(RoomSnapshotDto snapshot) {
    RoomSnapshot.Builder builder = RoomSnapshot.newBuilder();
    String runtimeRoomInstanceId = RuntimeRoomInstanceIds.canonical(snapshot.roomInstanceRowId());
    builder
        .setRoomInstanceId(runtimeRoomInstanceId)
        .setTenantId(snapshot.tenantId().toString())
        .setGameInstanceId(snapshot.gameInstanceId().toString())
        .setWorldSnapshotId(
            readFence(snapshot.tenantId(), snapshot.gameInstanceId(), runtimeRoomInstanceId))
        .setRoomName(snapshot.roomName())
        .setShortDescription(snapshot.shortDescription())
        .setLongDescription(snapshot.longDescription());
    snapshot.exits().forEach(exit -> builder.addExits(toProto(exit)));
    if (snapshot.ambientState() != null) {
      applyAmbientState(builder, snapshot);
    }
    if (snapshot.roomFlags() != null) {
      builder.addAllRoomFlags(snapshot.roomFlags());
    }
    return builder.build();
  }

  private WorldInstanceLifecycleSnapshot toProto(
      net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto snapshot) {
    return WorldInstanceLifecycleSnapshot.newBuilder()
        .setTenantId(Long.toString(snapshot.tenantId()))
        .setGameInstanceId(Long.toString(snapshot.gameInstanceId()))
        .setGameTemplateId(Long.toString(snapshot.gameTemplateId()))
        .setControlPlaneRequestId(snapshot.controlPlaneRequestId())
        .setLaunchDescriptorId(snapshot.launchDescriptorId())
        .setVersionId(Long.toString(snapshot.versionId()))
        .setReleaseBundleId(Long.toString(snapshot.releaseBundleId()))
        .setGenerationConfigRevision(snapshot.generationConfigRevision())
        .setPublishedReleaseBundleRef(snapshot.publishedReleaseBundleRef())
        .setVersionStateEpoch(snapshot.versionStateEpoch())
        .setLifecycleEpoch(snapshot.lifecycleEpoch())
        .setStatus(toProtoStatus(snapshot.status()))
        .setRemapSetId(snapshot.remapSetId() == null ? "" : snapshot.remapSetId())
        .setWorkflowId(snapshot.workflowId() == null ? "" : snapshot.workflowId())
        .setWorkflowRunId(snapshot.workflowRunId() == null ? "" : snapshot.workflowRunId())
        .setWorkflowStatus(snapshot.workflowStatus() == null ? "" : snapshot.workflowStatus())
        .setWorkflowFamily(snapshot.workflowFamily() == null ? "" : snapshot.workflowFamily())
        .build();
  }

  private net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold toProto(
      InitialAdmissionBindHoldDto hold) {
    return net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold.newBuilder()
        .setHoldId(hold.holdId())
        .setHoldFence(hold.holdFence())
        .setTenantId(Long.toString(hold.tenantId()))
        .setRealmUuid(hold.realmUuid())
        .setPlayableStateNamespaceUuid(hold.playableStateNamespaceUuid())
        .setPlayableStateScope(toProtoPlayableStateScope(hold.playableStateScope()))
        .setGameInstanceId(Long.toString(hold.gameInstanceId()))
        .setVersionId(Long.toString(hold.versionId()))
        .setActiveLifecycleEpoch(hold.activeLifecycleEpoch())
        .setInitialAdmissionRequestId(hold.initialAdmissionRequestId())
        .setRequestDigest(hold.requestDigest())
        .setExpectedNoPriorPointer(hold.expectedNoPriorPointer())
        .setExpectedCatalogRevision(hold.expectedCatalogRevision())
        .setStatus(toProtoInitialAdmissionBindStatus(hold.status()))
        .setDiagnosticExpiresAtEpochMillis(hold.diagnosticExpiresAt().toEpochMilli())
        .build();
  }

  private String normalizePlayableStateScope(PlayableStateScope scope) {
    return switch (scope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      default ->
          throw new IllegalArgumentException(
              "INVALID_ARGUMENT: playableStateScope must be SHARED or ISOLATED");
    };
  }

  private PlayableStateScope toProtoPlayableStateScope(String scope) {
    return switch (scope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> throw new IllegalStateException("Unsupported persisted playable state scope");
    };
  }

  private InitialAdmissionBindHoldStatus toProtoInitialAdmissionBindStatus(String status) {
    return switch (status) {
      case "PENDING" -> InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING;
      case "RECONCILIATION_REQUIRED" ->
          InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_RECONCILIATION_REQUIRED;
      case "COMMITTED" ->
          InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_COMMITTED;
      case "ABORTED" -> InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_ABORTED;
      default -> InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_UNSPECIFIED;
    };
  }

  private WorldInstanceLifecycleStatus toProtoStatus(String status) {
    return switch (status) {
      case "PREPARING" -> WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING;
      case "ACTIVE" -> WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE;
      case "FAILED_PRE_ACTIVATION" ->
          WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION;
      case "TERMINATING" ->
          WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING;
      case "TERMINATED" -> WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED;
      default -> WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_UNSPECIFIED;
    };
  }

  private void applyAmbientState(RoomSnapshot.Builder builder, RoomSnapshotDto snapshot) {
    String weather = snapshot.ambientState().get("weather");
    if (weather == null || weather.isBlank()) {
      return;
    }
    builder.setAmbientState(
        net.firedevops.firemud.worldmanagement.v1.RoomAmbientState.newBuilder()
            .setSchemaVersion(1)
            .setWeather(weather)
            .build());
  }

  private RoomExitSnapshot toProto(RoomExitSnapshotDto exit) {
    RoomExitSnapshot.Builder builder = RoomExitSnapshot.newBuilder();
    String targetRuntimeRoomInstanceId =
        RuntimeRoomInstanceIds.canonical(exit.targetRoomInstanceRowId());
    builder
        .setExitId(exit.exitId().toString())
        .setTargetRoomInstanceId(targetRuntimeRoomInstanceId)
        .setTargetRoomName(exit.targetRoomName())
        .setDirection(exit.direction())
        .setLabel(exit.label())
        .setDescription(exit.description());
    if (exit.cost() != null) {
      builder.setCost(exit.cost());
    }
    return builder.build();
  }

  private String readFence(long tenantId, long gameInstanceId, String roomInstanceId) {
    return tenantId + ":" + gameInstanceId + ":" + roomInstanceId;
  }

  private RuntimeRoom toProto(RuntimeRoomDto dto) {
    return RuntimeRoom.newBuilder()
        .setTenantId(Long.toString(dto.tenantId()))
        .setGameInstanceId(Long.toString(dto.gameInstanceId()))
        .setRoomInstanceId(RuntimeRoomInstanceIds.canonical(dto.roomInstanceRowId()))
        .setRegionId(Long.toString(dto.regionId()))
        .setName(dto.name() == null ? "" : dto.name())
        .setDescription(dto.description() == null ? "" : dto.description())
        .build();
  }

  private String errorCodeFor(IllegalArgumentException ex) {
    String message = ex.getMessage();
    if ("Room not found".equals(message)) {
      return "NOT_FOUND";
    }
    int separator = message == null ? -1 : message.indexOf(':');
    if (separator > 0) {
      String candidate = message.substring(0, separator);
      if (candidate.matches("[A-Z_]+")) {
        return candidate;
      }
    }
    return "INVALID_ARGUMENT";
  }

  private String errorMessageFor(IllegalArgumentException ex) {
    String message = ex.getMessage();
    int separator = message == null ? -1 : message.indexOf(':');
    if (separator > 0 && message.substring(0, separator).matches("[A-Z_]+")) {
      return message.substring(separator + 1).trim();
    }
    return message;
  }

  private UpgradeValidationResult toUpgradeValidationResult(String result) {
    return switch (result) {
      case "COMPATIBLE" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_COMPATIBLE;
      case "REQUIRES_MAPPING" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_REQUIRES_MAPPING;
      case "INCOMPATIBLE" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_INCOMPATIBLE;
      case "UNAVAILABLE" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_UNAVAILABLE;
      default -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_UNSPECIFIED;
    };
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail appError(
      String operation, ResponseStatusException ex) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, appErrorCode(ex), ex.getReason());
  }

  private String appErrorCode(ResponseStatusException ex) {
    return ex.getStatusCode().value() == 403 ? "PERMISSION_DENIED" : "INVALID_ARGUMENT";
  }

  private void requireTenantAccessWhenPresent(Long tenantId) {
    if (SessionContext.isInternalService()) {
      return;
    }
    if (!SessionContext.hasAuthenticatedCallerContext()) {
      return;
    }
    SessionContext.requireTenantAccess(tenantId);
  }

  private GameplayRoomScope requireGameplayRoomScope(
      String topLevelTenantIdText, RoomInstanceRef roomInstance) {
    String topLevelTenantId = blankToNull(topLevelTenantIdText);
    String nestedTenantId = blankToNull(roomInstance.getTenantId());
    if (topLevelTenantId != null
        && nestedTenantId != null
        && !topLevelTenantId.equals(nestedTenantId)) {
      throw new IllegalArgumentException("tenantId must match roomInstance.tenantId");
    }
    String tenantIdText =
        nestedTenantId != null ? nestedTenantId : requireText(topLevelTenantId, "tenantId");
    String gameInstanceIdText = requireText(roomInstance.getGameInstanceId(), "gameInstanceId");
    String roomInstanceIdText = requireText(roomInstance.getRoomInstanceId(), "roomInstanceId");
    long tenantId = RequestIdValidation.requirePositiveLong(tenantIdText, "tenantId");
    return new GameplayRoomScope(
        tenantId,
        RequestIdValidation.requirePositiveLong(gameInstanceIdText, "gameInstanceId"),
        RuntimeRoomInstanceIds.requireRowId(roomInstanceIdText),
        tenantIdText,
        gameInstanceIdText,
        roomInstanceIdText);
  }

  private String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be specified");
    }
    return value;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private void requireGameplayAttestation(
      String token, String tenantId, String gameInstanceId, String roomInstanceId) {
    gameplaySessionAttestationService.requireGameplayOrProbeMatch(
        token, tenantId, gameInstanceId, roomInstanceId);
    if (!SessionContext.isInternalService()) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay world RPCs require internal service identity");
    }
  }

  private record GameplayRoomScope(
      long tenantId,
      long gameInstanceId,
      long roomInstanceRowId,
      String tenantIdText,
      String gameInstanceIdText,
      String roomInstanceIdText) {}
}
