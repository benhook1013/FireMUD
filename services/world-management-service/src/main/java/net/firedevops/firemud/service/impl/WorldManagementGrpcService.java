package net.firedevops.firemud.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.mapper.RoomMapper;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.RoomService;
import net.firedevops.firemud.worldmanagement.v1.GetRoomRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.UpdateWorldStateRequest;
import net.firedevops.firemud.worldmanagement.v1.UpdateWorldStateResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.lognet.springboot.grpc.GRpcService;

/** gRPC endpoints for the World Management Service. */
@GRpcService
@RequiredArgsConstructor
public class WorldManagementGrpcService
    extends WorldManagementServiceGrpc.WorldManagementServiceImplBase {
  private final PingService pingService;
  private final RoomService roomService;
  private final RoomMapper roomMapper;
  private final net.firedevops.firemud.service.WorldEventService worldEventService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void getRoom(GetRoomRequest request, StreamObserver<GetRoomResponse> responseObserver) {
    try {
      Long roomId = Long.valueOf(request.getRoomId());
      Long tenantId = Long.valueOf(request.getTenantId());
      Optional<String> json =
          Optional.ofNullable(roomService.getRoom(tenantId, roomId)).map(this::toJson);
      if (json.isPresent()) {
        GetRoomResponse response = GetRoomResponse.newBuilder().setRoomJson(json.get()).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } else {
        responseObserver.onError(
            Status.NOT_FOUND.withDescription("room not found").asRuntimeException());
      }
    } catch (NumberFormatException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription("invalid id").asRuntimeException());
    }
  }

  @Override
  public void updateWorldState(
      UpdateWorldStateRequest request, StreamObserver<UpdateWorldStateResponse> responseObserver) {
    try {
      worldEventService.processDueEvents();
      UpdateWorldStateResponse response =
          UpdateWorldStateResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  private String toJson(RoomDto dto) {
    try {
      return objectMapper.writeValueAsString(dto);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize room", e);
    }
  }
}
