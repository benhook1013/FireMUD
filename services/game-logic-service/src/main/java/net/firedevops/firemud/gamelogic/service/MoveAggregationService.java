package net.firedevops.firemud.gamelogic.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
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
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MoveAggregationService {
  private static final Logger LOG = LoggerFactory.getLogger(MoveAggregationService.class);

  private final WorldManagementServiceGrpc.WorldManagementServiceBlockingStub worldStub;
  private final LookAggregationService lookAggregationService;
  private final MeterRegistry meterRegistry;

  public MoveResult resolve(MoveRequest request) {
    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(request)) {
      MoveResult.Builder builder = MoveResult.newBuilder();
      String direction = normalizeDirection(request.getDirection());
      if (direction.isBlank()) {
        return errorResponse(builder, "INVALID_ARGUMENT", "Direction must not be empty");
      }

      RoomInstanceRef currentRoom = request.getRoomInstance();
      if (currentRoom.getRoomInstanceId().isBlank()) {
        return errorResponse(
            builder, "INVALID_ARGUMENT", "room_instance.room_instance_id is required");
      }
      RoomSnapshot snapshot;
      try {
        GetRoomSnapshotResponse response =
            worldStub.getRoomSnapshot(
                GetRoomSnapshotRequest.newBuilder()
                    .setTenantId(resolveTenantId(request))
                    .setRoomInstance(currentRoom)
                    .setPreferredLocale(request.getPreferredLocale())
                    .setSessionAttestation(request.getSessionAttestation())
                    .build());
        if (response.hasError()) {
          return errorResponse(builder, response.getError(), "WorldManagementService");
        }
        snapshot = response.getSnapshot();
      } catch (StatusRuntimeException ex) {
        LOG.warn("WorldManagementService unavailable for MOVE request", ex);
        return errorResponse(builder, ex, "WorldManagementService");
      }

      Optional<RoomExitSnapshot> maybeExit = findExit(snapshot, direction);
      if (maybeExit.isEmpty()) {
        return errorResponse(
            builder,
            "INVALID_EXIT",
            "No exit " + direction + " from room " + currentRoom.getRoomInstanceId());
      }

      RoomExitSnapshot exit = maybeExit.get();
      try {
        String destinationGameInstanceId = resolveGameInstanceId(request, snapshot);
        LookResult destination =
            lookAggregationService.resolve(
                LookRequest.newBuilder()
                    .setTenantId(snapshot.getTenantId())
                    .setSessionId(request.getSessionId())
                    .setCharacterId(request.getCharacterId())
                    .setRoomInstance(
                        RoomInstanceRef.newBuilder()
                            .setTenantId(snapshot.getTenantId())
                            .setGameInstanceId(destinationGameInstanceId)
                            .setRoomInstanceId(exit.getTargetRoomInstanceId())
                            .build())
                    .setPreferredLocale(request.getPreferredLocale())
                    .setSessionAttestation(request.getSessionAttestation())
                    .build());
        return builder.setSuccess(true).setDestinationLook(destination).build();
      } catch (StatusRuntimeException ex) {
        LOG.warn("Look resolution failed after MOVE request", ex);
        return errorResponse(builder, ex, "LookAggregationService");
      } catch (RuntimeException ex) {
        LOG.warn("Unexpected MOVE resolution failure", ex);
        return errorResponse(builder, "UNAVAILABLE", "Move unavailable");
      }
    }
  }

  private Optional<RoomExitSnapshot> findExit(RoomSnapshot snapshot, String direction) {
    return snapshot.getExitsList().stream()
        .filter(exit -> direction.equals(normalizeDirection(exit.getDirection())))
        .findFirst();
  }

  private String normalizeDirection(String direction) {
    return direction == null ? "" : direction.trim().toUpperCase(Locale.ROOT);
  }

  private String resolveTenantId(MoveRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getTenantId().isBlank()) {
      return request.getRoomInstance().getTenantId();
    }
    return request.getTenantId();
  }

  private String resolveGameInstanceId(MoveRequest request, RoomSnapshot snapshot) {
    if (StringUtils.hasText(snapshot.getGameInstanceId())) {
      return snapshot.getGameInstanceId();
    }
    if (request.hasRoomInstance()
        && StringUtils.hasText(request.getRoomInstance().getGameInstanceId())) {
      return request.getRoomInstance().getGameInstanceId();
    }
    return "";
  }

  private MoveResult errorResponse(MoveResult.Builder builder, String code, String message) {
    ErrorDetail detail = GrpcAppErrors.error(meterRegistry, LOG, "ResolveMove", code, message);
    return builder.setSuccess(false).setError(detail).build();
  }

  private MoveResult errorResponse(
      MoveResult.Builder builder, StatusRuntimeException ex, String source) {
    String code = mapStatusCode(ex);
    String description =
        Optional.ofNullable(ex.getStatus().getDescription())
            .filter(s -> !s.isBlank())
            .orElse("unreachable");
    return errorResponse(builder, code, source + ": " + description);
  }

  private MoveResult errorResponse(MoveResult.Builder builder, ErrorDetail detail, String source) {
    String code = mapAppCode(detail.getCode());
    String message =
        Optional.ofNullable(detail.getMessage()).filter(s -> !s.isBlank()).orElse("unreachable");
    return errorResponse(builder, code, source + ": " + message);
  }

  private String mapAppCode(String code) {
    return mapAppCode(code, null);
  }

  private String mapAppCode(String code, String description) {
    String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "NOT_FOUND" -> "ROOM_NOT_FOUND";
      case "UNAVAILABLE" ->
          description != null && description.contains("EntityManagement")
              ? "ENTITY_UNAVAILABLE"
              : "WORLD_UNAVAILABLE";
      case "DEADLINE_EXCEEDED" -> "WORLD_UNAVAILABLE";
      case "PERMISSION_DENIED" -> "NOT_AUTHORIZED";
      default -> normalized.isBlank() ? "MOVE_UNAVAILABLE" : normalized;
    };
  }

  private String mapStatusCode(StatusRuntimeException ex) {
    Status.Code code = ex.getStatus().getCode();
    String description = Optional.ofNullable(ex.getStatus().getDescription()).orElse("");
    return mapAppCode(code.name(), description);
  }
}
