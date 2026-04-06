package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryKey;
import net.firedevops.firemud.entitymanagement.mapper.InventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.mapper.RoomGroundInventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
  private final InventoryEntryRepository inventoryRepository;
  private final InventoryEntryMapper inventoryMapper;
  private final ContainerInstanceRepository containerInstanceRepository;
  private final RoomGroundInventoryRepository roomGroundRepository;
  private final RoomGroundInventoryEntryMapper roomGroundMapper;
  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "inventory.list")
  public Page<InventoryEntryDto> listInventory(Long tenantId, Long characterId, Pageable pageable) {
    requireCharacter(tenantId, characterId);
    return inventoryRepository
        .findByIdCharacterIdAndCharacterTenantId(characterId, tenantId, pageable)
        .map(this::toDto);
  }

  @Override
  @Transactional
  @Timed(value = "inventory.add")
  public InventoryEntryDto addItem(Long tenantId, Long characterId, Long itemId, int quantity) {
    requirePositiveQuantity(quantity);
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireItem(tenantId, itemId);
    InventoryEntry entry = upsertInventoryEntry(character, item, quantity);
    return toDto(entry);
  }

  @Override
  @Transactional
  @Timed(value = "inventory.remove")
  public void removeItem(Long tenantId, Long characterId, Long itemId) {
    Character character = requireCharacter(tenantId, characterId);
    requireItem(tenantId, itemId);
    InventoryKey key = inventoryKey(character.getId(), itemId);
    inventoryRepository.deleteById(key);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "roomGround.list")
  public Page<RoomGroundInventoryEntryDto> listRoomGroundItems(
      Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable) {
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    return roomGroundRepository
        .findByIdTenantIdAndIdGameInstanceIdAndIdRoomInstanceId(
            tenantId, normalizedGameInstanceId, normalizedRoomInstanceId, pageable)
        .map(this::toRoomGroundDto);
  }

  @Override
  @Transactional
  @Timed(value = "roomGround.drop")
  public RoomGroundInventoryEntryDto dropItemToRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      String containerInstanceId,
      int quantity) {
    requirePositiveQuantity(quantity);
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireItem(tenantId, itemId);
    InventoryEntry carried = requireInventoryEntry(character.getId(), itemId);
    if (carried.getQuantity() < quantity) {
      throw new IllegalArgumentException("Not enough quantity to drop");
    }
    adjustInventoryQuantity(carried, -quantity);
    if (item.isContainer()) {
      requireSingleContainerTransfer(quantity);
      moveContainerInstanceToRoom(
          character,
          normalizedGameInstanceId,
          normalizedRoomInstanceId,
          item,
          normalizeOptionalText(containerInstanceId));
    }
    RoomGroundInventoryEntry roomEntry =
        upsertRoomGroundEntry(
            tenantId, normalizedGameInstanceId, normalizedRoomInstanceId, item, quantity);
    return toRoomGroundDto(roomEntry);
  }

  @Override
  @Transactional
  @Timed(value = "roomGround.pickup")
  public InventoryEntryDto pickupItemFromRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      String containerInstanceId,
      int quantity) {
    requirePositiveQuantity(quantity);
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireItem(tenantId, itemId);
    RoomGroundInventoryEntry roomEntry =
        requireRoomGroundEntry(
            tenantId, normalizedGameInstanceId, normalizedRoomInstanceId, itemId);
    if (roomEntry.getQuantity() < quantity) {
      throw new IllegalArgumentException("Not enough quantity on the room ground");
    }
    adjustRoomGroundQuantity(roomEntry, -quantity);
    if (item.isContainer()) {
      requireSingleContainerTransfer(quantity);
      moveContainerInstanceToCarriedFromRoom(
          character,
          normalizedGameInstanceId,
          normalizedRoomInstanceId,
          item,
          normalizeOptionalText(containerInstanceId));
    }
    InventoryEntry carried = upsertInventoryEntry(character, item, quantity);
    return toDto(carried);
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

  private InventoryEntry requireInventoryEntry(Long characterId, Long itemId) {
    return inventoryRepository
        .findById(inventoryKey(characterId, itemId))
        .orElseThrow(() -> new IllegalArgumentException("Inventory item not found"));
  }

  private RoomGroundInventoryEntry requireRoomGroundEntry(
      Long tenantId, String gameInstanceId, String roomInstanceId, Long itemId) {
    return roomGroundRepository
        .findById(roomGroundKey(tenantId, gameInstanceId, roomInstanceId, itemId))
        .orElseThrow(() -> new IllegalArgumentException("Room ground item not found"));
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
    InventoryEntry saved = inventoryRepository.save(entry);
    if (item.isContainer()) {
      saved.setContainerInstanceId(ensureCarriedContainerInstance(character, item).getId());
    }
    return saved;
  }

  private InventoryEntryDto toDto(InventoryEntry entry) {
    if (entry.getItem() != null && entry.getItem().isContainer()) {
      entry.setContainerInstanceId(
          resolveCarriedContainerInstanceId(entry.getCharacter(), entry.getItem()));
    } else {
      entry.setContainerInstanceId(null);
    }
    return inventoryMapper.toDto(entry);
  }

  private RoomGroundInventoryEntryDto toRoomGroundDto(RoomGroundInventoryEntry entry) {
    if (entry.getItem() != null && entry.getItem().isContainer()) {
      entry.setContainerInstanceId(
          resolveRoomContainerInstanceId(
              entry.getId().getTenantId(),
              entry.getId().getGameInstanceId(),
              entry.getId().getRoomInstanceId(),
              entry.getItem()));
    } else {
      entry.setContainerInstanceId(null);
    }
    return roomGroundMapper.toDto(entry);
  }

  private Long resolveCarriedContainerInstanceId(Character character, Item item) {
    return containerInstanceRepository
        .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
            character.getTenantId(), character.getId(), item.getId())
        .map(ContainerInstance::getId)
        .orElse(null);
  }

  private Long resolveRoomContainerInstanceId(
      Long tenantId, String gameInstanceId, String roomInstanceId, Item item) {
    return containerInstanceRepository
        .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNull(
            tenantId, gameInstanceId, roomInstanceId, item.getId())
        .map(ContainerInstance::getId)
        .orElse(null);
  }

  private ContainerInstance ensureCarriedContainerInstance(Character character, Item item) {
    return containerInstanceRepository
        .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
            character.getTenantId(), character.getId(), item.getId())
        .orElseGet(
            () -> {
              ContainerInstance instance = new ContainerInstance();
              instance.setTenantId(character.getTenantId());
              instance.setCharacter(character);
              instance.setEquipmentSlot(null);
              instance.setGameInstanceId(null);
              instance.setRoomInstanceId(null);
              instance.setItem(item);
              return containerInstanceRepository.save(instance);
            });
  }

  private void moveContainerInstanceToRoom(
      Character character,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      String containerInstanceId) {
    ContainerInstance instance =
        requireCarriedContainerInstance(character, item, containerInstanceId);
    instance.setCharacter(null);
    instance.setEquipmentSlot(null);
    instance.setGameInstanceId(gameInstanceId);
    instance.setRoomInstanceId(roomInstanceId);
    containerInstanceRepository.save(instance);
  }

  private void moveContainerInstanceToCarriedFromRoom(
      Character character,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      String containerInstanceId) {
    ContainerInstance instance =
        requireRoomContainerInstance(
            character.getTenantId(), gameInstanceId, roomInstanceId, item, containerInstanceId);
    instance.setCharacter(character);
    instance.setEquipmentSlot(null);
    instance.setGameInstanceId(null);
    instance.setRoomInstanceId(null);
    containerInstanceRepository.save(instance);
  }

  private RoomGroundInventoryEntry upsertRoomGroundEntry(
      Long tenantId, String gameInstanceId, String roomInstanceId, Item item, int quantity) {
    RoomGroundInventoryKey key =
        roomGroundKey(tenantId, gameInstanceId, roomInstanceId, item.getId());
    RoomGroundInventoryEntry entry = roomGroundRepository.findById(key).orElse(null);
    if (entry == null) {
      entry = new RoomGroundInventoryEntry();
      entry.setId(key);
      entry.setItem(item);
      entry.setQuantity(quantity);
    } else {
      entry.setQuantity(entry.getQuantity() + quantity);
    }
    return roomGroundRepository.save(entry);
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

  private void adjustRoomGroundQuantity(RoomGroundInventoryEntry entry, int delta) {
    adjustQuantity(
        () -> entry.getQuantity(),
        quantity -> {
          entry.setQuantity(quantity);
          if (quantity == 0) {
            roomGroundRepository.delete(entry);
          } else {
            roomGroundRepository.save(entry);
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

  private void requireSingleContainerTransfer(int quantity) {
    if (quantity != 1) {
      throw new IllegalArgumentException("Container transfers must move exactly one item");
    }
  }

  private ContainerInstance requireCarriedContainerInstance(
      Character character, Item item, String containerInstanceId) {
    if (containerInstanceId != null) {
      return containerInstanceRepository
          .findAccessibleByIdAndTenantIdAndCharacterId(
              Long.parseLong(containerInstanceId), character.getTenantId(), character.getId())
          .filter(instance -> instance.getItem().getId().equals(item.getId()))
          .orElseThrow(() -> new IllegalArgumentException("Container instance not found"));
    }
    return ensureCarriedContainerInstance(character, item);
  }

  private ContainerInstance requireRoomContainerInstance(
      Long tenantId,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      String containerInstanceId) {
    if (containerInstanceId != null) {
      return containerInstanceRepository
          .findById(Long.parseLong(containerInstanceId))
          .filter(instance -> instance.getTenantId().equals(tenantId))
          .filter(instance -> instance.getCharacter() == null)
          .filter(instance -> instance.getItem().getId().equals(item.getId()))
          .filter(instance -> gameInstanceId.equals(instance.getGameInstanceId()))
          .filter(instance -> roomInstanceId.equals(instance.getRoomInstanceId()))
          .orElseThrow(() -> new IllegalArgumentException("Container instance not found"));
    }
    return containerInstanceRepository
        .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNull(
            tenantId, gameInstanceId, roomInstanceId, item.getId())
        .orElseGet(
            () -> {
              ContainerInstance created = new ContainerInstance();
              created.setTenantId(tenantId);
              created.setItem(item);
              return created;
            });
  }

  private String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be provided");
    }
    return value.trim();
  }

  private String normalizeOptionalText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private InventoryKey inventoryKey(Long characterId, Long itemId) {
    InventoryKey key = new InventoryKey();
    key.setCharacterId(characterId);
    key.setItemId(itemId);
    return key;
  }

  private RoomGroundInventoryKey roomGroundKey(
      Long tenantId, String gameInstanceId, String roomInstanceId, Long itemId) {
    RoomGroundInventoryKey key = new RoomGroundInventoryKey();
    key.setTenantId(tenantId);
    key.setGameInstanceId(gameInstanceId);
    key.setRoomInstanceId(roomInstanceId);
    key.setItemId(itemId);
    return key;
  }
}
