package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentEntry;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentKey;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.mapper.ContainerContentEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerContentRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class ContainerServiceImplTest {
  @Test
  void listContainerContentsReturnsMappedDtos() {
    ContainerContentRepository containerRepo = Mockito.mock(ContainerContentRepository.class);
    ContainerContentEntryMapper containerMapper =
        Mappers.getMapper(ContainerContentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerRepo,
            containerMapper,
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo);

    Character character = character(7L, 1L);
    Item container = item(99L, 1L, "Old Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);

    Item item = item(100L, 1L, "Torch", false);
    ContainerContentEntry entry = new ContainerContentEntry();
    ContainerContentKey key = new ContainerContentKey();
    key.setTenantId(1L);
    key.setContainerInstanceId(500L);
    key.setItemId(100L);
    entry.setId(key);
    entry.setContainerInstance(containerInstance);
    entry.setItem(item);
    entry.setQuantity(2);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 7L))
        .thenReturn(Optional.of(containerInstance));
    when(containerRepo.findByIdTenantIdAndIdContainerInstanceId(1L, 500L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    var result = service.listContainerContents(1L, 7L, 500L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("Torch", result.getContent().get(0).itemName());
    assertEquals(2, result.getContent().get(0).quantity());
  }

  @Test
  void putItemIntoContainerDeletesSelectedCarriedInstances() {
    ContainerContentRepository containerRepo = Mockito.mock(ContainerContentRepository.class);
    ContainerContentEntryMapper containerMapper =
        Mappers.getMapper(ContainerContentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerRepo,
            containerMapper,
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo);

    Character character = character(1L, 1L);
    Item container = item(2L, 1L, "Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);
    Item item = item(3L, 1L, "Torch", false);
    ItemInstance first = itemInstance(41L, 1L, character, item);
    ItemInstance second = itemInstance(42L, 1L, character, item);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 3L))
        .thenReturn(List.of(first, second));
    when(containerRepo.findById(any(ContainerContentKey.class))).thenReturn(Optional.empty());
    when(containerRepo.save(any(ContainerContentEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var stored = service.putItemIntoContainer(1L, 1L, 500L, 3L, 1);

    assertEquals("Torch", stored.itemName());
    assertEquals(1, stored.quantity());
    verify(itemInstanceRepo).delete(first);
  }

  @Test
  void putItemIntoContainerRejectsNestedContainers() {
    ContainerContentRepository containerRepo = Mockito.mock(ContainerContentRepository.class);
    ContainerContentEntryMapper containerMapper =
        Mappers.getMapper(ContainerContentEntryMapper.class);
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerRepo,
            containerMapper,
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo);

    Character character = character(1L, 1L);
    Item chest = item(2L, 1L, "Chest", true);
    Item pouch = item(3L, 1L, "Pouch", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(chest);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(pouch));

    assertThrows(
        IllegalArgumentException.class, () -> service.putItemIntoContainer(1L, 1L, 500L, 3L, 1));
  }

  private static Character character(Long id, Long tenantId) {
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    return character;
  }

  private static Item item(Long id, Long tenantId, String name, boolean container) {
    Item item = new Item();
    item.setId(id);
    item.setTenantId(tenantId);
    item.setName(name);
    item.setDescription(name + " desc");
    item.setContainer(container);
    return item;
  }

  private static ItemInstance itemInstance(Long id, Long tenantId, Character character, Item item) {
    ItemInstance instance = new ItemInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setCharacter(character);
    instance.setItem(item);
    return instance;
  }
}
