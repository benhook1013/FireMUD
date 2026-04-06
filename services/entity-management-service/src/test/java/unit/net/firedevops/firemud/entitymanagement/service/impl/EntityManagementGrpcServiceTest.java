package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.PingService;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class EntityManagementGrpcServiceTest {
  private EntityManagementGrpcService newService(
      PingService pingService,
      CharacterService characterService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      RoomEntityService roomEntityService,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    ContainerService containerService = Mockito.mock(ContainerService.class);
    return new EntityManagementGrpcService(
        pingService,
        characterService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
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
    return new EntityManagementGrpcService(
        pingService,
        characterService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        meterRegistry);
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
    Mockito.when(containerService.listContainerContents(1L, 7L, 10L, Pageable.unpaged()))
        .thenReturn(
            new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(
                    new net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto(
                        1L, 7L, 10L, 11L, "Torch", "A small torch", 2))));
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
            .setContainerItemId("10")
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
    Mockito.when(containerService.putItemIntoContainer(1L, 7L, 10L, 11L, 2))
        .thenReturn(
            new net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto(
                1L, 7L, 10L, 11L, "Torch", "A small torch", 2));
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
            .setContainerItemId("10")
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
    Mockito.when(containerService.takeItemFromContainer(1L, 7L, 10L, 11L, 1))
        .thenReturn(
            new net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto(
                1L, 7L, 11L, "Torch", "A small torch", 1));
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
            .setContainerItemId("10")
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
    Mockito.when(characterService.listForTenantAndAccount(1L, 1L, Pageable.unpaged()))
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
  void queryInventoryReturnsItemsWithMetadata() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto(
            1L, 7L, 99L, "Torch", "A small torch", 2);
    Mockito.when(inventoryService.listInventory(1L, 7L, Pageable.unpaged()))
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

    assertEquals("Torch", ref.get().getItems(0).getItemName());
    assertEquals(2, ref.get().getItems(0).getQuantity());
  }

  @Test
  void listEquipmentReturnsEquipmentItems() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto(
            1L, 7L, "HEAD", 99L, "Leather Cap", "A small cap");
    Mockito.when(equipmentService.listEquipment(1L, 7L, Pageable.unpaged()))
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
        ListEquipmentRequest.newBuilder().setTenantId("1").setCharacterId("7").build(),
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
  }

  @Test
  void wearEquipmentReturnsEquippedItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto(
            1L, 7L, "HEAD", 99L, "Leather Cap", "A small cap");
    Mockito.when(equipmentService.wearItem(1L, 7L, 99L)).thenReturn(dto);
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
  }

  @Test
  void removeEquipmentReturnsRemovedItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto(
            1L, 7L, "HEAD", 99L, "Leather Cap", "A small cap");
    Mockito.when(equipmentService.removeWornItem(1L, 7L, "HEAD")).thenReturn(dto);
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
  }

  @Test
  void pickupItemFromRoomReturnsInventoryItem() {
    PingService pingService = Mockito.mock(PingService.class);
    CharacterService characterService = Mockito.mock(CharacterService.class);
    EquipmentService equipmentService = Mockito.mock(EquipmentService.class);
    InventoryService inventoryService = Mockito.mock(InventoryService.class);
    var dto =
        new net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto(
            1L, 7L, 99L, "Torch", "A small torch", 2);
    Mockito.when(inventoryService.pickupItemFromRoom(1L, 7L, "GI-1", "R-1", 99L, 1))
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
            .setRoomInstanceId("R-1")
            .setItemId("99")
            .setQuantity(1)
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
            1L, "GI-1", "R-1", 99L, "Torch", "A small torch", 1);
    Mockito.when(inventoryService.dropItemToRoom(1L, 7L, "GI-1", "R-1", 99L, 1)).thenReturn(dto);
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
            .setRoomInstanceId("R-1")
            .setItemId("99")
            .setQuantity(1)
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
}
