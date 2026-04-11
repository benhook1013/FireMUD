package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemStack;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContainerServiceImpl implements ContainerService {
  private final ContainerInstanceRepository containerInstanceRepository;
  private final ItemInstanceRepository itemInstanceRepository;
  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;
  private final ItemStackRepository itemStackRepository;
  private final ItemTransferSupport itemTransferSupport;
  private final ContainerHolderSyncSupport containerHolderSyncSupport;
  private final ContainerHolderPolicySupport containerHolderPolicySupport;
  private final StackableItemSupport stackableItemSupport;

  @Autowired
  public ContainerServiceImpl(
      ContainerInstanceRepository containerInstanceRepository,
      ItemInstanceRepository itemInstanceRepository,
      CharacterRepository characterRepository,
      ItemRepository itemRepository,
      ItemStackRepository itemStackRepository,
      ItemTransferSupport itemTransferSupport,
      ContainerHolderSyncSupport containerHolderSyncSupport,
      ContainerHolderPolicySupport containerHolderPolicySupport,
      StackableItemSupport stackableItemSupport) {
    this.containerInstanceRepository = containerInstanceRepository;
    this.itemInstanceRepository = itemInstanceRepository;
    this.characterRepository = characterRepository;
    this.itemRepository = itemRepository;
    this.itemStackRepository = itemStackRepository;
    this.itemTransferSupport = itemTransferSupport;
    this.containerHolderSyncSupport = containerHolderSyncSupport;
    this.containerHolderPolicySupport = containerHolderPolicySupport;
    this.stackableItemSupport = stackableItemSupport;
  }

  ContainerServiceImpl(
      ContainerInstanceRepository containerInstanceRepository,
      ItemInstanceRepository itemInstanceRepository,
      CharacterRepository characterRepository,
      ItemRepository itemRepository,
      ItemStackRepository itemStackRepository,
      ItemTransferSupport itemTransferSupport,
      ContainerHolderSyncSupport containerHolderSyncSupport) {
    this(
        containerInstanceRepository,
        itemInstanceRepository,
        characterRepository,
        itemRepository,
        itemStackRepository,
        itemTransferSupport,
        containerHolderSyncSupport,
        new ContainerHolderPolicySupport(containerInstanceRepository),
        new StackableItemSupport());
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "container.list")
  public Page<ContainerContentEntryDto> listContainerContents(
      Long tenantId, Long characterId, Long containerInstanceId, Pageable pageable) {
    Character character = requireCharacter(tenantId, characterId);
    ContainerInstance containerInstance =
        containerHolderPolicySupport.requireAccessibleContainer(
            tenantId, character.getId(), containerInstanceId);
    List<ContainerContentEntryDto> entries = new ArrayList<>();
    entries.addAll(
        itemInstanceRepository
            .findByTenantIdAndContainerInstance_IdOrderByIdAsc(
                tenantId, containerInstance.getId(), Pageable.unpaged())
            .map(this::toDto)
            .getContent());
    entries.addAll(
        itemStackRepository
            .findByTenantIdAndContainerInstance_IdOrderByIdAsc(
                tenantId, containerInstance.getId(), Pageable.unpaged())
            .map(this::toDto)
            .getContent());
    return page(entries, pageable);
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
        containerHolderPolicySupport.requireAccessibleContainer(
            tenantId, character.getId(), containerInstanceId);
    Item item = requireItem(tenantId, itemId);
    containerHolderPolicySupport.requireCanContainItem(containerInstance, item);
    if (stackableItemSupport.usesStackStorage(item)) {
      moveInventoryStackToContainer(tenantId, characterId, containerInstance, item, quantity);
      return toStackMutationDto(containerInstance, item, quantity);
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
          itemTransferSupport.container(containerInstance),
          itemTransferSupport.audit("PUT", characterId));
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
        containerHolderPolicySupport.requireAccessibleContainer(
            tenantId, character.getId(), containerInstanceId);
    Item item = requireItem(tenantId, itemId);
    if (stackableItemSupport.usesStackStorage(item)) {
      moveContainerStackToInventory(tenantId, character, containerInstance, item, quantity);
      return toInventoryStackMutationDto(character, item, quantity);
    }
    List<ItemInstance> contained =
        itemInstanceRepository.findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(
            tenantId, containerInstance.getId(), itemId);
    List<ItemInstance> moved = selectContainedInstances(contained, itemInstanceId, quantity);
    for (ItemInstance instance : moved) {
      itemTransferSupport.transfer(
          instance,
          itemTransferSupport.container(tenantId, containerInstance.getId()),
          itemTransferSupport.inventory(character),
          itemTransferSupport.audit("TAKE", characterId));
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

  private ContainerContentEntryDto toDto(ItemStack stack) {
    return new ContainerContentEntryDto(
        stack.getTenantId(),
        resolveCharacterId(stack.getContainerInstance()),
        stack.getContainerInstance().getId(),
        stack.getItem().getId(),
        stack.getItem().getName(),
        stack.getItem().getDescription(),
        stack.getQuantity(),
        null,
        null);
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

  private ContainerContentEntryDto toStackMutationDto(
      ContainerInstance containerInstance, Item item, int quantity) {
    return new ContainerContentEntryDto(
        containerInstance.getTenantId(),
        resolveCharacterId(containerInstance),
        containerInstance.getId(),
        item.getId(),
        item.getName(),
        item.getDescription(),
        quantity,
        null,
        null);
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

  private InventoryEntryDto toInventoryStackMutationDto(
      Character character, Item item, int quantity) {
    return new InventoryEntryDto(
        character.getTenantId(),
        character.getId(),
        item.getId(),
        item.getName(),
        item.getDescription(),
        quantity,
        null,
        null,
        null);
  }

  private void moveInventoryStackToContainer(
      Long tenantId,
      Long characterId,
      ContainerInstance containerInstance,
      Item item,
      int quantity) {
    ItemStack source =
        requireSingleInventoryStackSource(
            tenantId, characterId, item, "Not enough quantity to put into container");
    requireStackQuantity(source, quantity, "Not enough quantity to put into container");
    decrementOrDelete(source, quantity);
    String compatibilityFingerprint = source.getCompatibilityFingerprint();
    ItemStack destination =
        itemStackRepository
            .findByTenantIdAndContainerInstance_IdAndItem_IdAndCompatibilityFingerprint(
                tenantId, containerInstance.getId(), item.getId(), compatibilityFingerprint)
            .orElseGet(
                () -> {
                  ItemStack created = new ItemStack();
                  created.setTenantId(tenantId);
                  created.setContainerInstance(containerInstance);
                  created.setItem(item);
                  created.setStackFamilyKey(source.getStackFamilyKey());
                  created.setCompatibilityFingerprint(compatibilityFingerprint);
                  created.setQuantity(0);
                  return created;
                });
    destination.setQuantity(destination.getQuantity() + quantity);
    itemStackRepository.save(destination);
  }

  private void moveContainerStackToInventory(
      Long tenantId,
      Character character,
      ContainerInstance containerInstance,
      Item item,
      int quantity) {
    ItemStack source =
        requireSingleContainerStackSource(
            tenantId, containerInstance.getId(), item, "Not enough quantity in container");
    requireStackQuantity(source, quantity, "Not enough quantity in container");
    decrementOrDelete(source, quantity);
    String compatibilityFingerprint = source.getCompatibilityFingerprint();
    ItemStack destination =
        itemStackRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
                tenantId, character.getId(), item.getId(), compatibilityFingerprint)
            .orElseGet(
                () -> {
                  ItemStack created = new ItemStack();
                  created.setTenantId(tenantId);
                  created.setCharacter(character);
                  created.setItem(item);
                  created.setStackFamilyKey(source.getStackFamilyKey());
                  created.setCompatibilityFingerprint(compatibilityFingerprint);
                  created.setQuantity(0);
                  return created;
                });
    destination.setQuantity(destination.getQuantity() + quantity);
    itemStackRepository.save(destination);
  }

  private ItemStack requireSingleInventoryStackSource(
      Long tenantId, Long characterId, Item item, String notFoundMessage) {
    List<ItemStack> stacks =
        itemStackRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                tenantId, characterId, item.getId());
    return requireSingleStackSource(stacks, item, notFoundMessage);
  }

  private ItemStack requireSingleContainerStackSource(
      Long tenantId, Long containerInstanceId, Item item, String notFoundMessage) {
    List<ItemStack> stacks =
        itemStackRepository.findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(
            tenantId, containerInstanceId, item.getId());
    return requireSingleStackSource(stacks, item, notFoundMessage);
  }

  private ItemStack requireSingleStackSource(
      List<ItemStack> stacks, Item item, String notFoundMessage) {
    if (stacks.isEmpty()) {
      throw new IllegalArgumentException(notFoundMessage);
    }
    if (stacks.size() > 1) {
      throw new IllegalArgumentException(
          "Multiple stack families exist for item "
              + item.getId()
              + "; explicit stack selection required");
    }
    return stacks.get(0);
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

  private void requireStackQuantity(ItemStack stack, int quantity, String message) {
    if (stack.getQuantity() < quantity) {
      throw new IllegalArgumentException(message);
    }
  }

  private void decrementOrDelete(ItemStack stack, int quantity) {
    int remaining = stack.getQuantity() - quantity;
    if (remaining <= 0) {
      itemStackRepository.delete(stack);
      return;
    }
    stack.setQuantity(remaining);
    itemStackRepository.save(stack);
  }

  private <T> Page<T> page(List<T> entries, Pageable pageable) {
    if (pageable.isUnpaged()) {
      return new PageImpl<>(entries);
    }
    int start = Math.toIntExact(pageable.getOffset());
    if (start >= entries.size()) {
      return new PageImpl<>(List.of(), pageable, entries.size());
    }
    int end = Math.min(start + pageable.getPageSize(), entries.size());
    return new PageImpl<>(entries.subList(start, end), pageable, entries.size());
  }
}
