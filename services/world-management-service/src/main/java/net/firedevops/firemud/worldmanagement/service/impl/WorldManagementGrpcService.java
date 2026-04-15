package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto.RoomExitSnapshotDto;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse;
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
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** gRPC endpoints for the World Management Service. */
@GrpcService(interceptors = AuthTokenInterceptor.class)
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
                  Long.parseLong(request.getTenantId()),
                  Long.parseLong(request.getGameInstanceId()),
                  Long.parseLong(request.getGameTemplateId()),
                  request.getControlPlaneRequestId(),
                  request.getLaunchDescriptorId(),
                  Long.parseLong(request.getVersionId()),
                  request.getScriptPatchVersion(),
                  request.getRuntimeFlagsJson(),
                  request.getGenerationConfigRevision(),
                  Long.parseLong(request.getReleaseBundleId()),
                  request.getPublishedReleaseBundleRef(),
                  request.getVersionStateEpoch()));
      builder.setWorldInstance(toProto(snapshot));
    } catch (NumberFormatException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "PrepareWorldInstance", "INVALID_ARGUMENT", "invalid id"));
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
              Long.parseLong(request.getTenantId()),
              Long.parseLong(request.getGameInstanceId()),
              request.getExpectedLifecycleEpoch());
      builder.setWorldInstance(toProto(snapshot));
    } catch (NumberFormatException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "ActivatePreparedWorldInstance",
              "INVALID_ARGUMENT",
              "invalid id"));
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
              Long.parseLong(request.getTenantId()),
              Long.parseLong(request.getGameInstanceId()),
              request.getExpectedLifecycleEpoch(),
              request.getReason());
      builder.setWorldInstance(toProto(snapshot));
    } catch (NumberFormatException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "FailPreparedWorldInstance",
              "INVALID_ARGUMENT",
              "invalid id"));
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
                  Long.parseLong(request.getTenantId()),
                  Long.parseLong(request.getGameInstanceId()))));
    } catch (NumberFormatException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "GetWorldInstanceLifecycle",
              "INVALID_ARGUMENT",
              "invalid id"));
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
                  Long.parseLong(request.getTenantId()),
                  Long.parseLong(request.getGameInstanceId()),
                  request.getExpectedLifecycleEpoch(),
                  request.getTerminationRequestId(),
                  request.getReason())));
    } catch (NumberFormatException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry, logger, "TerminateWorldInstance", "INVALID_ARGUMENT", "invalid id"));
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
      Long roomId = Long.valueOf(resolveRoomId(request));
      Long tenantId = Long.valueOf(resolveTenantId(request));
      SessionContext.requireTenantAccess(tenantId);
      Optional<String> json =
          Optional.ofNullable(roomService.getRoom(tenantId, roomId)).map(this::toJson);
      if (json.isPresent()) {
        GetRoomResponse response = GetRoomResponse.newBuilder().setRoomJson(json.get()).build();
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
    } catch (NumberFormatException ex) {
      GetRoomResponse response =
          GetRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "GetRoom", "INVALID_ARGUMENT", "invalid id"))
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
      Long roomId = Long.valueOf(resolveRoomId(request));
      Long tenantId = Long.valueOf(resolveTenantId(request));
      requireTenantAccessWhenPresent(tenantId);
      RoomSnapshotDto snapshot =
          roomService.getRoomSnapshot(tenantId, roomId, request.getPreferredLocale());
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder().setSnapshot(toProto(snapshot)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "GetRoomSnapshot", "INVALID_ARGUMENT", "invalid id"))
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
    RoomSnapshot.Builder builder =
        RoomSnapshot.newBuilder()
            .setRoomInstanceId(snapshot.roomId().toString())
            .setTenantId(snapshot.tenantId().toString())
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
    RoomExitSnapshot.Builder builder =
        RoomExitSnapshot.newBuilder()
            .setExitId(exit.exitId().toString())
            .setTargetRoomInstanceId(exit.targetRoomId().toString())
            .setTargetRoomName(exit.targetRoomName())
            .setDirection(exit.direction())
            .setLabel(exit.label())
            .setDescription(exit.description());
    if (exit.cost() != null) {
      builder.setCost(exit.cost());
    }
    return builder.build();
  }

  private String toJson(RoomDto dto) {
    try {
      return objectMapper.writeValueAsString(dto);
    } catch (JacksonException e) {
      throw new RuntimeException("Failed to serialize room", e);
    }
  }

  private String resolveTenantId(GetRoomRequest request) {
    if (request.getRoomInstance().getTenantId().isBlank()) {
      return request.getTenantId();
    }
    return request.getRoomInstance().getTenantId();
  }

  private String resolveRoomId(GetRoomRequest request) {
    if (request.getRoomInstance().getRoomInstanceId().isBlank()) {
      throw new IllegalArgumentException("room_instance.room_instance_id is required");
    }
    return request.getRoomInstance().getRoomInstanceId();
  }

  private String resolveTenantId(GetRoomSnapshotRequest request) {
    if (request.getRoomInstance().getTenantId().isBlank()) {
      return request.getTenantId();
    }
    return request.getRoomInstance().getTenantId();
  }

  private String resolveRoomId(GetRoomSnapshotRequest request) {
    if (request.getRoomInstance().getRoomInstanceId().isBlank()) {
      throw new IllegalArgumentException("room_instance.room_instance_id is required");
    }
    return request.getRoomInstance().getRoomInstanceId();
  }

  private String errorCodeFor(IllegalArgumentException ex) {
    return "Room not found".equals(ex.getMessage()) ? "NOT_FOUND" : "INVALID_ARGUMENT";
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail appError(
      String operation, ResponseStatusException ex) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, appErrorCode(ex), ex.getReason());
  }

  private String appErrorCode(ResponseStatusException ex) {
    return ex.getStatusCode().value() == 403 ? "PERMISSION_DENIED" : "INVALID_ARGUMENT";
  }

  private void requireTenantAccessWhenPresent(Long tenantId) {
    if (SessionContext.getAccountId() == null
        && SessionContext.getGlobalRoles().isEmpty()
        && SessionContext.getScopedRolesMap().isEmpty()) {
      return;
    }
    SessionContext.requireTenantAccess(tenantId);
  }
}
