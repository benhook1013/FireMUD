package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class WorldManagementGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    RoomService roomService = Mockito.mock(RoomService.class);
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(pingService, roomService, meterRegistry, new ObjectMapper());

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
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(pingService, roomService, meterRegistry, new ObjectMapper());

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
  void getRoomSnapshotMissingRoomIdReturnsInvalidArgument() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(pingService, roomService, meterRegistry, new ObjectMapper());

    AtomicReference<GetRoomSnapshotResponse> ref = new AtomicReference<>();
    service.getRoomSnapshot(
        GetRoomSnapshotRequest.newBuilder().setTenantId("1").setPreferredLocale("fr").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetRoomSnapshotResponse value) {
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
  void getRoomSnapshotMissingRoomReturnsNotFound() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    Mockito.when(roomService.getRoomSnapshot(1L, 1L, "fr"))
        .thenThrow(new IllegalArgumentException("Room not found"));
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(pingService, roomService, meterRegistry, new ObjectMapper());

    AtomicReference<GetRoomSnapshotResponse> ref = new AtomicReference<>();
    service.getRoomSnapshot(
        GetRoomSnapshotRequest.newBuilder()
            .setTenantId("1")
            .setPreferredLocale("fr")
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1").build())
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetRoomSnapshotResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
  }

  @Test
  void getRoomSnapshotReturnsSnapshot() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    Mockito.when(roomService.getRoomSnapshot(1L, 1L, "fr"))
        .thenReturn(
            new RoomSnapshotDto(
                1L,
                1L,
                "Room A",
                "Seed room A",
                "Seed room A",
                List.of(
                    new RoomSnapshotDto.RoomExitSnapshotDto(
                        1L, 2L, "Room B", "NORTH", "NORTH", "Leads toward Room B", 1)),
                Map.of("weather", "dim"),
                List.of()));
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(pingService, roomService, meterRegistry, new ObjectMapper());

    AtomicReference<GetRoomSnapshotResponse> ref = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    service.getRoomSnapshot(
        GetRoomSnapshotRequest.newBuilder()
            .setTenantId("1")
            .setPreferredLocale("fr")
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1").build())
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetRoomSnapshotResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            error.set(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals(null, error.get());
    assertNotNull(ref.get());
    assertEquals("Room A", ref.get().getSnapshot().getRoomName());
    assertEquals("1", ref.get().getSnapshot().getRoomInstanceId());
    assertEquals("2", ref.get().getSnapshot().getExits(0).getTargetRoomInstanceId());
    assertEquals("NORTH", ref.get().getSnapshot().getExits(0).getDirection());
    assertEquals("dim", ref.get().getSnapshot().getAmbientState().getWeather());
  }
}
