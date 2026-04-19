package net.firedevops.firemud.gamelogic.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.gamelogic.v1.EntityType;
import net.firedevops.firemud.gamelogic.v1.HazardAmbientState;
import net.firedevops.firemud.gamelogic.v1.LookExit;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.RoomAmbientState;
import net.firedevops.firemud.gamelogic.v1.RoomEntity;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "gRPC stubs are thread-safe")
public class LookAggregationService {
  private static final Logger LOG = LoggerFactory.getLogger(LookAggregationService.class);

  private final WorldManagementServiceGrpc.WorldManagementServiceBlockingStub worldStub;
  private final EntityManagementServiceGrpc.EntityManagementServiceBlockingStub entityStub;
  private final LookResultRenderer renderer;

  public LookResult resolve(LookRequest request) {
    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(request)) {
      RoomSnapshot snapshot = fetchSnapshot(request);
      ListRoomEntitiesResponse entityResponse = fetchEntities(request);

      LookResult.Builder builder =
          LookResult.newBuilder()
              .setRoomInstance(
                  RoomInstanceRef.newBuilder()
                      .setTenantId(snapshot.getTenantId())
                      .setGameInstanceId(snapshot.getGameInstanceId())
                      .setRoomInstanceId(snapshot.getRoomInstanceId())
                      .build())
              .setRoomName(snapshot.getRoomName())
              .setShortDescription(snapshot.getShortDescription())
              .setLongDescription(snapshot.getLongDescription())
              .addAllRoomFlags(snapshot.getRoomFlagsList())
              .setAmbientState(toAmbientState(snapshot.getAmbientState()));

      snapshot.getExitsList().forEach(exit -> builder.addExits(toLookExit(exit)));
      entityResponse.getEntitiesList().stream()
          .map(this::toRoomEntity)
          .forEach(builder::addEntities);

      LookResult result = builder.build();
      LOG.debug("Rendered LOOK text:\n{}", renderer.render(result));
      return result;
    }
  }

  private RoomSnapshot fetchSnapshot(LookRequest request) {
    try {
      GetRoomSnapshotResponse response =
          worldStub.getRoomSnapshot(
              GetRoomSnapshotRequest.newBuilder()
                  .setTenantId(resolveTenantId(request))
                  .setRoomInstance(resolveRoomInstance(request))
                  .setPreferredLocale(request.getPreferredLocale())
                  .setSessionAttestation(request.getSessionAttestation())
                  .build());
      if (response.hasError()) {
        throw statusFromError(response.getError(), "WorldManagement");
      }
      return response.getSnapshot();
    } catch (StatusRuntimeException ex) {
      throw rethrowWithSource(ex, "WorldManagement");
    }
  }

  private ListRoomEntitiesResponse fetchEntities(LookRequest request) {
    try {
      ListRoomEntitiesResponse response =
          entityStub.listRoomEntities(
              ListRoomEntitiesRequest.newBuilder()
                  .setTenantId(resolveTenantId(request))
                  .setRoomInstance(resolveRoomInstance(request))
                  .setSessionAttestation(request.getSessionAttestation())
                  .build());
      if (response.hasError()) {
        throw statusFromError(response.getError(), "EntityManagement");
      }
      return response;
    } catch (StatusRuntimeException ex) {
      throw rethrowWithSource(ex, "EntityManagement");
    }
  }

  private StatusRuntimeException statusFromError(ErrorDetail error, String source) {
    Status.Code statusCode = mapErrorCode(error);
    String description =
        Optional.ofNullable(error.getMessage()).filter(s -> !s.isBlank()).orElse("unreachable");
    return Status.fromCode(statusCode)
        .withDescription(source + ": " + description)
        .asRuntimeException();
  }

  private Status.Code mapErrorCode(ErrorDetail error) {
    if (error == null || error.getCode() == null) {
      return Status.Code.UNAVAILABLE;
    }
    return switch (error.getCode().toUpperCase()) {
      case "INVALID_ARGUMENT" -> Status.Code.INVALID_ARGUMENT;
      case "NOT_FOUND" -> Status.Code.NOT_FOUND;
      case "PERMISSION_DENIED" -> Status.Code.PERMISSION_DENIED;
      default -> Status.Code.UNAVAILABLE;
    };
  }

  private StatusRuntimeException rethrowWithSource(StatusRuntimeException ex, String source) {
    String description =
        Optional.ofNullable(ex.getStatus().getDescription())
            .filter(s -> !s.isBlank())
            .orElse("unreachable");
    return Status.fromCode(ex.getStatus().getCode())
        .withDescription(source + ": " + description)
        .asRuntimeException();
  }

  private LookExit toLookExit(RoomExitSnapshot exit) {
    return LookExit.newBuilder()
        .setLabel(exit.getLabel())
        .setTargetRoomInstanceId(exit.getTargetRoomInstanceId())
        .setDescription(exit.getDescription())
        .build();
  }

  private RoomAmbientState toAmbientState(
      net.firedevops.firemud.worldmanagement.v1.RoomAmbientState ambientState) {
    RoomAmbientState.Builder builder =
        RoomAmbientState.newBuilder()
            .setSchemaVersion(ambientState.getSchemaVersion())
            .setWeather(ambientState.getWeather());
    ambientState.getDoorsList().forEach(door -> builder.addDoors(toDoorAmbientState(door)));
    ambientState
        .getHazardsList()
        .forEach(hazard -> builder.addHazards(toHazardAmbientState(hazard)));
    return builder.build();
  }

  private net.firedevops.firemud.gamelogic.v1.DoorAmbientState toDoorAmbientState(
      net.firedevops.firemud.worldmanagement.v1.DoorAmbientState door) {
    return net.firedevops.firemud.gamelogic.v1.DoorAmbientState.newBuilder()
        .setDoorId(door.getDoorId())
        .setState(net.firedevops.firemud.gamelogic.v1.DoorState.forNumber(door.getStateValue()))
        .build();
  }

  private HazardAmbientState toHazardAmbientState(
      net.firedevops.firemud.worldmanagement.v1.HazardAmbientState hazard) {
    return HazardAmbientState.newBuilder()
        .setHazardId(hazard.getHazardId())
        .setState(net.firedevops.firemud.gamelogic.v1.HazardState.forNumber(hazard.getStateValue()))
        .build();
  }

  private String resolveTenantId(LookRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getTenantId().isBlank()) {
      return request.getRoomInstance().getTenantId();
    }
    return request.getTenantId();
  }

  private RoomInstanceRef resolveRoomInstance(LookRequest request) {
    if (request.getRoomInstance().getRoomInstanceId().isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("room_instance.room_instance_id is required")
          .asRuntimeException();
    }
    return request.getRoomInstance();
  }

  private RoomEntity toRoomEntity(net.firedevops.firemud.entitymanagement.v1.RoomEntity entity) {
    EntityType type =
        EntityType.valueOf(entity.getEntityType().name()); // names align between modules
    return RoomEntity.newBuilder()
        .setEntityId(entity.getEntityId())
        .setDisplayName(entity.getDisplayName())
        .setEntityType(type)
        .setRole(entity.getRole())
        .addAllStateFlags(entity.getStateFlagsList())
        .build();
  }
}
