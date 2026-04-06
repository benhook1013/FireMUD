package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryKey;
import net.firedevops.firemud.entitymanagement.mapper.InventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.mapper.RoomGroundInventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class InventoryServiceImplTest {
  @Test
  void listInventoryReturnsMappedDtos() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(11L);
    Item item = new Item();
    item.setId(2L);
    item.setTenantId(11L);
    item.setName("Torch");
    item.setDescription("A small torch");
    item.setContainer(true);

    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(99L);
    containerInstance.setTenantId(11L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(item);

    InventoryEntry entry = new InventoryEntry();
    InventoryKey key = new InventoryKey();
    key.setCharacterId(1L);
    key.setItemId(2L);
    entry.setId(key);
    entry.setCharacter(character);
    entry.setItem(item);
    entry.setQuantity(3);

    when(charRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                11L, 1L, 2L))
        .thenReturn(Optional.of(containerInstance));
    when(inventoryRepo.findByIdCharacterIdAndCharacterTenantId(1L, 11L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    var result = service.listInventory(11L, 1L, Pageable.unpaged());
    assertEquals(1, result.getTotalElements());
    assertEquals(11L, result.getContent().get(0).tenantId());
    assertEquals("Torch", result.getContent().get(0).itemName());
    assertEquals(3, result.getContent().get(0).quantity());
    assertEquals(99L, result.getContent().get(0).containerInstanceId());
  }

  @Test
  void addItemRejectsCrossTenantOwnership() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service.addItem(1L, 1L, 2L, 1));
  }

  @Test
  void removeItemRejectsCrossTenantOwnership() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service.removeItem(1L, 1L, 2L));
  }

  @Test
  void dropItemMovesQuantityToRoomGround() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);
    item.setName("Torch");

    InventoryEntry carried = new InventoryEntry();
    InventoryKey inventoryKey = new InventoryKey();
    inventoryKey.setCharacterId(1L);
    inventoryKey.setItemId(2L);
    carried.setId(inventoryKey);
    carried.setCharacter(character);
    carried.setItem(item);
    carried.setQuantity(2);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(inventoryRepo.findById(inventoryKey)).thenReturn(Optional.of(carried));
    when(roomRepo.findById(any(RoomGroundInventoryKey.class))).thenReturn(Optional.empty());
    when(roomRepo.save(any(RoomGroundInventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(inventoryRepo.save(any(InventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var dropped = service.dropItemToRoom(1L, 1L, "GI-1", "R-1", 2L, 1);

    assertEquals("Torch", dropped.itemName());
    assertEquals(1, dropped.quantity());
    assertEquals(1, carried.getQuantity());
  }

  @Test
  void dropItemRejectsInsufficientQuantity() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);

    InventoryEntry carried = new InventoryEntry();
    InventoryKey inventoryKey = new InventoryKey();
    inventoryKey.setCharacterId(1L);
    inventoryKey.setItemId(2L);
    carried.setId(inventoryKey);
    carried.setCharacter(character);
    carried.setItem(item);
    carried.setQuantity(1);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(inventoryRepo.findById(inventoryKey)).thenReturn(Optional.of(carried));

    assertThrows(
        IllegalArgumentException.class, () -> service.dropItemToRoom(1L, 1L, "GI-1", "R-1", 2L, 2));
  }

  @Test
  void pickupItemMovesQuantityBackToInventory() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);
    item.setName("Torch");

    RoomGroundInventoryEntry roomEntry = new RoomGroundInventoryEntry();
    RoomGroundInventoryKey roomKey = new RoomGroundInventoryKey();
    roomKey.setTenantId(1L);
    roomKey.setGameInstanceId("GI-1");
    roomKey.setRoomInstanceId("R-1");
    roomKey.setItemId(2L);
    roomEntry.setId(roomKey);
    roomEntry.setItem(item);
    roomEntry.setQuantity(2);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(roomRepo.findById(roomKey)).thenReturn(Optional.of(roomEntry));
    when(roomRepo.save(any(RoomGroundInventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(inventoryRepo.save(any(InventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var pickedUp = service.pickupItemFromRoom(1L, 1L, "GI-1", "R-1", 2L, 1);

    assertEquals("Torch", pickedUp.itemName());
    assertEquals(1, pickedUp.quantity());
    assertEquals(1, roomEntry.getQuantity());
  }

  @Test
  void pickupItemRejectsInsufficientQuantity() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);

    RoomGroundInventoryEntry roomEntry = new RoomGroundInventoryEntry();
    RoomGroundInventoryKey roomKey = new RoomGroundInventoryKey();
    roomKey.setTenantId(1L);
    roomKey.setGameInstanceId("GI-1");
    roomKey.setRoomInstanceId("R-1");
    roomKey.setItemId(2L);
    roomEntry.setId(roomKey);
    roomEntry.setItem(item);
    roomEntry.setQuantity(1);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(roomRepo.findById(roomKey)).thenReturn(Optional.of(roomEntry));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.pickupItemFromRoom(1L, 1L, "GI-1", "R-1", 2L, 2));
  }

  @Test
  void roomGroundMethodsRejectBlankScopeBeforeRepositoryLookup() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.listRoomGroundItems(1L, " ", "R-1", Pageable.unpaged()));
    assertThrows(
        IllegalArgumentException.class, () -> service.dropItemToRoom(1L, 1L, "GI-1", "", 2L, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.pickupItemFromRoom(1L, 1L, null, "R-1", 2L, 1));

    verifyNoInteractions(roomRepo);
  }

  @Test
  void listRoomGroundItemsReturnsMappedDtos() {
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    RoomGroundInventoryRepository roomRepo = Mockito.mock(RoomGroundInventoryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    RoomGroundInventoryEntryMapper roomGroundMapper =
        Mappers.getMapper(RoomGroundInventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            inventoryRepo,
            inventoryMapper,
            containerInstanceRepo,
            roomRepo,
            roomGroundMapper,
            charRepo,
            itemRepo);

    Item item = new Item();
    item.setId(3L);
    item.setTenantId(2L);
    item.setName("Lantern");
    item.setDescription("A brass lantern");

    RoomGroundInventoryEntry entry = new RoomGroundInventoryEntry();
    RoomGroundInventoryKey key = new RoomGroundInventoryKey();
    key.setTenantId(2L);
    key.setGameInstanceId("GI-1");
    key.setRoomInstanceId("R-1");
    key.setItemId(3L);
    entry.setId(key);
    entry.setItem(item);
    entry.setQuantity(5);

    when(roomRepo.findByIdTenantIdAndIdGameInstanceIdAndIdRoomInstanceId(
            2L, "GI-1", "R-1", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    var result = service.listRoomGroundItems(2L, "GI-1", "R-1", Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("Lantern", result.getContent().get(0).itemName());
    assertEquals(5, result.getContent().get(0).quantity());
  }
}
