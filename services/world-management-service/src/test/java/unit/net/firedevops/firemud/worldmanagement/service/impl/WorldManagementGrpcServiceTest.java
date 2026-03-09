package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.worldmanagement.mapper.RoomMapper;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class WorldManagementGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    RoomService roomService = Mockito.mock(RoomService.class);
    RoomMapper mapper = Mappers.getMapper(RoomMapper.class);
    var worldEventService = Mockito.mock(net.firedevops.firemud.worldmanagement.service.WorldEventService.class);
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService, roomService, mapper, worldEventService, meterRegistry);

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void getRoomInvalidIdReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    RoomMapper mapper = Mappers.getMapper(RoomMapper.class);
    var worldEventService = Mockito.mock(net.firedevops.firemud.worldmanagement.service.WorldEventService.class);
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService, roomService, mapper, worldEventService, meterRegistry);

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.GetRoomResponse> ref =
        new AtomicReference<>();
    service.getRoom(
        net.firedevops.firemud.worldmanagement.v1.GetRoomRequest.newBuilder()
            .setTenantId("bad")
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1").build())
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(net.firedevops.firemud.worldmanagement.v1.GetRoomResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void updateWorldStateSuccess() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    RoomMapper mapper = Mappers.getMapper(RoomMapper.class);
    var worldEventService = Mockito.mock(net.firedevops.firemud.worldmanagement.service.WorldEventService.class);
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService, roomService, mapper, worldEventService, meterRegistry);

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.UpdateWorldStateResponse> ref =
        new AtomicReference<>();
    service.updateWorldState(
        net.firedevops.firemud.worldmanagement.v1.UpdateWorldStateRequest.newBuilder()
            .setTenantId("1")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.worldmanagement.v1.UpdateWorldStateResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(true, ref.get().getSuccess());
  }
}
