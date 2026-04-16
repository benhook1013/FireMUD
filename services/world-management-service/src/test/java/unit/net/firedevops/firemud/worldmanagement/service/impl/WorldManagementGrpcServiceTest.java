package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class WorldManagementGrpcServiceTest {
  private WorldManagementGrpcService newService(
      PingService pingService, RoomService roomService, MeterRegistry meterRegistry) {
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    SessionContext.setContext("test-account", List.of("platformAdmin"), Map.of());
    return new WorldManagementGrpcService(
        pingService,
        roomService,
        activationService,
        digestService,
        meterRegistry,
        new ObjectMapper());
  }

  private WorldManagementGrpcService newServiceWithoutContext(
      PingService pingService, RoomService roomService, MeterRegistry meterRegistry) {
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    SessionContext.clear();
    return new WorldManagementGrpcService(
        pingService,
        roomService,
        activationService,
        digestService,
        meterRegistry,
        new ObjectMapper());
  }

  @Test
  void getDraftDesignDigestReturnsVersionScopedDigest() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    Mockito.when(digestService.getDraftDesignDigest("1", "7"))
        .thenReturn(
            new WorldDraftDesignDigestService.WorldDraftDesignDigest(
                "1", "7", "version:7", "digest-world", 1));
    SessionContext.setContext("test-account", List.of("platformAdmin"), Map.of());
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            digestService,
            meterRegistry,
            new ObjectMapper());

    AtomicReference<GetDraftDesignDigestResponse> ref = new AtomicReference<>();
    service.getDraftDesignDigest(
        GetDraftDesignDigestRequest.newBuilder().setTenantId("1").setVersionId("7").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetDraftDesignDigestResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("7", ref.get().getScopeValue());
    assertEquals("version:7", ref.get().getAppliedCommitId());
  }

  @Test
  void prepareWorldInstanceReturnsLifecycleSnapshot() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("test-account", List.of("platformAdmin"), Map.of());
    Mockito.when(
            activationService.prepareWorldInstance(
                Mockito.argThat(
                    request -> request.tenantId() == 1L && request.gameInstanceId() == 55L)))
        .thenReturn(
            new WorldInstanceLifecycleSnapshotDto(
                1L,
                55L,
                7L,
                "cp-1",
                "ld-1",
                11L,
                77L,
                "genrev-11",
                "prb:1:11:77",
                4L,
                1L,
                "PREPARING"));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            activationService,
            digestService,
            meterRegistry,
            new ObjectMapper());

    AtomicReference<PrepareWorldInstanceResponse> ref = new AtomicReference<>();
    service.prepareWorldInstance(
        PrepareWorldInstanceRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("55")
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
            .setLaunchDescriptorId("ld-1")
            .setVersionId("11")
            .setRuntimeFlagsJson("{}")
            .setGenerationConfigRevision("genrev-11")
            .setReleaseBundleId("77")
            .setPublishedReleaseBundleRef("prb:1:11:77")
            .setVersionStateEpoch(4L)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PrepareWorldInstanceResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("55", ref.get().getWorldInstance().getGameInstanceId());
    assertEquals(1L, ref.get().getWorldInstance().getLifecycleEpoch());
  }

  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    RoomService roomService = Mockito.mock(RoomService.class);
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service = newService(pingService, roomService, meterRegistry);

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
    WorldManagementGrpcService service = newService(pingService, roomService, meterRegistry);

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.GetRoomResponse> ref =
        new AtomicReference<>();
    service.getRoom(
        net.firedevops.firemud.worldmanagement.v1.GetRoomRequest.newBuilder()
            .setTenantId("bad")
            .setRoomInstance(
                RoomInstanceRef.newBuilder().setGameInstanceId("41").setRoomInstanceId("1").build())
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
    WorldManagementGrpcService service = newService(pingService, roomService, meterRegistry);

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
    Mockito.when(roomService.getRoomSnapshot(1L, 41L, 1L, "fr"))
        .thenThrow(new IllegalArgumentException("Room not found"));
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service = newService(pingService, roomService, meterRegistry);

    AtomicReference<GetRoomSnapshotResponse> ref = new AtomicReference<>();
    service.getRoomSnapshot(
        GetRoomSnapshotRequest.newBuilder()
            .setTenantId("1")
            .setPreferredLocale("fr")
            .setRoomInstance(
                RoomInstanceRef.newBuilder().setGameInstanceId("41").setRoomInstanceId("1").build())
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
    Mockito.when(roomService.getRoomSnapshot(1L, 41L, 1L, "fr"))
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
    WorldManagementGrpcService service = newService(pingService, roomService, meterRegistry);

    AtomicReference<GetRoomSnapshotResponse> ref = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    service.getRoomSnapshot(
        GetRoomSnapshotRequest.newBuilder()
            .setTenantId("1")
            .setPreferredLocale("fr")
            .setRoomInstance(
                RoomInstanceRef.newBuilder().setGameInstanceId("41").setRoomInstanceId("1").build())
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

  @Test
  void getRoomSnapshotAllowsUnauthenticatedInternalReadPath() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    Mockito.when(roomService.getRoomSnapshot(1L, 41L, 1L, "fr"))
        .thenReturn(
            new RoomSnapshotDto(
                1L, 1L, "Room A", "Seed room A", "Seed room A", List.of(), Map.of(), List.of()));
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    WorldManagementGrpcService service =
        newServiceWithoutContext(pingService, roomService, meterRegistry);

    AtomicReference<GetRoomSnapshotResponse> ref = new AtomicReference<>();
    service.getRoomSnapshot(
        GetRoomSnapshotRequest.newBuilder()
            .setTenantId("1")
            .setPreferredLocale("fr")
            .setRoomInstance(
                RoomInstanceRef.newBuilder().setGameInstanceId("41").setRoomInstanceId("1").build())
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

    assertNotNull(ref.get());
    assertEquals(false, ref.get().hasError());
    assertEquals("Room A", ref.get().getSnapshot().getRoomName());
  }

  @Test
  void getRoomReturnsPermissionDeniedWhenTenantAccessFails() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("test-account", List.of(), Map.of("9", List.of("admin")));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            digestService,
            meterRegistry,
            new ObjectMapper());

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.GetRoomResponse> ref =
        new AtomicReference<>();
    service.getRoom(
        net.firedevops.firemud.worldmanagement.v1.GetRoomRequest.newBuilder()
            .setTenantId("1")
            .setRoomInstance(
                RoomInstanceRef.newBuilder().setGameInstanceId("41").setRoomInstanceId("1").build())
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

    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    assertEquals(
        1.0,
        meterRegistry.get("grpc.app_error").tag("code", "PERMISSION_DENIED").counter().count());
  }
}
