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
  private final ItemTransferSupport itemTransferSupport;
  private final ContainerHolderSyncSupport containerHolderSyncSupport;

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
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      int quantity) {
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
    List<ItemInstance> moved = selectCarriedInstances(carried, itemInstanceId, quantity);
    for (ItemInstance instance : moved) {
      itemTransferSupport.transfer(
          instance,
          itemTransferSupport.inventory(tenantId, characterId),
          itemTransferSupport.container(containerInstance));
      itemInstanceRepository.save(instance);
      containerHolderSyncSupport.requireExistingAndSync(instance);
    }
    return toMutationDto(moved.get(0), quantity);
  }

  @Override
  @Transactional
  @Timed(value = "container.take")
  public InventoryEntryDto takeItemFromContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      int quantity) {
    requirePositiveQuantity(quantity);
    Character character = requireCharacter(tenantId, characterId);
    ContainerInstance containerInstance =
        requireContainerInstanceAccessibleToCharacter(
            character.getId(), tenantId, containerInstanceId);
    Item item = requireItem(tenantId, itemId);
    List<ItemInstance> contained =
        itemInstanceRepository.findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(
            tenantId, containerInstance.getId(), itemId);
    List<ItemInstance> moved = selectContainedInstances(contained, itemInstanceId, quantity);
    for (ItemInstance instance : moved) {
      itemTransferSupport.transfer(
          instance,
          itemTransferSupport.container(tenantId, containerInstance.getId()),
          itemTransferSupport.inventory(character));
      itemInstanceRepository.save(instance);
      containerHolderSyncSupport.requireExistingAndSync(instance);
    }
    return toInventoryMutationDto(moved.get(0), quantity);
  }

  private List<ItemInstance> selectContainedInstances(
      List<ItemInstance> contained, Long itemInstanceId, int quantity) {
    if (itemInstanceId != null) {
      ItemInstance selected =
          contained.stream()
              .filter(instance -> instance.getId().equals(itemInstanceId))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Container item not found"));
      if (quantity != 1) {
        throw new IllegalArgumentException("Explicit item_instance_id requires quantity 1");
      }
      return List.of(selected);
    }
    if (contained.size() < quantity) {
      throw new IllegalArgumentException("Not enough quantity in container");
    }
    return contained.subList(0, quantity);
  }

  private List<ItemInstance> selectCarriedInstances(
      List<ItemInstance> carried, Long itemInstanceId, int quantity) {
    if (itemInstanceId != null) {
      ItemInstance selected =
          carried.stream()
              .filter(instance -> instance.getId().equals(itemInstanceId))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Inventory item not found"));
      if (quantity != 1) {
        throw new IllegalArgumentException("Explicit item_instance_id requires quantity 1");
      }
      return List.of(selected);
    }
    if (carried.size() < quantity) {
      throw new IllegalArgumentException("Not enough quantity to put into container");
    }
    return carried.subList(0, quantity);
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
