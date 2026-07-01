package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.GameplaySessionAttestationClaims;
import net.firedevops.firemud.common.security.GameplaySessionAttestationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.ActorConditionStateDto;
import net.firedevops.firemud.entitymanagement.dto.ActorResourceStateDto;
import net.firedevops.firemud.entitymanagement.dto.ActorStateDto;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService;
import net.firedevops.firemud.entitymanagement.service.ActorStateService;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService;
import net.firedevops.firemud.entitymanagement.service.EntityUpgradeValidationService;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.PingService;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionRequest;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionResponse;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.entitymanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryActorStateRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryActorStateResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityUpgradeMappingsRequest;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityUpgradeMappingsResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class EntityManagementGrpcServiceTest {
  private GameplaySessionAttestationService attestationService() {
    GameplaySessionAttestationService service =
        Mockito.mock(GameplaySessionAttestationService.class);
    Mockito.when(service.requireValid(Mockito.anyString()))
        .thenReturn(
            new GameplaySessionAttestationClaims(
                "GAMEPLAY_SESSION",
                "1",
                "41",
                "test-account",
                "7",
                "GI-1",
                "R-1",
                "demo",
                "production",
                "17",
                "SHARED"));
    return service;
  }

  private EntityMutationEffectReplayService effectReplayService() {
    EntityMutationEffectReplayService service =
        Mockito.mock(EntityMutationEffectReplayService.class);
    Mockito.when(
            service.execute(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any()))
        .thenAnswer(
            invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(3)).get());
    return service;
  }

  private EntityManagementGrpcService newService(
      PingService pingService,
      CharacterService characterService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      RoomEntityService roomEntityService,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    ContainerService containerService = Mockito.mock(ContainerService.class);
    return new EntityManagementGrpcService(
        pingService,
        characterService,
        digestService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        effectReplayService(),
        Mockito.mock(EntityUpgradeValidationService.class),
        attestationService(),
        meterRegistry);
  }

  private EntityManagementGrpcService newServiceWithoutContext(
      PingService pingService,
      CharacterService characterService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    SessionContext.clear();
    return new EntityManagementGrpcService(
        pingService,
        characterService,
        digestService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        effectReplayService(),
        Mockito.mock(EntityUpgradeValidationService.class),
        attestationService(),
        meterRegistry);
  }

  private EntityManagementGrpcService newService(
      PingService pingService,
      CharacterService characterService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    return new EntityManagementGrpcService(
        pingService,
        characterService,
        digestService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        effectReplayService(),
        Mockito.mock(EntityUpgradeValidationService.class),
        attestationService(),
        meterRegistry);
  }

  @Test
  void getDraftDesignDigestReturnsVersionScopedDigest() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    var meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    Mockito.when(digestService.getDraftDesignDigest("1", "7"))
        .thenReturn(
            new EntityDraftDesignDigestService.EntityDraftDesignDigest(
                "1", "7", "version:7", "digest-entity", 1));
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService,
            characterService,
            digestService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            effectReplayService(),
            Mockito.mock(EntityUpgradeValidationService.class),
            attestationService(),
            meterRegistry);

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
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

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
  void validateEntityUpgradeMappingsReturnsCompatibilityPayload() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityUpgradeValidationService validationService =
        Mockito.mock(EntityUpgradeValidationService.class);
    var meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    Mockito.when(validationService.validateEntityUpgradeMappings(1L, 55L, 11L, "remap-1"))
        .thenReturn(
            new net.firedevops.firemud.entitymanagement.dto.EntityUpgradeValidationResultDto(
                List.of("S3"),
                List.of("room_ground_inventory", "item_instances"),
                false,
                "COMPATIBLE",
                false,
                List.of(),
                "remap-1"));
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService,
            characterService,
            digestService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            effectReplayService(),
            validationService,
            attestationService(),
            meterRegistry);

    AtomicReference<ValidateEntityUpgradeMappingsResponse> ref = new AtomicReference<>();
    service.validateEntityUpgradeMappings(
        ValidateEntityUpgradeMappingsRequest.newBuilder()
            .setTenantId("1")
            .setSourceGameInstanceId("55")
            .setTargetVersionId("11")
            .setRemapSetId("remap-1")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ValidateEntityUpgradeMappingsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(
        net.firedevops.firemud.entitymanagement.v1.UpgradeValidationResult
            .UPGRADE_VALIDATION_RESULT_COMPATIBLE,
        ref.get().getResult());
    assertEquals("remap-1", ref.get().getRemapSetId());
    assertEquals(2, ref.get().getCheckedFamiliesCount());
  }

  @Test
  void listContainerContentsReturnsMappedItems() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    Mockito.when(
            containerService.listContainerContents(
                1L,
                7L,
                10L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                Pageable.unpaged()))
        .thenReturn(
            new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(
                    new net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto(
                        1L, 7L, 10L, 11L, "Torch", "A small torch", 2, null, null))));
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            meterRegistry);

    AtomicReference<ListContainerContentsResponse> ref = new AtomicReference<>();
    service.listContainerContents(
        ListContainerContentsRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setContainerInstanceId("10")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListContainerContentsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(1, ref.get().getItemsCount());
    assertEquals("Torch", ref.get().getItems(0).getItemName());
    assertEquals("10", ref.get().getItems(0).getContainerInstanceId());
  }

  @Test
  void listRoomGroundInventoryReturnsMappedItems() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    Mockito.when(inventoryService.listRoomGroundItems(1L, "GI-1", "R-1", Pageable.unpaged()))
        .thenReturn(
            new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(
                    new net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto(
                        1L, "GI-1", "R-1", 11L, "Torch", "A small torch", 2, null, null, null))));
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<ListRoomGroundInventoryResponse> ref = new AtomicReference<>();
    service.listRoomGroundInventory(
        ListRoomGroundInventoryRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("GI-1")
            .setRoomInstanceId("R-1")
            .setSessionAttestation("probe")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListRoomGroundInventoryResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(1, ref.get().getItemsCount());
    assertEquals("Torch", ref.get().getItems(0).getItemName());
    assertEquals(2, ref.get().getItems(0).getQuantity());
  }

  @Test
  void putItemIntoContainerReturnsMappedItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    Mockito.when(
            containerService.putItemIntoContainer(
                1L,
                7L,
                10L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                11L,
                null,
                null,
                2,
                null,
                "41"))
        .thenReturn(
            new net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto(
                1L, 7L, 10L, 11L, "Torch", "A small torch", 2, null, null));
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            meterRegistry);

    AtomicReference<PutItemIntoContainerResponse> ref = new AtomicReference<>();
    service.putItemIntoContainer(
        PutItemIntoContainerRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setContainerInstanceId("10")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setItemId("11")
            .setQuantity(2)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PutItemIntoContainerResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    ContainerItem item = ref.get().getContainerItem();
    assertEquals("Torch", item.getItemName());
    assertEquals(2, item.getQuantity());
    assertEquals("10", item.getContainerInstanceId());
  }

  @Test
  void putItemIntoContainerPassesExplicitItemInstanceId() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    Mockito.when(
            containerService.putItemIntoContainer(
                1L,
                7L,
                10L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                11L,
                44L,
                null,
                1,
                null,
                "41"))
        .thenReturn(
            new net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto(
                1L, 7L, 10L, 11L, "Torch", "A small torch", 1, 44L, "torch44"));
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            meterRegistry);

    AtomicReference<PutItemIntoContainerResponse> ref = new AtomicReference<>();
    service.putItemIntoContainer(
        PutItemIntoContainerRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setContainerInstanceId("10")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setItemId("11")
            .setQuantity(1)
            .setItemInstanceId("44")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PutItemIntoContainerResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    ContainerItem item = ref.get().getContainerItem();
    assertEquals("Torch", item.getItemName());
    assertEquals("44", item.getItemInstanceId());
    assertEquals("torch44", item.getVisibleRef());
  }

  @Test
  void takeItemFromContainerReturnsMappedInventoryItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    Mockito.when(
            containerService.takeItemFromContainer(
                1L,
                7L,
                10L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                11L,
                null,
                null,
                1,
                null,
                "41"))
        .thenReturn(
            new net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto(
                1L, 7L, 11L, "Torch", "A small torch", 1, null, null, null));
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            meterRegistry);

    AtomicReference<TakeItemFromContainerResponse> ref = new AtomicReference<>();
    service.takeItemFromContainer(
        TakeItemFromContainerRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setContainerInstanceId("10")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setItemId("11")
            .setQuantity(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(TakeItemFromContainerResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Torch", ref.get().getInventoryItem().getItemName());
  }

  @Test
  void takeItemFromContainerPassesExplicitItemInstanceId() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    Mockito.when(
            containerService.takeItemFromContainer(
                1L,
                7L,
                10L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                11L,
                44L,
                null,
                1,
                null,
                "41"))
        .thenReturn(
            new net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto(
                1L, 7L, 11L, "Torch", "A small torch", 1, 44L, null, "torch44"));
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            meterRegistry);

    AtomicReference<TakeItemFromContainerResponse> ref = new AtomicReference<>();
    service.takeItemFromContainer(
        TakeItemFromContainerRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setContainerInstanceId("10")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setItemId("11")
            .setItemInstanceId("44")
            .setQuantity(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(TakeItemFromContainerResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Torch", ref.get().getInventoryItem().getItemName());
    assertEquals("44", ref.get().getInventoryItem().getItemInstanceId());
    assertEquals("torch44", ref.get().getInventoryItem().getVisibleRef());
  }

  @Test
  void listRoomEntitiesAllowsInternalServiceReadPath() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    Mockito.when(roomEntityService.listEntities("1", "2", "3"))
        .thenReturn(
            List.of(
                new RoomEntityDto(
                    "entity-1",
                    "Lantern",
                    EntityType.ITEM,
                    null,
                    List.of(),
                    0,
                    ReloadHint.STABLE,
                    true,
                    null)));
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            meterRegistry);

    AtomicReference<ListRoomEntitiesResponse> ref = new AtomicReference<>();
    service.listRoomEntities(
        ListRoomEntitiesRequest.newBuilder()
            .setTenantId("1")
            .setRoomInstance(
                net.firedevops.firemud.shared.v1.RoomInstanceRef.newBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("2")
                    .setRoomInstanceId("3")
                    .build())
            .setSessionAttestation("probe")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListRoomEntitiesResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(false, ref.get().hasError());
    assertEquals(1, ref.get().getEntitiesCount());
    assertEquals("Lantern", ref.get().getEntities(0).getDisplayName());
  }

  @Test
  void queryInventoryRejectsNonInternalGameplayCaller() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("test-account", List.of(), Map.of("9", List.of("tenantAdmin")));
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService,
            characterService,
            digestService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            effectReplayService(),
            Mockito.mock(EntityUpgradeValidationService.class),
            attestationService(),
            meterRegistry);

    AtomicReference<QueryInventoryResponse> ref = new AtomicReference<>();
    service.queryInventory(
        QueryInventoryRequest.newBuilder().setTenantId("1").setCharacterId("7").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryInventoryResponse value) {
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
  void pingValidationErrorReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenThrow(new IllegalArgumentException("bad"));
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void pingUnexpectedErrorReturnsInternal() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenThrow(new RuntimeException("boom"));
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

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

    assertNotNull(ref.get());
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void listCharactersInvalidAccountIdReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse>
        ref = new AtomicReference<>();
    service.listCharactersByAccount(
        net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("bad")
            .setGameInstanceId("44")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse value) {
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
  void listCharactersUnexpectedErrorReturnsInternal() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    Mockito.when(
            characterService.listForGameplayScope(
                1L,
                1L,
                "44",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                Pageable.unpaged()))
        .thenThrow(new RuntimeException("boom"));
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse>
        ref = new AtomicReference<>();
    service.listCharactersByAccount(
        net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("1")
            .setGameInstanceId("44")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void listCharactersMissingTenantIdReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse>
        ref = new AtomicReference<>();
    service.listCharactersByAccount(
        net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest.newBuilder()
            .setAccountId("1")
            .setGameInstanceId("44")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse value) {
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
  void updateEntityRequiresResolvedPlayableStateScope() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    Mockito.when(
            characterService.updateEntity(
                1L,
                7L,
                "44",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(true);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            new SimpleMeterRegistry());

    AtomicReference<net.firedevops.firemud.entitymanagement.v1.UpdateEntityResponse> ref =
        new AtomicReference<>();
    service.updateEntity(
        net.firedevops.firemud.entitymanagement.v1.UpdateEntityRequest.newBuilder()
            .setTenantId("1")
            .setEntityId("7")
            .setGameInstanceId("44")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(
              net.firedevops.firemud.entitymanagement.v1.UpdateEntityResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals(true, ref.get().getSuccess());
    verify(characterService)
        .updateEntity(
            1L,
            7L,
            "44",
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_ISOLATED);
  }

  @Test
  void queryInventoryReturnsItemsWithMetadata() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto(
            1L, 7L, 99L, "Torch", "A small torch", 2, 55L, 55L, "torch12");
    Mockito.when(
            inventoryService.listInventory(
                1L,
                7L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                Pageable.unpaged()))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(dto)));
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<QueryInventoryResponse> ref = new AtomicReference<>();
    service.queryInventory(
        QueryInventoryRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryInventoryResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Torch", ref.get().getItems(0).getItemName());
    assertEquals(2, ref.get().getItems(0).getQuantity());
    assertEquals("55", ref.get().getItems(0).getContainerInstanceId());
    assertEquals("torch12", ref.get().getItems(0).getVisibleRef());
  }

  @Test
  void queryInventoryRejectsGameplayAttestationMissingRoutingBundle() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    GameplaySessionAttestationService attestationService = attestationService();
    Mockito.doThrow(
            new GameplaySessionAttestationException(
                "SESSION_ATTESTATION_INVALID",
                "Gameplay session attestation is missing pointerVersion"))
        .when(attestationService)
        .requireAdmittedRoutingBundle(Mockito.any());
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService,
            characterService,
            Mockito.mock(ActorStateService.class),
            Mockito.mock(ActorConditionMutationService.class),
            Mockito.mock(EntityDraftDesignDigestService.class),
            equipmentService,
            inventoryService,
            Mockito.mock(ContainerService.class),
            roomEntityService,
            Mockito.mock(EntityMutationEffectReplayService.class),
            Mockito.mock(EntityUpgradeValidationService.class),
            attestationService,
            meterRegistry);

    AtomicReference<QueryInventoryResponse> ref = new AtomicReference<>();
    service.queryInventory(
        QueryInventoryRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryInventoryResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("SESSION_ATTESTATION_INVALID", ref.get().getError().getCode());
  }

  @Test
  void queryActorStateReturnsResourcesAndActiveConditions() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    ActorStateService actorStateService = Mockito.mock(ActorStateService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    var actorState =
        new ActorStateDto(
            1L,
            "99",
            7L,
            List.of(new ActorResourceStateDto("health", 65L, 90L, 80L, "INTEGER", "EFFECT", "e1")),
            List.of(
                new ActorConditionStateDto(
                    "poisoned",
                    2,
                    "EFFECT",
                    "e1",
                    Instant.parse("2026-04-24T00:00:00Z"),
                    Instant.parse("2026-04-24T00:00:30Z"),
                    "{\"damage_per_tick\":3}")));
    Mockito.when(
            actorStateService.queryActorState(
                1L,
                7L,
                "99",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED))
        .thenReturn(actorState);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService,
            characterService,
            actorStateService,
            digestService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            effectReplayService(),
            Mockito.mock(EntityUpgradeValidationService.class),
            attestationService(),
            meterRegistry);

    AtomicReference<QueryActorStateResponse> ref = new AtomicReference<>();
    service.queryActorState(
        QueryActorStateRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("99")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED)
            .setSessionAttestation("attestation")
            .build(),
        new StreamObserver<QueryActorStateResponse>() {
          @Override
          public void onNext(QueryActorStateResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("99", ref.get().getGameInstanceId());
    assertEquals("health", ref.get().getResources(0).getStatKey());
    assertEquals(65L, ref.get().getResources(0).getCurrentValue());
    assertEquals(90L, ref.get().getResources(0).getMaxValue());
    assertEquals("poisoned", ref.get().getActiveConditions(0).getConditionKey());
    assertEquals("2026-04-24T00:00:30Z", ref.get().getActiveConditions(0).getExpiresAt());
  }

  @Test
  void applyActorConditionPersistsGameplayAttestedCondition() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    ActorStateService actorStateService = Mockito.mock(ActorStateService.class);
    ActorConditionMutationService conditionMutationService =
        Mockito.mock(ActorConditionMutationService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    EntityMutationEffectReplayService effectReplayService =
        Mockito.mock(EntityMutationEffectReplayService.class);
    Mockito.when(
            effectReplayService.execute(
                Mockito.anyLong(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any()))
        .thenAnswer(
            invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(3)).get());
    Mockito.when(
            conditionMutationService.applyCondition(
                1L,
                7L,
                "99",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED,
                "blocking",
                1,
                "ACTION_STATE",
                "effect-1",
                Instant.parse("2026-04-24T00:00:05Z"),
                "{\"modifiers\":[]}"))
        .thenReturn(
            new ActorConditionStateDto(
                "blocking",
                1,
                "ACTION_STATE",
                "effect-1",
                Instant.parse("2026-04-24T00:00:00Z"),
                Instant.parse("2026-04-24T00:00:05Z"),
                "{\"modifiers\":[]}"));
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService,
            characterService,
            actorStateService,
            conditionMutationService,
            digestService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            effectReplayService,
            Mockito.mock(EntityUpgradeValidationService.class),
            attestationService(),
            new SimpleMeterRegistry());

    AtomicReference<ApplyActorConditionResponse> ref = new AtomicReference<>();
    service.applyActorCondition(
        ApplyActorConditionRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("99")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED)
            .setSessionAttestation("attestation")
            .setConditionKey("blocking")
            .setStackCount(1)
            .setSourceType("ACTION_STATE")
            .setSourceId("effect-1")
            .setExpiresAt("2026-04-24T00:00:05Z")
            .setEffectPayloadJson("{\"modifiers\":[]}")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ApplyActorConditionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("blocking", ref.get().getActiveCondition().getConditionKey());
    assertEquals("2026-04-24T00:00:05Z", ref.get().getActiveCondition().getExpiresAt());
    verify(conditionMutationService)
        .applyCondition(
            1L,
            7L,
            "99",
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_ISOLATED,
            "blocking",
            1,
            "ACTION_STATE",
            "effect-1",
            Instant.parse("2026-04-24T00:00:05Z"),
            "{\"modifiers\":[]}");
    verify(effectReplayService)
        .execute(eq(1L), eq("effect-1"), eq("ApplyActorCondition"), any(), any());
  }

  @Test
  void applyActorConditionReplaysStoredResponseWithoutInvokingMutationService() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    ActorStateService actorStateService = Mockito.mock(ActorStateService.class);
    ActorConditionMutationService conditionMutationService =
        Mockito.mock(ActorConditionMutationService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    ContainerService containerService = Mockito.mock(ContainerService.class);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityDraftDesignDigestService digestService =
        Mockito.mock(EntityDraftDesignDigestService.class);
    EntityMutationEffectReplayService effectReplayService =
        Mockito.mock(EntityMutationEffectReplayService.class);
    ApplyActorConditionResponse replayed =
        ApplyActorConditionResponse.newBuilder()
            .setActiveCondition(
                net.firedevops.firemud.entitymanagement.v1.ActorConditionState.newBuilder()
                    .setConditionKey("blocking")
                    .setSourceType("ACTION_STATE")
                    .setSourceId("effect-1")
                    .setStartedAt("2026-04-24T00:00:00Z")
                    .setExpiresAt("2026-04-24T00:00:05Z")
                    .setEffectPayloadJson("{\"modifiers\":[]}")
                    .build())
            .build();
    Mockito.when(
            effectReplayService.execute(
                Mockito.eq(1L),
                Mockito.eq("effect-1"),
                Mockito.eq("ApplyActorCondition"),
                Mockito.any(),
                Mockito.any()))
        .thenReturn(replayed);
    SessionContext.setContext(
        "test-account", List.of(), Map.of(), true, "game-session-service", "test-instance");
    EntityManagementGrpcService service =
        new EntityManagementGrpcService(
            pingService,
            characterService,
            actorStateService,
            conditionMutationService,
            digestService,
            equipmentService,
            inventoryService,
            containerService,
            roomEntityService,
            effectReplayService,
            Mockito.mock(EntityUpgradeValidationService.class),
            attestationService(),
            new SimpleMeterRegistry());

    AtomicReference<ApplyActorConditionResponse> ref = new AtomicReference<>();
    service.applyActorCondition(
        ApplyActorConditionRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("99")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_ISOLATED)
            .setSessionAttestation("attestation")
            .setConditionKey("blocking")
            .setStackCount(1)
            .setSourceType("ACTION_STATE")
            .setSourceId("effect-1")
            .setExpiresAt("2026-04-24T00:00:05Z")
            .setEffectPayloadJson("{\"modifiers\":[]}")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ApplyActorConditionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("blocking", ref.get().getActiveCondition().getConditionKey());
    verify(conditionMutationService, never())
        .applyCondition(
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.anyString(),
            Mockito.anyInt(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.anyString());
  }

  @Test
  void listEquipmentReturnsEquipmentItems() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto(
            1L, 7L, "HEAD", 99L, "Leather Cap", "A small cap", 66L, 66L, "leathercap4");
    Mockito.when(
            equipmentService.listEquipment(
                1L,
                7L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                Pageable.unpaged()))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(dto)));
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<ListEquipmentResponse> ref = new AtomicReference<>();
    service.listEquipment(
        ListEquipmentRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListEquipmentResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Leather Cap", ref.get().getItems(0).getItemName());
    assertEquals("HEAD", ref.get().getItems(0).getSlot());
    assertEquals("66", ref.get().getItems(0).getContainerInstanceId());
    assertEquals("leathercap4", ref.get().getItems(0).getVisibleRef());
  }

  @Test
  void wearEquipmentReturnsEquippedItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto(
            1L, 7L, "HEAD", 99L, "Leather Cap", "A small cap", 66L, 66L, "leathercap4");
    Mockito.when(
            equipmentService.wearItem(
                1L,
                7L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                99L,
                null,
                null,
                "41"))
        .thenReturn(dto);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<WearEquipmentItemResponse> ref = new AtomicReference<>();
    service.wearEquipment(
        WearEquipmentItemRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setItemId("99")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(WearEquipmentItemResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Leather Cap", ref.get().getEquipmentItem().getItemName());
    assertEquals("HEAD", ref.get().getEquipmentItem().getSlot());
    assertEquals("66", ref.get().getEquipmentItem().getContainerInstanceId());
  }

  @Test
  void removeEquipmentReturnsRemovedItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto(
            1L, 7L, "HEAD", 99L, "Leather Cap", "A small cap", 66L, 66L, "leathercap4");
    Mockito.when(
            equipmentService.removeWornItem(
                1L,
                7L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "HEAD",
                null,
                "41"))
        .thenReturn(dto);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<RemoveEquipmentResponse> ref = new AtomicReference<>();
    service.removeEquipment(
        RemoveEquipmentRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setSlot("HEAD")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(RemoveEquipmentResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Leather Cap", ref.get().getEquipmentItem().getItemName());
    assertEquals("HEAD", ref.get().getEquipmentItem().getSlot());
    assertEquals("66", ref.get().getEquipmentItem().getContainerInstanceId());
  }

  @Test
  void pickupItemFromRoomReturnsInventoryItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto(
            1L, 7L, 99L, "Torch", "A small torch", 2, null, null, null);
    Mockito.when(
            inventoryService.pickupItemFromRoom(
                1L,
                7L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                99L,
                null,
                "",
                null,
                1,
                null,
                "41"))
        .thenReturn(dto);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<PickupItemFromRoomResponse> ref = new AtomicReference<>();
    service.pickupItemFromRoom(
        PickupItemFromRoomRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setRoomInstanceId("R-1")
            .setItemId("99")
            .setQuantity(1)
            .setSessionAttestation("attestation")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PickupItemFromRoomResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Torch", ref.get().getInventoryItem().getItemName());
    assertEquals(2, ref.get().getInventoryItem().getQuantity());
  }

  @Test
  void dropItemToRoomReturnsRoomGroundItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto(
            1L, "GI-1", "R-1", 99L, "Torch", "A small torch", 1, null, null, null);
    Mockito.when(
            inventoryService.dropItemToRoom(
                1L,
                7L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                99L,
                null,
                "",
                null,
                1,
                null,
                "41"))
        .thenReturn(dto);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    AtomicReference<DropItemToRoomResponse> ref = new AtomicReference<>();
    service.dropItemToRoom(
        DropItemToRoomRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setRoomInstanceId("R-1")
            .setItemId("99")
            .setQuantity(1)
            .setSessionAttestation("attestation")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(DropItemToRoomResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("Torch", ref.get().getRoomGroundItem().getItemName());
    assertEquals(1, ref.get().getRoomGroundItem().getQuantity());
  }

  @Test
  void dropItemToRoomPassesEffectIdThroughToInventoryService() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto(
            1L, "GI-1", "R-1", 99L, "Torch", "A small torch", 1, null, null, null);
    Mockito.when(
            inventoryService.dropItemToRoom(
                1L,
                7L,
                "GI-1",
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED,
                "R-1",
                99L,
                null,
                "",
                null,
                1,
                "effect-1",
                "41"))
        .thenReturn(dto);
    io.micrometer.core.instrument.MeterRegistry meterRegistry =
        Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
    io.micrometer.core.instrument.Counter counter =
        Mockito.mock(io.micrometer.core.instrument.Counter.class);
    Mockito.when(meterRegistry.counter(Mockito.anyString(), Mockito.any(String[].class)))
        .thenReturn(counter);
    RoomEntityService roomEntityService = Mockito.mock(RoomEntityService.class);
    EntityManagementGrpcService service =
        newService(
            pingService,
            characterService,
            equipmentService,
            inventoryService,
            roomEntityService,
            meterRegistry);

    service.dropItemToRoom(
        DropItemToRoomRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("GI-1")
            .setPlayableStateScope(
                net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                    .PLAYABLE_STATE_SCOPE_SHARED)
            .setRoomInstanceId("R-1")
            .setItemId("99")
            .setQuantity(1)
            .setEffectId("effect-1")
            .setSessionAttestation("attestation")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(DropItemToRoomResponse value) {}

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    verify(inventoryService)
        .dropItemToRoom(
            1L,
            7L,
            "GI-1",
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED,
            "R-1",
            99L,
            null,
            "",
            null,
            1,
            "effect-1",
            "41");
  }
}
