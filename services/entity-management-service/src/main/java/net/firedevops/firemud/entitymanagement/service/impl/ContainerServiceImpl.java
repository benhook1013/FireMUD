package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentEntry;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentKey;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.mapper.ContainerContentEntryMapper;
import net.firedevops.firemud.entitymanagement.mapper.InventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerContentRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContainerServiceImpl implements ContainerService {
  private final ContainerContentRepository containerContentRepository;
  private final ContainerContentEntryMapper containerContentMapper;
  private final ContainerInstanceRepository containerInstanceRepository;
  private final InventoryEntryRepository inventoryRepository;
  private final InventoryEntryMapper inventoryMapper;
  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "container.list")
  public Page<ContainerContentEntryDto> listContainerContents(
      Long tenantId, Long characterId, Long containerInstanceId, Pageable pageable) {
    Character character = requireCharacter(tenantId, characterId);
    ContainerInstance containerInstance =
        requireContainerInstanceAccessibleToCharacter(
            character.getId(), tenantId, containerInstanceId);
    return containerContentRepository
        .findByIdTenantIdAndIdContainerInstanceId(tenantId, containerInstance.getId(), pageable)
        .map(containerContentMapper::toDto);
  }

  @Override
  @Transactional
  @Timed(value = "container.put")
  public ContainerContentEntryDto putItemIntoContainer(
      Long tenantId, Long characterId, Long containerInstanceId, Long itemId, int quantity) {
    requirePositiveQuantity(quantity);
    Character character = requireCharacter(tenantId, characterId);
    ContainerInstance containerInstance =
        requireContainerInstanceAccessibleToCharacter(
            character.getId(), tenantId, containerInstanceId);
    Item containerItem = containerInstance.getItem();
    Item item = requireItem(tenantId, itemId);
    if (!containerItem.isContainer()) {
      throw new IllegalArgumentException("Item is not a container");
    }
    if (item.isContainer()) {
      throw new IllegalArgumentException("Nested containers are not supported");
    }
    if (containerItem.getId().equals(itemId)) {
      throw new IllegalArgumentException("Item cannot be placed into itself");
    }
    InventoryEntry carried = requireInventoryEntry(character.getId(), itemId);
    if (carried.getQuantity() < quantity) {
      throw new IllegalArgumentException("Not enough quantity to put into container");
    }
    adjustInventoryQuantity(carried, -quantity);
    ContainerContentEntry entry = upsertContainerContentEntry(containerInstance, item, quantity);
    return containerContentMapper.toDto(entry);
  }

  @Override
  @Transactional
  @Timed(value = "container.take")
  public InventoryEntryDto takeItemFromContainer(
      Long tenantId, Long characterId, Long containerInstanceId, Long itemId, int quantity) {
    requirePositiveQuantity(quantity);
    Character character = requireCharacter(tenantId, characterId);
    ContainerInstance containerInstance =
        requireContainerInstanceAccessibleToCharacter(
            character.getId(), tenantId, containerInstanceId);
    Item item = requireItem(tenantId, itemId);
    ContainerContentEntry entry =
        requireContainerContentEntry(tenantId, containerInstance.getId(), itemId);
    if (entry.getQuantity() < quantity) {
      throw new IllegalArgumentException("Not enough quantity in container");
    }
    adjustContainerContentQuantity(entry, -quantity);
    InventoryEntry carried = upsertInventoryEntry(character, item, quantity);
    return inventoryMapper.toDto(carried);
  }

  private Character requireCharacter(Long tenantId, Long characterId) {
    return characterRepository
        .findByIdAndTenantId(characterId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("Character not found for tenant"));
  }

  private Item requireItem(Long tenantId, Long itemId) {
    return itemRepository
        .findByIdAndTenantId(itemId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("Item not found for tenant"));
  }

  private ContainerInstance requireContainerInstanceAccessibleToCharacter(
      Long characterId, Long tenantId, Long containerInstanceId) {
    ContainerInstance containerInstance =
        containerInstanceRepository
            .findAccessibleByIdAndTenantIdAndCharacterId(containerInstanceId, tenantId, characterId)
            .orElseThrow(() -> new IllegalArgumentException("Container instance not found"));
    Item containerItem = containerInstance.getItem();
    if (!containerItem.isContainer()) {
      throw new IllegalArgumentException("Item is not a container");
    }
    return containerInstance;
  }

  private InventoryEntry requireInventoryEntry(Long characterId, Long itemId) {
    return inventoryRepository
        .findById(inventoryKey(characterId, itemId))
        .orElseThrow(() -> new IllegalArgumentException("Inventory item not found"));
  }

  private ContainerContentEntry requireContainerContentEntry(
      Long tenantId, Long containerInstanceId, Long itemId) {
    return containerContentRepository
        .findByIdTenantIdAndIdContainerInstanceIdAndIdItemId(tenantId, containerInstanceId, itemId)
        .orElseThrow(() -> new IllegalArgumentException("Container item not found"));
  }

  private InventoryEntry upsertInventoryEntry(Character character, Item item, int quantity) {
    InventoryKey key = inventoryKey(character.getId(), item.getId());
    InventoryEntry entry = inventoryRepository.findById(key).orElse(null);
    if (entry == null) {
      entry = new InventoryEntry();
      entry.setId(key);
      entry.setCharacter(character);
      entry.setItem(item);
      entry.setQuantity(quantity);
    } else {
      entry.setQuantity(entry.getQuantity() + quantity);
    }
    return inventoryRepository.save(entry);
  }

  private ContainerContentEntry upsertContainerContentEntry(
      ContainerInstance containerInstance, Item item, int quantity) {
    ContainerContentKey key =
        containerContentKey(
            containerInstance.getTenantId(), containerInstance.getId(), item.getId());
    ContainerContentEntry entry = containerContentRepository.findById(key).orElse(null);
    if (entry == null) {
      entry = new ContainerContentEntry();
      entry.setId(key);
      entry.setContainerInstance(containerInstance);
      entry.setItem(item);
      entry.setQuantity(quantity);
    } else {
      entry.setQuantity(entry.getQuantity() + quantity);
    }
    return containerContentRepository.save(entry);
  }

  private void adjustInventoryQuantity(InventoryEntry entry, int delta) {
    adjustQuantity(
        () -> entry.getQuantity(),
        quantity -> {
          entry.setQuantity(quantity);
          if (quantity == 0) {
            inventoryRepository.delete(entry);
          } else {
            inventoryRepository.save(entry);
          }
        },
        delta);
  }

  private void adjustContainerContentQuantity(ContainerContentEntry entry, int delta) {
    adjustQuantity(
        () -> entry.getQuantity(),
        quantity -> {
          entry.setQuantity(quantity);
          if (quantity == 0) {
            containerContentRepository.delete(entry);
          } else {
            containerContentRepository.save(entry);
          }
        },
        delta);
  }

  private void adjustQuantity(
      Supplier<Integer> currentQuantity, java.util.function.IntConsumer quantityWriter, int delta) {
    int nextQuantity = currentQuantity.get() + delta;
    if (nextQuantity < 0) {
      throw new IllegalArgumentException("Quantity cannot go negative");
    }
    quantityWriter.accept(nextQuantity);
  }

  private void requirePositiveQuantity(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
  }

  private InventoryKey inventoryKey(Long characterId, Long itemId) {
    InventoryKey key = new InventoryKey();
    key.setCharacterId(characterId);
    key.setItemId(itemId);
    return key;
  }

  private ContainerContentKey containerContentKey(
      Long tenantId, Long containerInstanceId, Long itemId) {
    ContainerContentKey key = new ContainerContentKey();
    key.setTenantId(tenantId);
    key.setContainerInstanceId(containerInstanceId);
    key.setItemId(itemId);
    return key;
  }
}
