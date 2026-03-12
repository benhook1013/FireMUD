package net.firedevops.firemud.worldmanagement.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto.RoomExitSnapshotDto;
import net.firedevops.firemud.worldmanagement.mapper.RoomMapper;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.v1.GetRoomRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.UpdateWorldStateRequest;
import net.firedevops.firemud.worldmanagement.v1.UpdateWorldStateResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.lognet.springboot.grpc.GRpcService;

/** gRPC endpoints for the World Management Service. */
@GRpcService
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services and registry remain internal")
public class WorldManagementGrpcService
    extends WorldManagementServiceGrpc.WorldManagementServiceImplBase {
  private final PingService pingService;
  private final RoomService roomService;
  private final RoomMapper roomMapper;
  private final net.firedevops.firemud.worldmanagement.service.WorldEventService worldEventService;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private ErrorDetail error(String code, String message) {
    meterRegistry.counter("grpc.app_error", "code", code).increment();
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
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
          PingResponse.newBuilder().setError(error("INVALID_ARGUMENT", ex.getMessage())).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "worldGrpc.getRoom")
  public void getRoom(GetRoomRequest request, StreamObserver<GetRoomResponse> responseObserver) {
    try {
      Long roomId = Long.valueOf(resolveRoomId(request));
      Long tenantId = Long.valueOf(resolveTenantId(request));
      Optional<String> json =
          Optional.ofNullable(roomService.getRoom(tenantId, roomId)).map(this::toJson);
      if (json.isPresent()) {
        GetRoomResponse response = GetRoomResponse.newBuilder().setRoomJson(json.get()).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } else {
        GetRoomResponse response =
            GetRoomResponse.newBuilder().setError(error("NOT_FOUND", "room not found")).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (NumberFormatException ex) {
      GetRoomResponse response =
          GetRoomResponse.newBuilder().setError(error("INVALID_ARGUMENT", "invalid id")).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetRoomResponse response =
          GetRoomResponse.newBuilder().setError(error("NOT_FOUND", ex.getMessage())).build();
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
      RoomSnapshotDto snapshot = roomService.getRoomSnapshot(tenantId, roomId);
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder().setSnapshot(toProto(snapshot)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder()
              .setError(error("INVALID_ARGUMENT", "invalid id"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetRoomSnapshotResponse response =
          GetRoomSnapshotResponse.newBuilder()
              .setError(error("NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "worldGrpc.updateState")
  public void updateWorldState(
      UpdateWorldStateRequest request, StreamObserver<UpdateWorldStateResponse> responseObserver) {
    try {
      worldEventService.processDueEvents();
      UpdateWorldStateResponse response =
          UpdateWorldStateResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      UpdateWorldStateResponse response =
          UpdateWorldStateResponse.newBuilder()
              .setSuccess(false)
              .setError(error("INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
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
      applyLegacyAmbientState(builder, snapshot);
    }
    if (snapshot.roomFlags() != null) {
      builder.addAllRoomFlags(snapshot.roomFlags());
    }
    return builder.build();
  }

  @SuppressWarnings("deprecation")
  private void applyLegacyAmbientState(RoomSnapshot.Builder builder, RoomSnapshotDto snapshot) {
    builder.putAllAmbientState(snapshot.ambientState());
  }

  private RoomExitSnapshot toProto(RoomExitSnapshotDto exit) {
    RoomExitSnapshot.Builder builder =
        RoomExitSnapshot.newBuilder()
            .setExitId(exit.exitId().toString())
            .setTargetRoomInstanceId(exit.targetRoomId().toString())
            .setTargetRoomName(exit.targetRoomName())
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
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize room", e);
    }
  }

  private String resolveTenantId(GetRoomRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getTenantId().isBlank()) {
      return request.getRoomInstance().getTenantId();
    }
    return request.getTenantId();
  }

  @SuppressWarnings("deprecation")
  private String resolveRoomId(GetRoomRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getRoomInstanceId().isBlank()) {
      return request.getRoomInstance().getRoomInstanceId();
    }
    return request.getRoomId();
  }

  private String resolveTenantId(GetRoomSnapshotRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getTenantId().isBlank()) {
      return request.getRoomInstance().getTenantId();
    }
    return request.getTenantId();
  }

  @SuppressWarnings("deprecation")
  private String resolveRoomId(GetRoomSnapshotRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getRoomInstanceId().isBlank()) {
      return request.getRoomInstance().getRoomInstanceId();
    }
    return request.getRoomId();
  }
}
