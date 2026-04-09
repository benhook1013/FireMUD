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

class ContainerServiceImplTest {
  @Test
  void listContainerContentsReturnsInstanceBackedDtos() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(7L, 1L);
    Item container = item(99L, 1L, "Old Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);

    Item item = item(100L, 1L, "Torch", false);
    ItemInstance contained = itemInstance(300L, 1L, null, item);
    contained.setContainerInstance(containerInstance);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 7L))
        .thenReturn(Optional.of(containerInstance));
    when(itemInstanceRepo.findByTenantIdAndContainerInstance_IdOrderByIdAsc(
            1L, 500L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(contained)));

    var result = service.listContainerContents(1L, 7L, 500L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("Torch", result.getContent().get(0).itemName());
    assertEquals(1, result.getContent().get(0).quantity());
    assertEquals(300L, result.getContent().get(0).itemInstanceId());
  }

  @Test
  void putItemIntoContainerMovesSelectedInstancesIntoContainer() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

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
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var stored = service.putItemIntoContainer(1L, 1L, 500L, 3L, null, 1);

    assertEquals("Torch", stored.itemName());
    assertEquals(1, stored.quantity());
    assertEquals(containerInstance, first.getContainerInstance());
  }

  @Test
  void putItemIntoContainerRejectsStaleSourceMismatch() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item container = item(2L, 1L, "Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);
    Item item = item(3L, 1L, "Torch", false);
    ItemInstance stale = itemInstance(41L, 1L, character, item);
    stale.setGameInstanceId("GI-1");
    stale.setRoomInstanceId("R-1");

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 3L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.putItemIntoContainer(1L, 1L, 500L, 3L, null, 1));
  }

  @Test
  void putItemIntoContainerUsesExplicitItemInstanceId() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

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
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var stored = service.putItemIntoContainer(1L, 1L, 500L, 3L, 42L, 1);

    assertEquals("Torch", stored.itemName());
    assertEquals(containerInstance, second.getContainerInstance());
    assertEquals(null, first.getContainerInstance());
  }

  @Test
  void putItemIntoContainerRejectsExplicitItemInstanceIdWithQuantityGreaterThanOne() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item container = item(2L, 1L, "Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);
    Item item = item(3L, 1L, "Torch", false);
    ItemInstance first = itemInstance(41L, 1L, character, item);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                1L, 1L, 3L))
        .thenReturn(List.of(first));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.putItemIntoContainer(1L, 1L, 500L, 3L, 41L, 2));
  }

  @Test
  void takeItemFromContainerRejectsStaleSourceMismatch() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item chest = item(2L, 1L, "Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(chest);
    Item item = item(3L, 1L, "Torch", false);
    ItemInstance stale = itemInstance(41L, 1L, character, item);
    stale.setContainerInstance(null);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo.findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(1L, 500L, 3L))
        .thenReturn(List.of(stale));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.takeItemFromContainer(1L, 1L, 500L, 3L, null, 1));
  }

  @Test
  void takeItemFromContainerUsesExplicitItemInstanceId() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item chest = item(2L, 1L, "Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(chest);
    Item item = item(3L, 1L, "Torch", false);
    ItemInstance first = itemInstance(41L, 1L, null, item);
    first.setContainerInstance(containerInstance);
    ItemInstance second = itemInstance(42L, 1L, null, item);
    second.setContainerInstance(containerInstance);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo.findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(1L, 500L, 3L))
        .thenReturn(List.of(first, second));
    when(itemInstanceRepo.save(any(ItemInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var taken = service.takeItemFromContainer(1L, 1L, 500L, 3L, 42L, 1);

    assertEquals("Torch", taken.itemName());
    assertEquals(character, second.getCharacter());
    assertEquals(null, first.getCharacter());
  }

  @Test
  void takeItemFromContainerRejectsExplicitItemInstanceIdWithQuantityGreaterThanOne() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item chest = item(2L, 1L, "Chest", true);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(chest);
    Item item = item(3L, 1L, "Torch", false);
    ItemInstance first = itemInstance(41L, 1L, null, item);
    first.setContainerInstance(containerInstance);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(item));
    when(itemInstanceRepo.findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(1L, 500L, 3L))
        .thenReturn(List.of(first));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.takeItemFromContainer(1L, 1L, 500L, 3L, 41L, 2));
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
