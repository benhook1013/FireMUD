package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemStack;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
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
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(7L, 1L);
    Item container = item(99L, 1L, "Old Chest", true, false);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);

    Item item = item(100L, 1L, "Torch", false, false);
    ItemInstance contained = itemInstance(300L, 1L, null, item);
    contained.setContainerInstance(containerInstance);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 7L))
        .thenReturn(Optional.of(containerInstance));
    when(itemInstanceRepo.findByTenantIdAndContainerInstance_IdOrderByIdAsc(
            1L, 500L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(contained)));
    when(itemStackRepo.findByTenantIdAndContainerInstance_IdOrderByIdAsc(
            1L, 500L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));

    var result = service.listContainerContents(1L, 7L, 500L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("Torch", result.getContent().get(0).itemName());
    assertEquals(1, result.getContent().get(0).quantity());
    assertEquals(300L, result.getContent().get(0).itemInstanceId());
  }

  @Test
  void listContainerContentsReturnsStackBackedDtos() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(7L, 1L);
    Item container = item(99L, 1L, "Old Chest", true, false);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);

    Item arrows = item(100L, 1L, "Arrows", false, true);
    ItemStack stack = new ItemStack();
    stack.setId(301L);
    stack.setTenantId(1L);
    stack.setContainerInstance(containerInstance);
    stack.setItem(arrows);
    stack.setStackFamilyKey("ammo/iron");
    stack.setCompatibilityFingerprint("item-definition:100");
    stack.setQuantity(4);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 7L))
        .thenReturn(Optional.of(containerInstance));
    when(itemInstanceRepo.findByTenantIdAndContainerInstance_IdOrderByIdAsc(
            1L, 500L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));
    when(itemStackRepo.findByTenantIdAndContainerInstance_IdOrderByIdAsc(
            1L, 500L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(stack)));

    var result = service.listContainerContents(1L, 7L, 500L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals(4, result.getContent().get(0).quantity());
    assertNull(result.getContent().get(0).itemInstanceId());
    assertEquals("ammo/iron", result.getContent().get(0).visibleRef());
  }

  @Test
  void putItemIntoContainerMovesSelectedInstancesIntoContainer() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item container = item(2L, 1L, "Chest", true, false);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);
    Item item = item(3L, 1L, "Torch", false, false);
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

    var stored = service.putItemIntoContainer(1L, 1L, 500L, 3L, null, null, 1);

    assertEquals("Torch", stored.itemName());
    assertEquals(1, stored.quantity());
    assertEquals(containerInstance, first.getContainerInstance());
  }

  @Test
  void putItemIntoContainerMovesStackQuantity() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item container = item(2L, 1L, "Chest", true, false);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);
    Item arrows = item(3L, 1L, "Arrows", false, true);
    arrows.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);
    ItemStack inventoryStack = new ItemStack();
    inventoryStack.setId(41L);
    inventoryStack.setTenantId(1L);
    inventoryStack.setCharacter(character);
    inventoryStack.setItem(arrows);
    inventoryStack.setStackFamilyKey("ammo/iron");
    inventoryStack.setCompatibilityFingerprint("item-definition:3:family:ammo/iron");
    inventoryStack.setQuantity(5);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(arrows));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                1L, 1L, 3L))
        .thenReturn(List.of(inventoryStack));
    when(itemStackRepo.findByTenantIdAndContainerInstance_IdAndItem_IdAndCompatibilityFingerprint(
            1L, 500L, 3L, "item-definition:3:family:ammo/iron"))
        .thenReturn(Optional.empty());
    when(itemStackRepo.save(any(ItemStack.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var stored = service.putItemIntoContainer(1L, 1L, 500L, 3L, null, null, 2);

    assertEquals(2, stored.quantity());
    assertEquals(3, inventoryStack.getQuantity());
    assertNull(stored.itemInstanceId());
    assertEquals("ammo/iron", stored.visibleRef());
    org.mockito.ArgumentCaptor<ItemStack> saved =
        org.mockito.ArgumentCaptor.forClass(ItemStack.class);
    Mockito.verify(itemStackRepo, Mockito.atLeastOnce()).save(saved.capture());
    ItemStack destination = saved.getAllValues().get(saved.getAllValues().size() - 1);
    assertEquals("ammo/iron", destination.getStackFamilyKey());
    assertEquals("item-definition:3:family:ammo/iron", destination.getCompatibilityFingerprint());
  }

  @Test
  void takeItemFromContainerUsesExplicitItemInstanceId() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item chest = item(2L, 1L, "Chest", true, false);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(chest);
    Item item = item(3L, 1L, "Torch", false, false);
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

    var taken = service.takeItemFromContainer(1L, 1L, 500L, 3L, 42L, null, 1);

    assertEquals("Torch", taken.itemName());
    assertEquals(character, second.getCharacter());
    assertNull(first.getCharacter());
  }

  @Test
  void putItemIntoContainerRejectsNestedContainerViaSharedHolderPolicy() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item container = item(2L, 1L, "Chest", true, false);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);
    Item nestedContainer = item(3L, 1L, "Pouch", true, false);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(nestedContainer));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.putItemIntoContainer(1L, 1L, 500L, 3L, null, null, 1));

    assertEquals("Nested containers are not supported", ex.getMessage());
  }

  @Test
  void putItemIntoContainerSelectsExplicitStackFamilyWhenMultipleFamiliesExist() {
    ContainerInstanceRepository containerInstanceRepo =
        Mockito.mock(ContainerInstanceRepository.class);
    ItemInstanceRepository itemInstanceRepo = Mockito.mock(ItemInstanceRepository.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ItemStackRepository itemStackRepo = Mockito.mock(ItemStackRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerInstanceRepo,
            itemInstanceRepo,
            characterRepo,
            itemRepo,
            itemStackRepo,
            new ItemTransferSupport(),
            new ContainerHolderSyncSupport(containerInstanceRepo));

    Character character = character(1L, 1L);
    Item container = item(2L, 1L, "Chest", true, false);
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setId(500L);
    containerInstance.setTenantId(1L);
    containerInstance.setCharacter(character);
    containerInstance.setItem(container);
    Item arrows = item(3L, 1L, "Arrows", false, true);
    arrows.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);
    ItemStack first = new ItemStack();
    first.setId(41L);
    first.setTenantId(1L);
    first.setCharacter(character);
    first.setItem(arrows);
    first.setStackFamilyKey("ammo/iron");
    first.setCompatibilityFingerprint("item-definition:3:family:ammo/iron");
    first.setQuantity(5);
    ItemStack second = new ItemStack();
    second.setId(42L);
    second.setTenantId(1L);
    second.setCharacter(character);
    second.setItem(arrows);
    second.setStackFamilyKey("ammo/steel");
    second.setCompatibilityFingerprint("item-definition:3:family:ammo/steel");
    second.setQuantity(4);

    when(characterRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(character));
    when(containerInstanceRepo.findAccessibleByIdAndTenantIdAndCharacterId(500L, 1L, 1L))
        .thenReturn(Optional.of(containerInstance));
    when(itemRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(arrows));
    when(itemStackRepo
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                1L, 1L, 3L))
        .thenReturn(List.of(first, second));
    when(itemStackRepo.findByTenantIdAndContainerInstance_IdAndItem_IdAndCompatibilityFingerprint(
            1L, 500L, 3L, "item-definition:3:family:ammo/steel"))
        .thenReturn(Optional.empty());
    when(itemStackRepo.save(any(ItemStack.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var stored = service.putItemIntoContainer(1L, 1L, 500L, 3L, null, "ammo/steel", 2);

    assertEquals("ammo/steel", stored.visibleRef());
    assertEquals(2, second.getQuantity());
    assertEquals(5, first.getQuantity());
  }

  private static Character character(Long id, Long tenantId) {
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    return character;
  }

  private static Item item(
      Long id, Long tenantId, String name, boolean container, boolean stackable) {
    Item item = new Item();
    item.setId(id);
    item.setTenantId(tenantId);
    item.setName(name);
    item.setDescription(name + " desc");
    item.setContainer(container);
    item.setStackable(stackable);
    item.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_ONLY);
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
