package net.firedevops.firemud.gamelogic.health;

import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.springframework.stereotype.Component;

/** Shared internal canary for the downstream calls made by {@code ResolveLook}. */
@Component
public final class ResolveLookPathProbe {
  private static final String WORLD_DEPENDENCY = "worldManagementService";
  private static final String ENTITY_DEPENDENCY = "entityManagementService";

  private final WorldManagementServiceBlockingStub worldStub;
  private final EntityManagementServiceBlockingStub entityStub;

  public ResolveLookPathProbe(
      WorldManagementServiceBlockingStub worldStub,
      EntityManagementServiceBlockingStub entityStub) {
    this.worldStub = worldStub;
    this.entityStub = entityStub;
  }

  public ProbeResult probe(String tenantId, String roomId) {
    Map<String, Object> dependencies = new LinkedHashMap<>();
    RoomInstanceRef roomInstance =
        RoomInstanceRef.newBuilder().setTenantId(tenantId).setRoomInstanceId(roomId).build();

    try {
      GetRoomSnapshotResponse response =
          worldStub.getRoomSnapshot(
              GetRoomSnapshotRequest.newBuilder()
                  .setTenantId(tenantId)
                  .setRoomInstance(roomInstance)
                  .build());
      if (response.hasError() && !isReachableAppError(response.getError().getCode())) {
        dependencies.put(
            WORLD_DEPENDENCY,
            DependencyReadinessSupport.downDependency(
                "getRoomSnapshot",
                "grpc:WorldManagementService#GetRoomSnapshot",
                response.getError().getCode() + ": " + response.getError().getMessage()));
        return ProbeResult.outOfService(WORLD_DEPENDENCY, dependencies);
      }
      dependencies.put(
          WORLD_DEPENDENCY,
          DependencyReadinessSupport.upDependency(
              "getRoomSnapshot",
              "grpc:WorldManagementService#GetRoomSnapshot",
              response.hasError() ? response.getError().getCode() : "OK"));
    } catch (RuntimeException ex) {
      dependencies.put(
          WORLD_DEPENDENCY,
          DependencyReadinessSupport.downDependency(
              "getRoomSnapshot", "grpc:WorldManagementService#GetRoomSnapshot", message(ex)));
      return ProbeResult.outOfService(WORLD_DEPENDENCY, dependencies);
    }

    try {
      ListRoomEntitiesResponse response =
          entityStub.listRoomEntities(
              ListRoomEntitiesRequest.newBuilder()
                  .setTenantId(tenantId)
                  .setRoomInstance(roomInstance)
                  .build());
      if (response.hasError() && !isReachableAppError(response.getError().getCode())) {
        dependencies.put(
            ENTITY_DEPENDENCY,
            DependencyReadinessSupport.downDependency(
                "listRoomEntities",
                "grpc:EntityManagementService#ListRoomEntities",
                response.getError().getCode() + ": " + response.getError().getMessage()));
        return ProbeResult.outOfService(ENTITY_DEPENDENCY, dependencies);
      }
      dependencies.put(
          ENTITY_DEPENDENCY,
          DependencyReadinessSupport.upDependency(
              "listRoomEntities",
              "grpc:EntityManagementService#ListRoomEntities",
              response.hasError() ? response.getError().getCode() : "OK"));
    } catch (RuntimeException ex) {
      dependencies.put(
          ENTITY_DEPENDENCY,
          DependencyReadinessSupport.downDependency(
              "listRoomEntities", "grpc:EntityManagementService#ListRoomEntities", message(ex)));
      return ProbeResult.outOfService(ENTITY_DEPENDENCY, dependencies);
    }

    return ProbeResult.up(dependencies);
  }

  private static boolean isReachableAppError(String errorCode) {
    return "INVALID_ARGUMENT".equals(errorCode) || "NOT_FOUND".equals(errorCode);
  }

  private static String message(RuntimeException ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }

  public record ProbeResult(
      boolean ready, String failingDependency, Map<String, Object> dependencies) {
    static ProbeResult up(Map<String, Object> dependencies) {
      return new ProbeResult(true, null, dependencies);
    }

    static ProbeResult outOfService(String failingDependency, Map<String, Object> dependencies) {
      return new ProbeResult(false, failingDependency, dependencies);
    }
  }
}
