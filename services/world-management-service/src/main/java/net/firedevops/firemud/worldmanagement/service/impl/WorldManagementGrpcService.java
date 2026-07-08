package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.GameplaySessionAttestationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto.RoomExitSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RuntimeRoomDto;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationRequestDto;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationRequest;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationResponse;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.GeneratedRoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.GeneratedRoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.GeneratedWorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RuntimeRoom;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.UpgradeValidationResult;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsRequest;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationResult;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldGenerationSubtreeDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** gRPC endpoints for the World Management Service. */
@GrpcService
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services and registry remain internal")
public class WorldManagementGrpcService
    extends WorldManagementServiceGrpc.WorldManagementServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(WorldManagementGrpcService.class);
  private final PingService pingService;
  private final RoomService roomService;
  private final WorldInstanceActivationService worldInstanceActivationService;
  private final WorldDraftDesignDigestService worldDraftDesignDigestService;
  private final WorldDesignMutationService worldDesignMutationService;
  private final WorldUpgradeValidationService worldUpgradeValidationService;
  private final GameplaySessionAttestationService gameplaySessionAttestationService;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;

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
      var digest =
          worldDraftDesignDigestService.getDraftDesignDigest(
              request.getTenantId(), request.getVersionId());
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setTenantId(digest.tenantId())
              .setScopeValue(digest.scopeValue())
              .setAppliedCommitId(digest.appliedCommitId())
              .setContentDigest(digest.contentDigest())
              .setDigestSchemaVersion(digest.digestSchemaVersion())
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
    try {
      var result = worldDesignMutationService.applyMutation(toDto(request));
      builder
          .setResult(toProtoMutationResult(result.result()))
          .setTenantId(Long.toString(result.tenantId()))
          .setVersionId(Long.toString(result.versionId()))
          .setAggregateId(Long.toString(result.aggregateId()))
          .setDraftRevisionEpoch(result.draftRevisionEpoch());
      if (result.draftScopeRevisionEpoch() != null) {
        builder.setDraftScopeRevisionEpoch(result.draftScopeRevisionEpoch());
      }
    } catch (NumberFormatException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "ApplyWorldDesignMutation", "INVALID_ARGUMENT", "invalid id"));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ApplyWorldDesignMutation",
              errorCodeFor(ex),
              errorMessageFor(ex)));
    } catch (Exception ex) {
      builder.setError(
          GrpcAppErrors.internal(meterRegistry, logger, "ApplyWorldDesignMutation", ex));
    }
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

  private WorldDesignMutationRequestDto toDto(ApplyWorldDesignMutationRequest request) {
    return new WorldDesignMutationRequestDto(
        RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId"),
        RequestIdValidation.requirePositiveLong(request.getVersionId(), "versionId"),
        request.getCommitId(),
        request.getRevisionId(),
        operationName(request),
        aggregateTypeName(request),
        request.getAggregateId(),
        request.getExpectedDraftRevisionEpoch(),
        request.getScopeType()
                == net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType
                    .WORLD_DESIGN_SCOPE_TYPE_UNSPECIFIED
            ? ""
            : request.getScopeType().name().replace("WORLD_DESIGN_SCOPE_TYPE_", ""),
        request.getScopeId(),
        request.getExpectedDraftScopeRevisionEpoch(),
        request.getScopeMutationPolicy()
                == net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy
                    .WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED
            ? ""
            : request
                .getScopeMutationPolicy()
                .name()
                .replace("WORLD_DESIGN_SCOPE_MUTATION_POLICY_", ""),
        request.hasRegion() ? toDto(request.getRegion()) : null,
        request.hasZone() ? toDto(request.getZone()) : null,
        request.hasRoom() ? toDto(request.getRoom()) : null,
        request.hasRoomExit() ? toDto(request.getRoomExit()) : null,
        request.hasGenerationRule() ? toDto(request.getGenerationRule()) : null,
        request.hasWorldEntitySpawnBinding() ? toDto(request.getWorldEntitySpawnBinding()) : null,
        request.hasWorldGenerationSubtree() ? toDto(request.getWorldGenerationSubtree()) : null);
  }

  private String operationName(ApplyWorldDesignMutationRequest request) {
    return switch (request.getOperation()) {
      case WORLD_DESIGN_MUTATION_OPERATION_UPSERT -> "UPSERT";
      case WORLD_DESIGN_MUTATION_OPERATION_DELETE -> "DELETE";
      default -> "";
    };
  }

  private String aggregateTypeName(ApplyWorldDesignMutationRequest request) {
    return switch (request.getAggregateType()) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION -> "REGION";
      case WORLD_DESIGN_AGGREGATE_TYPE_ZONE -> "ZONE";
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM -> "ROOM";
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> "ROOM_EXIT";
      case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE -> "GENERATION_RULE";
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING -> "WORLD_ENTITY_SPAWN_BINDING";
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_GENERATION_SUBTREE -> "WORLD_GENERATION_SUBTREE";
      default -> "";
    };
  }

  private WorldDesignMutationRequestDto.RegionMutationDto toDto(RegionDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.RegionMutationDto(
        mutation.getName(),
        mutation.getWeather(),
        mutation.getShardId(),
        mutation.getGenerationSeed(),
        mutation.getGeneratorType(),
        mutation.getGeneratorParams(),
        mutation.getSpacingMultiplier());
  }

  private WorldDesignMutationRequestDto.ZoneMutationDto toDto(ZoneDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.ZoneMutationDto(
        mutation.getName(), mutation.getRegionId());
  }

  private WorldDesignMutationRequestDto.RoomMutationDto toDto(RoomDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.RoomMutationDto(
        mutation.getName(),
        mutation.getDescription(),
        mutation.getZoneId(),
        mutation.getNameLocalizedVariantsJson(),
        mutation.getDescriptionLocalizedVariantsJson());
  }

  private WorldDesignMutationRequestDto.RoomExitMutationDto toDto(RoomExitDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.RoomExitMutationDto(
        mutation.getFromRoomId(),
        mutation.getToRoomId(),
        mutation.getDirection(),
        mutation.getCost());
  }

  private WorldDesignMutationRequestDto.GenerationRuleMutationDto toDto(
      GenerationRuleDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.GenerationRuleMutationDto(
        mutation.getName(), mutation.getValue());
  }

  private WorldDesignMutationRequestDto.WorldEntitySpawnBindingMutationDto toDto(
      WorldEntitySpawnBindingDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.WorldEntitySpawnBindingMutationDto(
        mutation.getRoomId(),
        mutation.getEntityTemplateType().name().replace("ENTITY_TEMPLATE_REFERENCE_TYPE_", ""),
        mutation.getEntityTemplateId(),
        mutation.getSpawnCount(),
        mutation.getRespawnDelaySeconds());
  }

  private WorldDesignMutationRequestDto.WorldGenerationSubtreeMutationDto toDto(
      WorldGenerationSubtreeDesignMutation mutation) {
    List<WorldDesignMutationRequestDto.GenerationRuleMutationDto> generationRules =
        mutation.getGenerationRulesList().stream().map(this::toDto).toList();
    List<WorldDesignMutationRequestDto.GeneratedRoomMutationDto> rooms =
        mutation.getRoomsList().stream().map(this::toDto).toList();
    List<WorldDesignMutationRequestDto.GeneratedRoomExitMutationDto> roomExits =
        mutation.getRoomExitsList().stream().map(this::toDto).toList();
    List<WorldDesignMutationRequestDto.GeneratedWorldEntitySpawnBindingMutationDto> spawnBindings =
        mutation.getWorldEntitySpawnBindingsList().stream().map(this::toDto).toList();
    return new WorldDesignMutationRequestDto.WorldGenerationSubtreeMutationDto(
        generationRules, rooms, roomExits, spawnBindings);
  }

  private WorldDesignMutationRequestDto.GeneratedRoomMutationDto toDto(
      GeneratedRoomDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.GeneratedRoomMutationDto(
        mutation.getClientRef(),
        mutation.getName(),
        mutation.getDescription(),
        mutation.getZoneId(),
        mutation.getNameLocalizedVariantsJson(),
        mutation.getDescriptionLocalizedVariantsJson());
  }

  private WorldDesignMutationRequestDto.GeneratedRoomExitMutationDto toDto(
      GeneratedRoomExitDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.GeneratedRoomExitMutationDto(
        mutation.getFromRoomRef(),
        mutation.getToRoomRef(),
        mutation.getDirection(),
        mutation.getCost());
  }

  private WorldDesignMutationRequestDto.GeneratedWorldEntitySpawnBindingMutationDto toDto(
      GeneratedWorldEntitySpawnBindingDesignMutation mutation) {
    return new WorldDesignMutationRequestDto.GeneratedWorldEntitySpawnBindingMutationDto(
        mutation.getRoomRef(),
        mutation.getEntityTemplateType().name().replace("ENTITY_TEMPLATE_REFERENCE_TYPE_", ""),
        mutation.getEntityTemplateId(),
        mutation.getSpawnCount(),
        mutation.getRespawnDelaySeconds());
  }

  private WorldDesignMutationResult toProtoMutationResult(String result) {
    return switch (result) {
      case "APPLIED" -> WorldDesignMutationResult.WORLD_DESIGN_MUTATION_RESULT_APPLIED;
      case "NO_OP_ALREADY_APPLIED" ->
          WorldDesignMutationResult.WORLD_DESIGN_MUTATION_RESULT_NO_OP_ALREADY_APPLIED;
      default -> WorldDesignMutationResult.WORLD_DESIGN_MUTATION_RESULT_UNSPECIFIED;
    };
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
