package net.firedevops.firemud.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.mapper.RoomMapper;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.RoomService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
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
      Long roomId = Long.valueOf(request.getRoomId());
      Long tenantId = Long.valueOf(request.getTenantId());
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

  private String toJson(RoomDto dto) {
    try {
      return objectMapper.writeValueAsString(dto);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize room", e);
    }
  }
}
