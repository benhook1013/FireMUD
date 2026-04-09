package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
  private final ItemInstanceRepository itemInstanceRepository;
  private final ContainerInstanceRepository containerInstanceRepository;
  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;
  private final ItemVisibleRefAllocator itemVisibleRefAllocator;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "inventory.list")
  public Page<InventoryEntryDto> listInventory(Long tenantId, Long characterId, Pageable pageable) {
    requireCharacter(tenantId, characterId);
    return itemInstanceRepository
        .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
            tenantId, characterId, pageable)
        .map(this::toInventoryDto);
  }

  @Override
  @Transactional
  @Timed(value = "inventory.add")
  public InventoryEntryDto addItem(Long tenantId, Long characterId, Long itemId, int quantity) {
    requirePositiveQuantity(quantity);
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireItem(tenantId, itemId);
    List<ItemInstance> created = createCarriedItemInstances(character, item, quantity);
    return inventoryDtoForMutation(created.get(0), quantity);
  }

  @Override
  @Transactional
  @Timed(value = "inventory.remove")
  public void removeItem(Long tenantId, Long characterId, Long itemId) {
    Character character = requireCharacter(tenantId, characterId);
    requireItem(tenantId, itemId);
    List<ItemInstance> carried =
        itemInstanceRepository.findByTenantIdAndCharacter_IdAndItem_IdOrderByIdAsc(
            tenantId, character.getId(), itemId);
    if (carried.isEmpty()) {
      throw new IllegalArgumentException("Inventory item not found");
    }
    carried.forEach(this::deleteItemInstance);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "roomGround.list")
  public Page<RoomGroundInventoryEntryDto> listRoomGroundItems(
      Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable) {
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    return itemInstanceRepository
        .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
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
      Long itemInstanceId,
      String containerInstanceId,
      int quantity) {
    requirePositiveQuantity(quantity);
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireItem(tenantId, itemId);
    List<ItemInstance> selected =
        requireCarriedItemInstances(
            character, item, itemInstanceId, normalizeOptionalText(containerInstanceId), quantity);
    moveItemInstancesToRoom(selected, normalizedGameInstanceId, normalizedRoomInstanceId);
    return roomGroundDtoForMutation(selected.get(0), quantity);
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
      Long itemInstanceId,
      String containerInstanceId,
      int quantity) {
    requirePositiveQuantity(quantity);
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireItem(tenantId, itemId);
    List<ItemInstance> selected =
        requireRoomItemInstances(
            tenantId,
            normalizedGameInstanceId,
            normalizedRoomInstanceId,
            item,
            itemInstanceId,
            normalizeOptionalText(containerInstanceId),
            quantity);
    moveItemInstancesToInventory(character, selected);
    return inventoryDtoForMutation(selected.get(0), quantity);
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

  private List<ItemInstance> createCarriedItemInstances(
      Character character, Item item, int quantity) {
    List<ItemInstance> created = new ArrayList<>();
    for (int i = 0; i < quantity; i++) {
      ItemVisibleRefAllocator.VisibleRef visibleRef =
          itemVisibleRefAllocator.allocate(character.getTenantId(), item);
      ItemInstance instance = new ItemInstance();
      instance.setTenantId(character.getTenantId());
      instance.setCharacter(character);
      instance.setEquipmentSlot(null);
      instance.setGameInstanceId(null);
      instance.setRoomInstanceId(null);
      instance.setItem(item);
      instance.setVisibleRefToken(visibleRef.token());
      instance.setVisibleRefSequence(visibleRef.sequence());
      instance.setVisibleRef(visibleRef.value());
      ItemInstance saved = itemInstanceRepository.save(instance);
      if (item.isContainer()) {
        createContainerInstance(saved);
      }
      created.add(saved);
    }
    return created;
  }

  private List<ItemInstance> requireCarriedItemInstances(
      Character character,
      Item item,
      Long itemInstanceId,
      String containerInstanceId,
      int quantity) {
    List<ItemInstance> matches =
        itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                character.getTenantId(), character.getId(), item.getId());
    return selectMatchingInstances(
        matches, item, itemInstanceId, containerInstanceId, quantity, "Inventory item not found");
  }

  private List<ItemInstance> requireRoomItemInstances(
      Long tenantId,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      Long itemInstanceId,
      String containerInstanceId,
      int quantity) {
    List<ItemInstance> matches =
        itemInstanceRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                tenantId, gameInstanceId, roomInstanceId, item.getId());
    return selectMatchingInstances(
        matches, item, itemInstanceId, containerInstanceId, quantity, "Room ground item not found");
  }

  private List<ItemInstance> selectMatchingInstances(
      List<ItemInstance> candidates,
      Item item,
      Long itemInstanceId,
      String containerInstanceId,
      int quantity,
      String notFoundMessage) {
    if (itemInstanceId != null) {
      return candidates.stream()
          .filter(instance -> instance.getId().equals(itemInstanceId))
          .findFirst()
          .map(List::of)
          .orElseThrow(() -> new IllegalArgumentException(notFoundMessage));
    }
    if (item.isContainer()) {
      requireSingleContainerTransfer(quantity);
      if (containerInstanceId != null) {
        long requestedContainerId = Long.parseLong(containerInstanceId);
        return candidates.stream()
            .filter(instance -> hasContainerInstanceId(instance, requestedContainerId))
            .findFirst()
            .map(List::of)
            .orElseThrow(() -> new IllegalArgumentException("Container instance not found"));
      }
    }
    if (candidates.size() < quantity) {
      throw new IllegalArgumentException(notFoundMessage);
    }
    return new ArrayList<>(candidates.subList(0, quantity));
  }

  private void moveItemInstancesToRoom(
      List<ItemInstance> instances, String gameInstanceId, String roomInstanceId) {
    for (ItemInstance instance : instances) {
      instance.setCharacter(null);
      instance.setEquipmentSlot(null);
      instance.setGameInstanceId(gameInstanceId);
      instance.setRoomInstanceId(roomInstanceId);
      itemInstanceRepository.save(instance);
      syncContainerHolder(instance);
    }
  }

  private void moveItemInstancesToInventory(Character character, List<ItemInstance> instances) {
    for (ItemInstance instance : instances) {
      instance.setCharacter(character);
      instance.setEquipmentSlot(null);
      instance.setGameInstanceId(null);
      instance.setRoomInstanceId(null);
      itemInstanceRepository.save(instance);
      syncContainerHolder(instance);
    }
  }

  private void deleteItemInstance(ItemInstance instance) {
    if (instance.getItem() != null && instance.getItem().isContainer()) {
      containerInstanceRepository
          .findByItemInstance_Id(instance.getId())
          .ifPresent(containerInstanceRepository::delete);
    }
    itemInstanceRepository.delete(instance);
  }

  private ContainerInstance createContainerInstance(ItemInstance itemInstance) {
    ContainerInstance containerInstance = new ContainerInstance();
    containerInstance.setTenantId(itemInstance.getTenantId());
    containerInstance.setCharacter(itemInstance.getCharacter());
    containerInstance.setEquipmentSlot(itemInstance.getEquipmentSlot());
    containerInstance.setGameInstanceId(itemInstance.getGameInstanceId());
    containerInstance.setRoomInstanceId(itemInstance.getRoomInstanceId());
    containerInstance.setItem(itemInstance.getItem());
    containerInstance.setItemInstance(itemInstance);
    return containerInstanceRepository.save(containerInstance);
  }

  private void syncContainerHolder(ItemInstance itemInstance) {
    if (itemInstance.getItem() == null || !itemInstance.getItem().isContainer()) {
      return;
    }
    ContainerInstance containerInstance =
        containerInstanceRepository
            .findByItemInstance_Id(itemInstance.getId())
            .orElseGet(() -> createContainerInstance(itemInstance));
    containerInstance.setTenantId(itemInstance.getTenantId());
    containerInstance.setCharacter(itemInstance.getCharacter());
    containerInstance.setEquipmentSlot(itemInstance.getEquipmentSlot());
    containerInstance.setGameInstanceId(itemInstance.getGameInstanceId());
    containerInstance.setRoomInstanceId(itemInstance.getRoomInstanceId());
    containerInstance.setItem(itemInstance.getItem());
    containerInstance.setItemInstance(itemInstance);
    containerInstanceRepository.save(containerInstance);
  }

  private boolean hasContainerInstanceId(ItemInstance itemInstance, long containerInstanceId) {
    return containerInstanceRepository
        .findByItemInstance_Id(itemInstance.getId())
        .map(ContainerInstance::getId)
        .filter(id -> id == containerInstanceId)
        .isPresent();
  }

  private InventoryEntryDto toInventoryDto(ItemInstance instance) {
    return new InventoryEntryDto(
        instance.getTenantId(),
        instance.getCharacter().getId(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        1,
        instance.getId(),
        resolveContainerInstanceId(instance),
        instance.getVisibleRef());
  }

  private InventoryEntryDto inventoryDtoForMutation(ItemInstance instance, int quantity) {
    return new InventoryEntryDto(
        instance.getTenantId(),
        instance.getCharacter().getId(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        quantity,
        quantity == 1 ? instance.getId() : null,
        quantity == 1 ? resolveContainerInstanceId(instance) : null,
        quantity == 1 ? instance.getVisibleRef() : null);
  }

  private RoomGroundInventoryEntryDto toRoomGroundDto(ItemInstance instance) {
    return new RoomGroundInventoryEntryDto(
        instance.getTenantId(),
        instance.getGameInstanceId(),
        instance.getRoomInstanceId(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        1,
        instance.getId(),
        resolveContainerInstanceId(instance),
        instance.getVisibleRef());
  }

  private RoomGroundInventoryEntryDto roomGroundDtoForMutation(
      ItemInstance instance, int quantity) {
    return new RoomGroundInventoryEntryDto(
        instance.getTenantId(),
        instance.getGameInstanceId(),
        instance.getRoomInstanceId(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        quantity,
        quantity == 1 ? instance.getId() : null,
        quantity == 1 ? resolveContainerInstanceId(instance) : null,
        quantity == 1 ? instance.getVisibleRef() : null);
  }

  private Long resolveContainerInstanceId(ItemInstance itemInstance) {
    if (itemInstance.getItem() == null || !itemInstance.getItem().isContainer()) {
      return null;
    }
    return containerInstanceRepository
        .findByItemInstance_Id(itemInstance.getId())
        .map(ContainerInstance::getId)
        .orElse(null);
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

  private String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be provided");
    }
    return value.trim();
  }

  private String normalizeOptionalText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
