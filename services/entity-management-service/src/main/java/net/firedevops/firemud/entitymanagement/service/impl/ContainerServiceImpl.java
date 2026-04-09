package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContainerServiceImpl implements ContainerService {
  private final ContainerInstanceRepository containerInstanceRepository;
  private final ItemInstanceRepository itemInstanceRepository;
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
    return itemInstanceRepository
        .findByTenantIdAndContainerInstance_IdOrderByIdAsc(
            tenantId, containerInstance.getId(), pageable)
        .map(this::toDto);
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
    List<ItemInstance> carried =
        itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                tenantId, characterId, itemId);
    if (carried.size() < quantity) {
      throw new IllegalArgumentException("Not enough quantity to put into container");
    }
    List<ItemInstance> moved = carried.subList(0, quantity);
    for (ItemInstance instance : moved) {
      instance.setCharacter(null);
      instance.setEquipmentSlot(null);
      instance.setGameInstanceId(null);
      instance.setRoomInstanceId(null);
      instance.setContainerInstance(containerInstance);
      itemInstanceRepository.save(instance);
      syncNestedContainerHolder(instance);
    }
    return toMutationDto(moved.get(0), quantity);
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
    List<ItemInstance> contained =
        itemInstanceRepository.findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(
            tenantId, containerInstance.getId(), itemId);
    if (contained.size() < quantity) {
      throw new IllegalArgumentException("Not enough quantity in container");
    }
    List<ItemInstance> moved = contained.subList(0, quantity);
    for (ItemInstance instance : moved) {
      instance.setCharacter(character);
      instance.setEquipmentSlot(null);
      instance.setGameInstanceId(null);
      instance.setRoomInstanceId(null);
      instance.setContainerInstance(null);
      itemInstanceRepository.save(instance);
      syncNestedContainerHolder(instance);
    }
    return toInventoryMutationDto(moved.get(0), quantity);
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
    if (!containerInstance.getItem().isContainer()) {
      throw new IllegalArgumentException("Item is not a container");
    }
    return containerInstance;
  }

  private void syncNestedContainerHolder(ItemInstance itemInstance) {
    if (itemInstance.getItem() == null || !itemInstance.getItem().isContainer()) {
      return;
    }
    ContainerInstance nested =
        containerInstanceRepository
            .findByItemInstance_Id(itemInstance.getId())
            .orElseThrow(() -> new IllegalArgumentException("Container instance not found"));
    nested.setTenantId(itemInstance.getTenantId());
    nested.setCharacter(itemInstance.getCharacter());
    nested.setEquipmentSlot(itemInstance.getEquipmentSlot());
    nested.setGameInstanceId(itemInstance.getGameInstanceId());
    nested.setRoomInstanceId(itemInstance.getRoomInstanceId());
    containerInstanceRepository.save(nested);
  }

  private ContainerContentEntryDto toDto(ItemInstance instance) {
    return new ContainerContentEntryDto(
        instance.getTenantId(),
        resolveCharacterId(instance.getContainerInstance()),
        instance.getContainerInstance().getId(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        1,
        instance.getId(),
        instance.getVisibleRef());
  }

  private ContainerContentEntryDto toMutationDto(ItemInstance instance, int quantity) {
    return new ContainerContentEntryDto(
        instance.getTenantId(),
        resolveCharacterId(instance.getContainerInstance()),
        instance.getContainerInstance().getId(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        quantity,
        quantity == 1 ? instance.getId() : null,
        quantity == 1 ? instance.getVisibleRef() : null);
  }

  private InventoryEntryDto toInventoryMutationDto(ItemInstance instance, int quantity) {
    return new InventoryEntryDto(
        instance.getTenantId(),
        instance.getCharacter().getId(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        quantity,
        quantity == 1 ? instance.getId() : null,
        resolveContainerInstanceId(instance),
        quantity == 1 ? instance.getVisibleRef() : null);
  }

  private Long resolveCharacterId(ContainerInstance containerInstance) {
    return containerInstance.getCharacter() == null
        ? null
        : containerInstance.getCharacter().getId();
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
}
