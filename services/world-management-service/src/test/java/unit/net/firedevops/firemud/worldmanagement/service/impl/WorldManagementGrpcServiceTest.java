package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.dto.RoomSnapshotDto;
import net.firedevops.firemud.worldmanagement.dto.RuntimeRoomDto;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationResultDto;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationRequest;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationResponse;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsRequest;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationResult;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class WorldManagementGrpcServiceTest {
  private WorldManagementGrpcService newService(
      PingService pingService, RoomService roomService, MeterRegistry meterRegistry) {
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    GameplaySessionAttestationService attestationService =
        Mockito.mock(GameplaySessionAttestationService.class);
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    return new WorldManagementGrpcService(
        pingService,
        roomService,
        activationService,
        digestService,
        Mockito.mock(WorldDesignMutationService.class),
        Mockito.mock(WorldUpgradeValidationService.class),
        attestationService,
        meterRegistry,
        new ObjectMapper());
  }

  private WorldManagementGrpcService newServiceWithoutContext(
      PingService pingService, RoomService roomService, MeterRegistry meterRegistry) {
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    GameplaySessionAttestationService attestationService =
        Mockito.mock(GameplaySessionAttestationService.class);
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    return new WorldManagementGrpcService(
        pingService,
        roomService,
        activationService,
        digestService,
        Mockito.mock(WorldDesignMutationService.class),
        Mockito.mock(WorldUpgradeValidationService.class),
        attestationService,
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
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            digestService,
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
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
  void applyWorldDesignMutationReturnsTypedResult() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldDesignMutationService mutationService = Mockito.mock(WorldDesignMutationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    Mockito.when(mutationService.applyMutation(Mockito.any()))
        .thenReturn(new WorldDesignMutationResultDto("APPLIED", 1L, 7L, 44L, 1L, null));
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            digestService,
            mutationService,
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    AtomicReference<ApplyWorldDesignMutationResponse> ref = new AtomicReference<>();
    service.applyWorldDesignMutation(
        ApplyWorldDesignMutationRequest.newBuilder()
            .setTenantId("1")
            .setVersionId("7")
            .setCommitId("commit-1")
            .setRevisionId("revision-1")
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId("44")
            .setRegion(RegionDesignMutation.newBuilder().setName("North").build())
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ApplyWorldDesignMutationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(
        WorldDesignMutationResult.WORLD_DESIGN_MUTATION_RESULT_APPLIED, ref.get().getResult());
    assertEquals("44", ref.get().getAggregateId());
    assertEquals(1L, ref.get().getDraftRevisionEpoch());
  }

  @Test
  void applyWorldDesignMutationRejectsZeroVersionIdBeforeMutation() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldDesignMutationService mutationService = Mockito.mock(WorldDesignMutationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            digestService,
            mutationService,
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    AtomicReference<ApplyWorldDesignMutationResponse> ref = new AtomicReference<>();
    service.applyWorldDesignMutation(
        ApplyWorldDesignMutationRequest.newBuilder()
            .setTenantId("1")
            .setVersionId("0")
            .setCommitId("commit-1")
            .setRevisionId("revision-1")
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId("44")
            .setRegion(RegionDesignMutation.newBuilder().setName("North").build())
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ApplyWorldDesignMutationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("versionId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(mutationService);
  }

  @Test
  void prepareWorldInstanceReturnsLifecycleSnapshot() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
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
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
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
  void prepareWorldInstanceRejectsZeroTenantId() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            activationService,
            Mockito.mock(WorldDraftDesignDigestService.class),
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    AtomicReference<PrepareWorldInstanceResponse> ref = new AtomicReference<>();
    service.prepareWorldInstance(
        PrepareWorldInstanceRequest.newBuilder()
            .setTenantId("0")
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(activationService);
  }

  @Test
  void activatePreparedWorldInstanceRejectsMalformedGameInstanceId() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldInstanceActivationService activationService =
        Mockito.mock(WorldInstanceActivationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            activationService,
            Mockito.mock(WorldDraftDesignDigestService.class),
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    AtomicReference<ActivatePreparedWorldInstanceResponse> ref = new AtomicReference<>();
    service.activatePreparedWorldInstance(
        ActivatePreparedWorldInstanceRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("abc")
            .setExpectedLifecycleEpoch(3L)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ActivatePreparedWorldInstanceResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("gameInstanceId must be numeric", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(activationService);
  }

  @Test
  void validateWorldUpgradeMappingsReturnsCompatibilityPayload() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    WorldUpgradeValidationService validationService =
        Mockito.mock(WorldUpgradeValidationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    Mockito.when(validationService.validateWorldUpgradeMappings(1L, 55L, 11L, "remap-1"))
        .thenReturn(
            new net.firedevops.firemud.worldmanagement.dto.WorldUpgradeValidationResultDto(
                List.of("S3"),
                List.of("world_instance", "room_instance"),
                false,
                "COMPATIBLE",
                false,
                List.of(),
                "remap-1"));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            digestService,
            Mockito.mock(WorldDesignMutationService.class),
            validationService,
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    AtomicReference<ValidateWorldUpgradeMappingsResponse> ref = new AtomicReference<>();
    service.validateWorldUpgradeMappings(
        ValidateWorldUpgradeMappingsRequest.newBuilder()
            .setTenantId("1")
            .setSourceGameInstanceId("55")
            .setTargetVersionId("11")
            .setRemapSetId("remap-1")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ValidateWorldUpgradeMappingsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(
        net.firedevops.firemud.worldmanagement.v1.UpgradeValidationResult
            .UPGRADE_VALIDATION_RESULT_COMPATIBLE,
        ref.get().getResult());
    assertEquals("remap-1", ref.get().getRemapSetId());
    assertEquals(2, ref.get().getCheckedFamiliesCount());
  }

  @Test
  void validateWorldUpgradeMappingsRejectsZeroTargetVersionId() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldUpgradeValidationService validationService =
        Mockito.mock(WorldUpgradeValidationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            Mockito.mock(WorldDraftDesignDigestService.class),
            Mockito.mock(WorldDesignMutationService.class),
            validationService,
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    AtomicReference<ValidateWorldUpgradeMappingsResponse> ref = new AtomicReference<>();
    service.validateWorldUpgradeMappings(
        ValidateWorldUpgradeMappingsRequest.newBuilder()
            .setTenantId("1")
            .setSourceGameInstanceId("55")
            .setTargetVersionId("0")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ValidateWorldUpgradeMappingsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("targetVersionId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(validationService);
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
                RoomInstanceRef.newBuilder()
                    .setGameInstanceId("41")
                    .setRoomInstanceId("R-1")
                    .build())
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
    assertEquals("tenantId must be numeric", ref.get().getError().getMessage());
  }

  @Test
  void getRoomReturnsTypedRuntimeRoomPayload() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    MeterRegistry meterRegistry = Mockito.mock(MeterRegistry.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(Mockito.mock(io.micrometer.core.instrument.Counter.class));
    Mockito.when(roomService.getRoom(1L, 41L, 1L))
        .thenReturn(new RuntimeRoomDto(1L, 1L, 41L, 7L, "Room A", "Seed room A"));
    WorldManagementGrpcService service = newService(pingService, roomService, meterRegistry);

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.GetRoomResponse> ref =
        new AtomicReference<>();
    service.getRoom(
        net.firedevops.firemud.worldmanagement.v1.GetRoomRequest.newBuilder()
            .setTenantId("1")
            .setSessionAttestation("probe")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setGameInstanceId("41")
                    .setRoomInstanceId("R-1")
                    .build())
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

    assertEquals("1", ref.get().getRoom().getTenantId());
    assertEquals("41", ref.get().getRoom().getGameInstanceId());
    assertEquals("R-1", ref.get().getRoom().getRoomInstanceId());
    assertEquals("7", ref.get().getRoom().getRegionId());
    assertEquals("Room A", ref.get().getRoom().getName());
    assertEquals("Seed room A", ref.get().getRoom().getDescription());
  }

  @Test
  void getRoomSnapshotMissingGameInstanceIdReturnsInvalidArgument() {
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
    assertEquals("gameInstanceId must be specified", ref.get().getError().getMessage());
  }

  @Test
  void getRoomRejectsMismatchedTenantIdsBeforeAttestationAndLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    GameplaySessionAttestationService attestationService =
        Mockito.mock(GameplaySessionAttestationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            Mockito.mock(WorldDraftDesignDigestService.class),
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            attestationService,
            meterRegistry,
            new ObjectMapper());

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.GetRoomResponse> ref =
        new AtomicReference<>();
    service.getRoom(
        net.firedevops.firemud.worldmanagement.v1.GetRoomRequest.newBuilder()
            .setTenantId("1")
            .setSessionAttestation("probe")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId("2")
                    .setGameInstanceId("41")
                    .setRoomInstanceId("R-1")
                    .build())
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
    assertEquals("tenantId must match roomInstance.tenantId", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(roomService, attestationService);
  }

  @Test
  void getRoomSnapshotRejectsBlankGameInstanceIdBeforeAttestationAndLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    GameplaySessionAttestationService attestationService =
        Mockito.mock(GameplaySessionAttestationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            Mockito.mock(WorldDraftDesignDigestService.class),
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            attestationService,
            meterRegistry,
            new ObjectMapper());

    AtomicReference<GetRoomSnapshotResponse> ref = new AtomicReference<>();
    service.getRoomSnapshot(
        GetRoomSnapshotRequest.newBuilder()
            .setTenantId("1")
            .setPreferredLocale("fr")
            .setSessionAttestation("probe")
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-1").build())
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("gameInstanceId must be specified", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(roomService, attestationService);
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
            .setSessionAttestation("probe")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setGameInstanceId("41")
                    .setRoomInstanceId("R-1")
                    .build())
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
                41L,
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
            .setSessionAttestation("probe")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setGameInstanceId("41")
                    .setRoomInstanceId("R-1")
                    .build())
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
    assertEquals("R-1", ref.get().getSnapshot().getRoomInstanceId());
    assertEquals("R-2", ref.get().getSnapshot().getExits(0).getTargetRoomInstanceId());
    assertEquals("1:41:R-1", ref.get().getSnapshot().getWorldSnapshotId());
    assertEquals("NORTH", ref.get().getSnapshot().getExits(0).getDirection());
    assertEquals("dim", ref.get().getSnapshot().getAmbientState().getWeather());
  }

  @Test
  void getRoomSnapshotAllowsInternalServiceReadPath() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    Mockito.when(roomService.getRoomSnapshot(1L, 41L, 1L, "fr"))
        .thenReturn(
            new RoomSnapshotDto(
                1L,
                1L,
                41L,
                "Room A",
                "Seed room A",
                "Seed room A",
                List.of(),
                Map.of(),
                List.of()));
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
            .setSessionAttestation("probe")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setGameInstanceId("41")
                    .setRoomInstanceId("R-1")
                    .build())
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
  void getRoomRejectsMalformedRuntimeRoomInstanceIdBeforeAttestationAndLookup() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    GameplaySessionAttestationService attestationService =
        Mockito.mock(GameplaySessionAttestationService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            Mockito.mock(WorldDraftDesignDigestService.class),
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            attestationService,
            meterRegistry,
            new ObjectMapper());

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.GetRoomResponse> ref =
        new AtomicReference<>();
    service.getRoom(
        net.firedevops.firemud.worldmanagement.v1.GetRoomRequest.newBuilder()
            .setTenantId("1")
            .setSessionAttestation("probe")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setGameInstanceId("41")
                    .setRoomInstanceId("room-antechamber")
                    .build())
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
    assertEquals(
        "roomInstanceId must be a runtime room id like R-1021", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(roomService, attestationService);
  }

  @Test
  void getRoomRejectsNonInternalGameplayCaller() {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    WorldDraftDesignDigestService digestService = Mockito.mock(WorldDraftDesignDigestService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("test-account", List.of(), Map.of("9", List.of("tenantAdmin")));
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            digestService,
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    AtomicReference<net.firedevops.firemud.worldmanagement.v1.GetRoomResponse> ref =
        new AtomicReference<>();
    service.getRoom(
        net.firedevops.firemud.worldmanagement.v1.GetRoomRequest.newBuilder()
            .setTenantId("1")
            .setSessionAttestation("probe")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setGameInstanceId("41")
                    .setRoomInstanceId("R-1")
                    .build())
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

    assertEquals("SESSION_ATTESTATION_INVALID", ref.get().getError().getCode());
    assertEquals(
        1.0,
        meterRegistry
            .get("grpc.app_error")
            .tag("code", "SESSION_ATTESTATION_INVALID")
            .counter()
            .count());
  }

  @Test
  void requireTenantAccessWhenPresentRejectsMalformedCurrentAccountClaim() throws Exception {
    PingService pingService = Mockito.mock(PingService.class);
    RoomService roomService = Mockito.mock(RoomService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("not-a-long", List.of(), Map.of());
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            Mockito.mock(WorldInstanceActivationService.class),
            Mockito.mock(WorldDraftDesignDigestService.class),
            Mockito.mock(WorldDesignMutationService.class),
            Mockito.mock(WorldUpgradeValidationService.class),
            Mockito.mock(GameplaySessionAttestationService.class),
            meterRegistry,
            new ObjectMapper());

    InvocationTargetException thrown =
        assertThrows(
            InvocationTargetException.class,
            () -> {
              var method =
                  WorldManagementGrpcService.class.getDeclaredMethod(
                      "requireTenantAccessWhenPresent", Long.class);
              method.setAccessible(true);
              method.invoke(service, 1L);
            });

    ResponseStatusException responseStatusException =
        assertInstanceOf(ResponseStatusException.class, thrown.getCause());
    assertEquals(HttpStatus.FORBIDDEN, responseStatusException.getStatusCode());
  }
}
