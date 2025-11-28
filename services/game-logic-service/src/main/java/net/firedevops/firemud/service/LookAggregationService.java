package net.firedevops.firemud.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.gamelogic.v1.EntityType;
import net.firedevops.firemud.gamelogic.v1.LookExit;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.RoomEntity;
import net.firedevops.firemud.service.LookResultRenderer;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
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
    RoomSnapshot snapshot = fetchSnapshot(request);
    ListRoomEntitiesResponse entityResponse = fetchEntities(request);

    LookResult.Builder builder =
        LookResult.newBuilder()
            .setRoomId(snapshot.getRoomId())
            .setRoomName(snapshot.getRoomName())
            .setShortDescription(snapshot.getShortDescription())
            .setLongDescription(snapshot.getLongDescription())
            .putAllAmbientState(snapshot.getAmbientStateMap())
            .addAllRoomFlags(snapshot.getRoomFlagsList());

    snapshot.getExitsList().forEach(exit -> builder.addExits(toLookExit(exit)));
    entityResponse.getEntitiesList().stream()
        .map(this::toRoomEntity)
        .forEach(builder::addEntities);

    LookResult result = builder.build();
    LOG.debug("Rendered LOOK text:\n{}", renderer.render(result));
    return result;
  }

  private RoomSnapshot fetchSnapshot(LookRequest request) {
    try {
      return worldStub
          .getRoomSnapshot(
              GetRoomSnapshotRequest.newBuilder()
                  .setTenantId(request.getTenantId())
                  .setRoomId(request.getRoomId())
                  .build())
          .getSnapshot();
    } catch (StatusRuntimeException ex) {
      throw rethrowWithSource(ex, "WorldManagement");
    }
  }

  private ListRoomEntitiesResponse fetchEntities(LookRequest request) {
    try {
      return entityStub.listRoomEntities(
          ListRoomEntitiesRequest.newBuilder()
              .setTenantId(request.getTenantId())
              .setRoomId(request.getRoomId())
              .build());
    } catch (StatusRuntimeException ex) {
      throw rethrowWithSource(ex, "EntityManagement");
    }
  }

  private StatusRuntimeException rethrowWithSource(StatusRuntimeException ex, String source) {
    String description =
        Optional.ofNullable(ex.getStatus().getDescription()).filter(s -> !s.isBlank()).orElse("unreachable");
    return Status.fromCode(ex.getStatus().getCode())
        .withDescription(source + ": " + description)
        .asRuntimeException();
  }

  private LookExit toLookExit(RoomExitSnapshot exit) {
    return LookExit.newBuilder()
        .setLabel(exit.getLabel())
        .setTargetRoomId(exit.getTargetRoomId())
        .setDescription(exit.getDescription())
        .build();
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
