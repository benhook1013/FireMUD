package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentEntry;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentKey;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.mapper.CharacterEquipmentEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterEquipmentRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class EquipmentServiceImplTest {
  @Test
  void listEquipmentReturnsMappedDtos() {
    CharacterEquipmentRepository equipmentRepo = Mockito.mock(CharacterEquipmentRepository.class);
    CharacterEquipmentEntryMapper equipmentMapper =
        Mappers.getMapper(CharacterEquipmentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            equipmentRepo,
            equipmentMapper,
            containerInstanceRepo,
            inventoryRepo,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(11L);

    Item item = new Item();
    item.setId(2L);
    item.setTenantId(11L);
    item.setName("Leather Cap");
    item.setDescription("A worn cap");
    item.setEquipmentSlot("HEAD");
    item.setContainer(true);

    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(88L);
    containerInstance.setTenantId(11L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(item);

    CharacterEquipmentEntry entry = new CharacterEquipmentEntry();
    CharacterEquipmentKey key = new CharacterEquipmentKey();
    key.setCharacterId(1L);
    key.setSlot("HEAD");
    entry.setId(key);
    entry.setCharacter(character);
    entry.setItem(item);

    when(charRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotAndItem_IdAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                11L, 1L, "HEAD", 2L))
        .thenReturn(Optional.of(containerInstance));
    when(equipmentRepo.findByIdCharacterIdAndCharacterTenantId(1L, 11L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    var result = service.listEquipment(11L, 1L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("HEAD", result.getContent().get(0).slot());
    assertEquals("Leather Cap", result.getContent().get(0).itemName());
    assertEquals(88L, result.getContent().get(0).containerInstanceId());
  }

  @Test
  void wearItemConsumesOneFromInventoryStackAndCreatesEquipmentEntry() {
    CharacterEquipmentRepository equipmentRepo = Mockito.mock(CharacterEquipmentRepository.class);
    CharacterEquipmentEntryMapper equipmentMapper =
        Mappers.getMapper(CharacterEquipmentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            equipmentRepo,
            equipmentMapper,
            containerInstanceRepo,
            inventoryRepo,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);

    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);
    item.setName("Leather Cap");
    item.setEquipmentSlot("head");
    item.setContainer(true);

    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(77L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setEquipmentSlot("HEAD");
    containerInstance.setItem(item);

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
    when(containerInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, 2L))
        .thenReturn(Optional.of(containerInstance));
    when(containerInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotAndItem_IdAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD", 2L))
        .thenReturn(Optional.of(containerInstance));
    when(equipmentRepo.findById(any(CharacterEquipmentKey.class))).thenReturn(Optional.empty());
    when(inventoryRepo.findById(inventoryKey)).thenReturn(Optional.of(carried));
    when(inventoryRepo.save(any(InventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(equipmentRepo.save(any(CharacterEquipmentEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var equipped = service.wearItem(1L, 1L, 2L);

    assertEquals("HEAD", equipped.slot());
    assertEquals("Leather Cap", equipped.itemName());
    assertEquals(1, carried.getQuantity());
    assertEquals(77L, equipped.containerInstanceId());
  }

  @Test
  void wearItemRejectsMissingEquipmentSlot() {
    CharacterEquipmentRepository equipmentRepo = Mockito.mock(CharacterEquipmentRepository.class);
    CharacterEquipmentEntryMapper equipmentMapper =
        Mappers.getMapper(CharacterEquipmentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            equipmentRepo,
            equipmentMapper,
            containerInstanceRepo,
            inventoryRepo,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);

    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);
    item.setName("Torch");

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));

    assertThrows(IllegalArgumentException.class, () -> service.wearItem(1L, 1L, 2L));
    verifyNoInteractions(equipmentRepo, inventoryRepo);
  }

  @Test
  void wearItemRejectsOccupiedSlot() {
    CharacterEquipmentRepository equipmentRepo = Mockito.mock(CharacterEquipmentRepository.class);
    CharacterEquipmentEntryMapper equipmentMapper =
        Mappers.getMapper(CharacterEquipmentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            equipmentRepo,
            equipmentMapper,
            containerInstanceRepo,
            inventoryRepo,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);

    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);
    item.setName("Leather Cap");
    item.setEquipmentSlot("HEAD");

    CharacterEquipmentEntry occupied = new CharacterEquipmentEntry();
    CharacterEquipmentKey key = new CharacterEquipmentKey();
    key.setCharacterId(1L);
    key.setSlot("HEAD");
    occupied.setId(key);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(equipmentRepo.findById(any(CharacterEquipmentKey.class)))
        .thenReturn(Optional.of(occupied));

    assertThrows(IllegalArgumentException.class, () -> service.wearItem(1L, 1L, 2L));
    verifyNoInteractions(inventoryRepo);
  }

  @Test
  void removeWornItemReturnsItemToInventory() {
    CharacterEquipmentRepository equipmentRepo = Mockito.mock(CharacterEquipmentRepository.class);
    CharacterEquipmentEntryMapper equipmentMapper =
        Mappers.getMapper(CharacterEquipmentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            equipmentRepo,
            equipmentMapper,
            containerInstanceRepo,
            inventoryRepo,
            charRepo,
            itemRepo);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);

    Item item = new Item();
    item.setId(2L);
    item.setTenantId(1L);
    item.setName("Leather Cap");
    item.setEquipmentSlot("HEAD");

    CharacterEquipmentEntry equipped = new CharacterEquipmentEntry();
    CharacterEquipmentKey key = new CharacterEquipmentKey();
    key.setCharacterId(1L);
    key.setSlot("HEAD");
    equipped.setId(key);
    equipped.setCharacter(character);
    equipped.setItem(item);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(equipmentRepo.findById(any(CharacterEquipmentKey.class)))
        .thenReturn(Optional.of(equipped));
    when(inventoryRepo.findById(any(InventoryKey.class))).thenReturn(Optional.empty());
    when(inventoryRepo.save(any(InventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var removed = service.removeWornItem(1L, 1L, "head");

    assertEquals("HEAD", removed.slot());
    assertEquals("Leather Cap", removed.itemName());
  }

  @Test
  void removeWornItemRejectsBlankSlotBeforeRepositoryLookup() {
    CharacterEquipmentRepository equipmentRepo = Mockito.mock(CharacterEquipmentRepository.class);
    CharacterEquipmentEntryMapper equipmentMapper =
        Mappers.getMapper(CharacterEquipmentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            equipmentRepo,
            equipmentMapper,
            containerInstanceRepo,
            inventoryRepo,
            charRepo,
            itemRepo);

    assertThrows(IllegalArgumentException.class, () -> service.removeWornItem(1L, 1L, " "));
    verifyNoInteractions(equipmentRepo, inventoryRepo, itemRepo);
  }
}
