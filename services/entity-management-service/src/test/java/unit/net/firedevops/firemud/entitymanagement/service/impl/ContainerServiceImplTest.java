package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentEntry;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentKey;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.mapper.ContainerContentEntryMapper;
import net.firedevops.firemud.entitymanagement.mapper.InventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerContentRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
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
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerRepo,
            containerMapper,
            inventoryRepo,
            inventoryMapper,
            characterRepo,
            itemRepo);

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);

    Item container = new Item();
    container.setId(99L);
    container.setTenantId(1L);
    container.setName("Old Chest");
    container.setContainer(true);

    Item item = new Item();
    item.setId(100L);
    item.setTenantId(1L);
    item.setName("Torch");
    item.setDescription("A small torch");

    InventoryEntry carriedContainer = new InventoryEntry();
    InventoryKey carriedContainerKey = new InventoryKey();
    carriedContainerKey.setCharacterId(7L);
    carriedContainerKey.setItemId(99L);
    carriedContainer.setId(carriedContainerKey);
    carriedContainer.setCharacter(character);
    carriedContainer.setItem(container);
    carriedContainer.setQuantity(1);

    ContainerContentEntry entry = new ContainerContentEntry();
    ContainerContentKey key = new ContainerContentKey();
    key.setTenantId(1L);
    key.setCharacterId(7L);
    key.setContainerItemId(99L);
    key.setItemId(100L);
    entry.setId(key);
    entry.setCharacter(character);
    entry.setContainerItem(container);
    entry.setItem(item);
    entry.setQuantity(2);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(99L, 1L)).thenReturn(Optional.of(container));
    when(inventoryRepo.findById(carriedContainerKey)).thenReturn(Optional.of(carriedContainer));
    when(containerRepo.findByIdTenantIdAndIdCharacterIdAndIdContainerItemId(
            1L, 7L, 99L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    var result = service.listContainerContents(1L, 7L, 99L, Pageable.unpaged());

    assertEquals(1, result.getTotalElements());
    assertEquals("Torch", result.getContent().get(0).itemName());
    assertEquals(2, result.getContent().get(0).quantity());
  }

  @Test
  void putItemIntoContainerMovesQuantityFromInventory() {
    ContainerContentRepository containerRepo = Mockito.mock(ContainerContentRepository.class);
    ContainerContentEntryMapper containerMapper =
        Mappers.getMapper(ContainerContentEntryMapper.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerRepo,
            containerMapper,
            inventoryRepo,
            inventoryMapper,
            characterRepo,
            itemRepo);

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);

    Item container = new Item();
    container.setId(99L);
    container.setTenantId(1L);
    container.setName("Old Chest");
    container.setContainer(true);

    Item item = new Item();
    item.setId(100L);
    item.setTenantId(1L);
    item.setName("Torch");

    InventoryKey containerKey = new InventoryKey();
    containerKey.setCharacterId(7L);
    containerKey.setItemId(99L);
    InventoryEntry carriedContainer = new InventoryEntry();
    carriedContainer.setId(containerKey);
    carriedContainer.setCharacter(character);
    carriedContainer.setItem(container);
    carriedContainer.setQuantity(1);

    InventoryKey carriedKey = new InventoryKey();
    carriedKey.setCharacterId(7L);
    carriedKey.setItemId(100L);
    InventoryEntry carriedItem = new InventoryEntry();
    carriedItem.setId(carriedKey);
    carriedItem.setCharacter(character);
    carriedItem.setItem(item);
    carriedItem.setQuantity(3);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(99L, 1L)).thenReturn(Optional.of(container));
    when(itemRepo.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(item));
    when(inventoryRepo.findById(containerKey)).thenReturn(Optional.of(carriedContainer));
    when(inventoryRepo.findById(carriedKey)).thenReturn(Optional.of(carriedItem));
    when(containerRepo.findById(any(ContainerContentKey.class))).thenReturn(Optional.empty());
    when(containerRepo.save(any(ContainerContentEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(inventoryRepo.save(any(InventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var stored = service.putItemIntoContainer(1L, 7L, 99L, 100L, 2);

    assertEquals("Torch", stored.itemName());
    assertEquals(2, stored.quantity());
    assertEquals(1, carriedItem.getQuantity());
  }

  @Test
  void takeItemFromContainerMovesQuantityBackToInventory() {
    ContainerContentRepository containerRepo = Mockito.mock(ContainerContentRepository.class);
    ContainerContentEntryMapper containerMapper =
        Mappers.getMapper(ContainerContentEntryMapper.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerRepo,
            containerMapper,
            inventoryRepo,
            inventoryMapper,
            characterRepo,
            itemRepo);

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);

    Item container = new Item();
    container.setId(99L);
    container.setTenantId(1L);
    container.setName("Old Chest");
    container.setContainer(true);

    Item item = new Item();
    item.setId(100L);
    item.setTenantId(1L);
    item.setName("Torch");

    InventoryKey containerKey = new InventoryKey();
    containerKey.setCharacterId(7L);
    containerKey.setItemId(99L);
    InventoryEntry carriedContainer = new InventoryEntry();
    carriedContainer.setId(containerKey);
    carriedContainer.setCharacter(character);
    carriedContainer.setItem(container);
    carriedContainer.setQuantity(1);

    ContainerContentKey contentKey = new ContainerContentKey();
    contentKey.setTenantId(1L);
    contentKey.setCharacterId(7L);
    contentKey.setContainerItemId(99L);
    contentKey.setItemId(100L);
    ContainerContentEntry entry = new ContainerContentEntry();
    entry.setId(contentKey);
    entry.setCharacter(character);
    entry.setContainerItem(container);
    entry.setItem(item);
    entry.setQuantity(2);

    InventoryKey returnedInventoryKey = new InventoryKey();
    returnedInventoryKey.setCharacterId(7L);
    returnedInventoryKey.setItemId(100L);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(99L, 1L)).thenReturn(Optional.of(container));
    when(itemRepo.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(item));
    when(inventoryRepo.findById(containerKey)).thenReturn(Optional.of(carriedContainer));
    when(containerRepo.findByIdTenantIdAndIdCharacterIdAndIdContainerItemIdAndIdItemId(
            1L, 7L, 99L, 100L))
        .thenReturn(Optional.of(entry));
    when(inventoryRepo.findById(returnedInventoryKey)).thenReturn(Optional.empty());
    when(inventoryRepo.save(any(InventoryEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var taken = service.takeItemFromContainer(1L, 7L, 99L, 100L, 1);

    assertEquals("Torch", taken.itemName());
    assertEquals(1, taken.quantity());
    assertEquals(1, entry.getQuantity());
  }

  @Test
  void putRejectsNestedContainers() {
    ContainerContentRepository containerRepo = Mockito.mock(ContainerContentRepository.class);
    ContainerContentEntryMapper containerMapper =
        Mappers.getMapper(ContainerContentEntryMapper.class);
    InventoryEntryRepository inventoryRepo = Mockito.mock(InventoryEntryRepository.class);
    InventoryEntryMapper inventoryMapper = Mappers.getMapper(InventoryEntryMapper.class);
    CharacterRepository characterRepo = Mockito.mock(CharacterRepository.class);
    ItemRepository itemRepo = Mockito.mock(ItemRepository.class);
    ContainerServiceImpl service =
        new ContainerServiceImpl(
            containerRepo,
            containerMapper,
            inventoryRepo,
            inventoryMapper,
            characterRepo,
            itemRepo);

    Character character = new Character();
    character.setId(7L);
    character.setTenantId(1L);

    Item container = new Item();
    container.setId(99L);
    container.setTenantId(1L);
    container.setName("Old Chest");
    container.setContainer(true);

    Item nestedContainer = new Item();
    nestedContainer.setId(100L);
    nestedContainer.setTenantId(1L);
    nestedContainer.setName("Small Box");
    nestedContainer.setContainer(true);

    InventoryKey containerKey = new InventoryKey();
    containerKey.setCharacterId(7L);
    containerKey.setItemId(99L);
    InventoryEntry carriedContainer = new InventoryEntry();
    carriedContainer.setId(containerKey);
    carriedContainer.setCharacter(character);
    carriedContainer.setItem(container);
    carriedContainer.setQuantity(1);

    InventoryKey nestedKey = new InventoryKey();
    nestedKey.setCharacterId(7L);
    nestedKey.setItemId(100L);
    InventoryEntry nestedEntry = new InventoryEntry();
    nestedEntry.setId(nestedKey);
    nestedEntry.setCharacter(character);
    nestedEntry.setItem(nestedContainer);
    nestedEntry.setQuantity(1);

    when(characterRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(character));
    when(itemRepo.findByIdAndTenantId(99L, 1L)).thenReturn(Optional.of(container));
    when(itemRepo.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(nestedContainer));
    when(inventoryRepo.findById(containerKey)).thenReturn(Optional.of(carriedContainer));
    when(inventoryRepo.findById(nestedKey)).thenReturn(Optional.of(nestedEntry));

    assertThrows(
        IllegalArgumentException.class, () -> service.putItemIntoContainer(1L, 7L, 99L, 100L, 1));
  }
}
