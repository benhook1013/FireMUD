package net.firedevops.firemud.gamelogic.health;

import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc.EntityManagementServiceBlockingStub;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc.WorldManagementServiceBlockingStub;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator for the downstream services required by the LOOK path. */
@Component("lookDependencyReadiness")
public class LookDependencyReadinessHealthIndicator implements HealthIndicator {
  private static final String CONTRACT = "ResolveLook";
  private static final String PROBE_TENANT_ID = "0";
  private static final String PROBE_ROOM_ID = "0";

  private final WorldManagementServiceBlockingStub worldStub;
  private final EntityManagementServiceBlockingStub entityStub;
  private final ReadinessTransitionTracker readinessTransitionTracker;

  public LookDependencyReadinessHealthIndicator(
      WorldManagementServiceBlockingStub worldStub,
      EntityManagementServiceBlockingStub entityStub,
      ReadinessTransitionTracker readinessTransitionTracker) {
    this.worldStub = worldStub;
    this.entityStub = entityStub;
    this.readinessTransitionTracker = readinessTransitionTracker;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    Map<String, Object> dependencies = new LinkedHashMap<>();

    try {
      GetRoomSnapshotResponse response =
          worldStub.getRoomSnapshot(
              GetRoomSnapshotRequest.newBuilder()
                  .setTenantId(PROBE_TENANT_ID)
                  .setRoomInstance(
                      RoomInstanceRef.newBuilder()
                          .setTenantId(PROBE_TENANT_ID)
                          .setRoomInstanceId(PROBE_ROOM_ID)
                          .build())
                  .build());
      if (response.hasError() && !isReachableAppError(response.getError().getCode())) {
        dependencies.put(
            "worldManagementService",
            DependencyReadinessSupport.downDependency(
                "getRoomSnapshot",
                "grpc:WorldManagementService#GetRoomSnapshot",
                response.getError().getCode() + ": " + response.getError().getMessage()));
        return readinessTransitionTracker.record(
            "game-logic-service",
            DependencyReadinessSupport.outOfService(
                CONTRACT, "worldManagementService", dependencies));
      }
      dependencies.put(
          "worldManagementService",
          DependencyReadinessSupport.upDependency(
              "getRoomSnapshot",
              "grpc:WorldManagementService#GetRoomSnapshot",
              response.hasError() ? response.getError().getCode() : "OK"));
    } catch (RuntimeException ex) {
      dependencies.put(
          "worldManagementService",
          DependencyReadinessSupport.downDependency(
              "getRoomSnapshot", "grpc:WorldManagementService#GetRoomSnapshot", message(ex)));
      return readinessTransitionTracker.record(
          "game-logic-service",
          DependencyReadinessSupport.outOfService(
              CONTRACT, "worldManagementService", dependencies));
    }

    try {
      ListRoomEntitiesResponse response =
          entityStub.listRoomEntities(
              ListRoomEntitiesRequest.newBuilder()
                  .setTenantId(PROBE_TENANT_ID)
                  .setRoomInstance(
                      RoomInstanceRef.newBuilder()
                          .setTenantId(PROBE_TENANT_ID)
                          .setRoomInstanceId(PROBE_ROOM_ID)
                          .build())
                  .build());
      if (response.hasError() && !isReachableAppError(response.getError().getCode())) {
        dependencies.put(
            "entityManagementService",
            DependencyReadinessSupport.downDependency(
                "listRoomEntities",
                "grpc:EntityManagementService#ListRoomEntities",
                response.getError().getCode() + ": " + response.getError().getMessage()));
        return readinessTransitionTracker.record(
            "game-logic-service",
            DependencyReadinessSupport.outOfService(
                CONTRACT, "entityManagementService", dependencies));
      }
      dependencies.put(
          "entityManagementService",
          DependencyReadinessSupport.upDependency(
              "listRoomEntities",
              "grpc:EntityManagementService#ListRoomEntities",
              response.hasError() ? response.getError().getCode() : "OK"));
    } catch (RuntimeException ex) {
      dependencies.put(
          "entityManagementService",
          DependencyReadinessSupport.downDependency(
              "listRoomEntities", "grpc:EntityManagementService#ListRoomEntities", message(ex)));
      return readinessTransitionTracker.record(
          "game-logic-service",
          DependencyReadinessSupport.outOfService(
              CONTRACT, "entityManagementService", dependencies));
    }

    return readinessTransitionTracker.record(
        "game-logic-service", DependencyReadinessSupport.up(CONTRACT, dependencies));
  }

  private static boolean isReachableAppError(String errorCode) {
    return "INVALID_ARGUMENT".equals(errorCode) || "NOT_FOUND".equals(errorCode);
  }

  private static String message(RuntimeException ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }
}
