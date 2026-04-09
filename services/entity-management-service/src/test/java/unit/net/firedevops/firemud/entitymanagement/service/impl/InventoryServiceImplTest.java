package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class InventoryServiceImplTest {
  @Test
  void listInventoryReturnsInstanceBackedDtos() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            visibleRefAllocator,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 11L);
    Item item = item(2L, 11L, "Torch", true, null);
    ItemInstance instance = itemInstance(501L, 11L, character, item, null, null, null);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(99L);
    containerInstance.setTenantId(11L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(item);
    containerInstance.setItemInstance(instance);

    when(characterRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(instance)));
    when(containerInstanceRepo.findByItemInstance_Id(501L))
        .thenReturn(Optional.of(containerInstance));

    var result = service.listInventory(11L, 1L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals(501L, result.getContent().get(0).itemInstanceId());
    assertEquals(99L, result.getContent().get(0).containerInstanceId());
    assertEquals(1, result.getContent().get(0).quantity());
  }

  @Test
  void listInventoryKeepsDuplicateNonStackableItemsSeparate() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            visibleRefAllocator,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 11L);
    Item item = item(2L, 11L, "Torch", false, null);
    ItemInstance first = itemInstance(501L, 11L, character, item, null, null, null);
    first.setVisibleRef("torch1");
    first.setVisibleRefToken("torch");
    first.setVisibleRefSequence(1L);
    ItemInstance second = itemInstance(502L, 11L, character, item, null, null, null);
    second.setVisibleRef("torch2");
    second.setVisibleRefToken("torch");
    second.setVisibleRefSequence(2L);

    when(characterRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(first, second)));

    var result = service.listInventory(11L, 1L, Pageable.unpaged());

    assertEquals(2, result.getTotalElements());
    assertEquals(501L, result.getContent().get(0).itemInstanceId());
    assertEquals("torch1", result.getContent().get(0).visibleRef());
    assertEquals(1, result.getContent().get(0).quantity());
    assertEquals(502L, result.getContent().get(1).itemInstanceId());
    assertEquals("torch2", result.getContent().get(1).visibleRef());
    assertEquals(1, result.getContent().get(1).quantity());
  }

  @Test
  void addItemRejectsCrossTenantOwnership() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            visibleRefAllocator,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character(1L, 1L)));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service.addItem(1L, 1L, 2L, 1));
  }

  @Test
  void dropItemMovesSelectedInstanceToRoom() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            visibleRefAllocator,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null);
    ItemInstance first = itemInstance(41L, 1L, character, item, null, null, null);
    ItemInstance second = itemInstance(42L, 1L, character, item, null, null, null);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(first, second));
    when(itemInstanceRepo.save(Mockito.any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var dropped = service.dropItemToRoom(1L, 1L, "GI-1", "R-1", 2L, null, null, 1);

    assertEquals("GI-1", first.getGameInstanceId());
    assertEquals("R-1", first.getRoomInstanceId());
    assertEquals(41L, dropped.itemInstanceId());
    verify(itemInstanceRepo).save(first);
  }

  @Test
  void pickupItemRejectsMissingQuantity() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            visibleRefAllocator,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null);
    ItemInstance stale = itemInstance(41L, 1L, character, item, null, "GI-OLD", "R-OLD");

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.dropItemToRoom(1L, 1L, "GI-1", "R-1", 2L, null, null, 1));
  }

  @Test
  void pickupItemRejectsStaleSourceMismatch() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemVisibleRefAllocator visibleRefAllocator = Mockito.mock(ItemVisibleRefAllocator.class);
    InventoryServiceImpl service =
        new InventoryServiceImpl(
            itemInstanceRepo,
            containerInstanceRepo,
            characterRepo,
            itemRepo,
            visibleRefAllocator,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Torch", false, null);
    ItemInstance stale = itemInstance(41L, 1L, null, item, null, null, null);
    stale.setCharacter(character);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                1L, "GI-1", "R-1", 2L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.pickupItemFromRoom(1L, 1L, "GI-1", "R-1", 2L, null, null, 1));
  }

  private static Character character(Long id, Long tenantId) {
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    return character;
  }

  private static Item item(
      Long id, Long tenantId, String name, boolean container, String equipmentSlot) {
    Item item = new Item();
    item.setId(id);
    item.setTenantId(tenantId);
    item.setName(name);
    item.setDescription(name + " desc");
    item.setContainer(container);
    item.setEquipmentSlot(equipmentSlot);
    return item;
  }

  private static ItemInstance itemInstance(
      Long id,
      Long tenantId,
      Character character,
      Item item,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId) {
    ItemInstance instance = new ItemInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setCharacter(character);
    instance.setItem(item);
    instance.setEquipmentSlot(equipmentSlot);
    instance.setGameInstanceId(gameInstanceId);
    instance.setRoomInstanceId(roomInstanceId);
    instance.setVisibleRef("item" + id);
    instance.setVisibleRefToken("item");
    instance.setVisibleRefSequence(id);
    return instance;
  }
}
