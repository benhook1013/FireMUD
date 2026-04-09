package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

class EquipmentServiceImplTest {
  @Test
  void listEquipmentReturnsInstanceBackedDtos() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo, containerInstanceRepo, charRepo, itemRepo, new ItemTransferSupport());

    Character character = character(1L, 11L);
    Item item = item(2L, 11L, "Leather Cap", true, "HEAD");
    ItemInstance equipped = itemInstance(601L, 11L, character, item, "HEAD");
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(88L);
    containerInstance.setItemInstance(equipped);
    containerInstance.setItem(item);

    when(charRepo.findByIdAndTenantId(1L, 11L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
                11L, 1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(equipped)));
    when(containerInstanceRepo.findByItemInstance_Id(601L))
        .thenReturn(Optional.of(containerInstance));

    var result = service.listEquipment(11L, 1L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("HEAD", result.getContent().get(0).slot());
    assertEquals(601L, result.getContent().get(0).itemInstanceId());
    assertEquals(88L, result.getContent().get(0).containerInstanceId());
  }

  @Test
  void wearItemMovesCarriedInstanceIntoSlot() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo, containerInstanceRepo, charRepo, itemRepo, new ItemTransferSupport());

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Leather Cap", true, "head");
    ItemInstance carried = itemInstance(701L, 1L, character, item, null);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(77L);
    containerInstance.setItemInstance(carried);
    containerInstance.setItem(item);

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD"))
        .thenReturn(false);
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(carried));
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(containerInstanceRepo.findByItemInstance_Id(701L))
        .thenReturn(Optional.of(containerInstance));

    var equipped = service.wearItem(1L, 1L, 2L, null);

    assertEquals("HEAD", carried.getEquipmentSlot());
    assertEquals("HEAD", equipped.slot());
    assertEquals(77L, equipped.containerInstanceId());
  }

  @Test
  void wearItemRejectsStaleSourceMismatch() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo, containerInstanceRepo, charRepo, itemRepo, new ItemTransferSupport());

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Leather Cap", true, "head");
    ItemInstance stale = itemInstance(701L, 1L, character, item, null);
    stale.setGameInstanceId("GI-1");
    stale.setRoomInstanceId("R-1");

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD"))
        .thenReturn(false);
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 2L))
        .thenReturn(List.of(stale));

    assertThrows(IllegalArgumentException.class, () -> service.wearItem(1L, 1L, 2L, null));
  }

  @Test
  void removeWornItemRejectsStaleEquippedSourceMismatch() {
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    CharacterRepository charRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    EquipmentServiceImpl service =
        new EquipmentServiceImpl(
            itemInstanceRepo, containerInstanceRepo, charRepo, itemRepo, new ItemTransferSupport());

    Character character = character(1L, 1L);
    Item item = item(2L, 1L, "Leather Cap", true, "HEAD");
    ItemInstance stale = itemInstance(701L, 1L, character, item, "HEAD");
    stale.setContainerInstance(new ContainerInstance());

    when(charRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                1L, 1L, "HEAD"))
        .thenReturn(Optional.of(stale));

    assertThrows(IllegalArgumentException.class, () -> service.removeWornItem(1L, 1L, "HEAD"));
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
      Long id, Long tenantId, Character character, Item item, String equipmentSlot) {
    ItemInstance instance = new ItemInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setCharacter(character);
    instance.setItem(item);
    instance.setEquipmentSlot(equipmentSlot);
    return instance;
  }
}
