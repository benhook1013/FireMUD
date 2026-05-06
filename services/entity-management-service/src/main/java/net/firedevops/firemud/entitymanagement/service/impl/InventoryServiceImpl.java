package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto;
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
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories and helpers are managed dependencies kept internal.")
public class InventoryServiceImpl implements InventoryService {
  private final ItemInstanceRepository itemInstanceRepository;
  private final ContainerInstanceRepository containerInstanceRepository;
  private final ScopedCharacterResolver scopedCharacterResolver;
  private final ItemRepository itemRepository;
  private final ItemStackRepository itemStackRepository;
  private final ItemVisibleRefAllocator itemVisibleRefAllocator;
  private final ItemTransferSupport itemTransferSupport;
  private final ItemTransferAuditWriter itemTransferAuditWriter;
  private final ContainerHolderSyncSupport containerHolderSyncSupport;
  private final StackableItemSupport stackableItemSupport;

  @Autowired
  public InventoryServiceImpl(
      ItemInstanceRepository itemInstanceRepository,
      ContainerInstanceRepository containerInstanceRepository,
      ScopedCharacterResolver scopedCharacterResolver,
      ItemRepository itemRepository,
      ItemStackRepository itemStackRepository,
      ItemVisibleRefAllocator itemVisibleRefAllocator,
      ItemTransferSupport itemTransferSupport,
      ItemTransferAuditWriter itemTransferAuditWriter,
      ContainerHolderSyncSupport containerHolderSyncSupport,
      StackableItemSupport stackableItemSupport) {
    this.itemInstanceRepository = itemInstanceRepository;
    this.containerInstanceRepository = containerInstanceRepository;
    this.scopedCharacterResolver = scopedCharacterResolver;
    this.itemRepository = itemRepository;
    this.itemStackRepository = itemStackRepository;
    this.itemVisibleRefAllocator = itemVisibleRefAllocator;
    this.itemTransferSupport = itemTransferSupport;
    this.itemTransferAuditWriter = itemTransferAuditWriter;
    this.containerHolderSyncSupport = containerHolderSyncSupport;
    this.stackableItemSupport = stackableItemSupport;
  }

  InventoryServiceImpl(
      ItemInstanceRepository itemInstanceRepository,
      ContainerInstanceRepository containerInstanceRepository,
      CharacterRepository characterRepository,
      ItemRepository itemRepository,
      ItemStackRepository itemStackRepository,
      ItemVisibleRefAllocator itemVisibleRefAllocator,
      ItemTransferSupport itemTransferSupport,
      ItemTransferAuditWriter itemTransferAuditWriter,
      ContainerHolderSyncSupport containerHolderSyncSupport,
      StackableItemSupport stackableItemSupport) {
    this(
        itemInstanceRepository,
        containerInstanceRepository,
        new ScopedCharacterResolver(characterRepository, new PlayableStateKeyResolver()),
        itemRepository,
        itemStackRepository,
        itemVisibleRefAllocator,
        itemTransferSupport,
        itemTransferAuditWriter,
        containerHolderSyncSupport,
        stackableItemSupport);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "inventory.list")
  public Page<InventoryEntryDto> listInventory(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Pageable pageable) {
    requireCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
    List<InventoryEntryDto> entries = new ArrayList<>();
    entries.addAll(
        itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
                tenantId, characterId, Pageable.unpaged())
            .map(this::toInventoryDto)
            .getContent());
    entries.addAll(
        itemStackRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullOrderByIdAsc(
                tenantId, characterId, Pageable.unpaged())
            .map(this::toInventoryDto)
            .getContent());
    entries.sort(inventoryOrdering());
    return page(entries, pageable);
  }

  @Override
  @Transactional
  @Timed(value = "inventory.add")
  public InventoryEntryDto addItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long itemId,
      int quantity) {
    requirePositiveQuantity(quantity);
    Character character =
        requireCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
    Item item = requireItem(tenantId, itemId);
    if (stackableItemSupport.usesStackStorage(item)) {
      incrementInventoryStack(character, item, quantity);
      return inventoryDtoForStackMutation(
          character, item, stackableItemSupport.authoredStackFamilyKey(item), quantity);
    }
    List<ItemInstance> created = createCarriedItemInstances(character, item, quantity);
    return inventoryDtoForMutation(created.get(0), quantity);
  }

  @Override
  @Transactional
  @Timed(value = "inventory.remove")
  public void removeItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long itemId) {
    Character character =
        requireCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
    Item item = requireItem(tenantId, itemId);
    if (stackableItemSupport.usesStackStorage(item)) {
      List<ItemStack> stacks =
          itemStackRepository
              .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                  tenantId, characterId, itemId);
      if (stacks.isEmpty()) {
        throw new IllegalArgumentException("Inventory item not found");
      }
      itemStackRepository.deleteAll(stacks);
    }
    List<ItemInstance> carried =
        itemInstanceRepository.findByTenantIdAndCharacter_IdAndItem_IdOrderByIdAsc(
            tenantId, character.getId(), itemId);
    if (carried.isEmpty() && !stackableItemSupport.usesStackStorage(item)) {
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
    List<RoomGroundInventoryEntryDto> entries = new ArrayList<>();
    entries.addAll(
        itemInstanceRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                tenantId, normalizedGameInstanceId, normalizedRoomInstanceId, Pageable.unpaged())
            .map(this::toRoomGroundDto)
            .getContent());
    entries.addAll(
        itemStackRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
                tenantId, normalizedGameInstanceId, normalizedRoomInstanceId, Pageable.unpaged())
            .map(this::toRoomGroundDto)
            .getContent());
    entries.sort(roomGroundOrdering());
    return page(entries, pageable);
  }

  @Override
  @Transactional
  @Timed(value = "roomGround.drop")
  public RoomGroundInventoryEntryDto dropItemToRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId) {
    requirePositiveQuantity(quantity);
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    Character character =
        requireCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
    Item item = requireItem(tenantId, itemId);
    if (stackableItemSupport.usesStackStorage(item)) {
      String selectedStackFamilyKey =
          moveInventoryStackToRoom(
              tenantId,
              character,
              normalizedGameInstanceId,
              normalizedRoomInstanceId,
              item,
              normalizeOptionalText(stackFamilyKey),
              quantity,
              effectId,
              sessionId);
      return roomGroundDtoForStackMutation(
          tenantId,
          normalizedGameInstanceId,
          normalizedRoomInstanceId,
          item,
          selectedStackFamilyKey,
          quantity);
    }
    List<ItemInstance> selected =
        requireCarriedItemInstances(
            character, item, itemInstanceId, normalizeOptionalText(containerInstanceId), quantity);
    moveItemInstancesToRoom(
        character,
        selected,
        normalizedGameInstanceId,
        normalizedRoomInstanceId,
        effectId,
        sessionId);
    return roomGroundDtoForMutation(selected.get(0), quantity);
  }

  @Override
  @Transactional
  @Timed(value = "roomGround.pickup")
  public InventoryEntryDto pickupItemFromRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId) {
    requirePositiveQuantity(quantity);
    String normalizedGameInstanceId = requireText(gameInstanceId, "gameInstanceId");
    String normalizedRoomInstanceId = requireText(roomInstanceId, "roomInstanceId");
    Character character =
        requireCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
    Item item = requireItem(tenantId, itemId);
    if (stackableItemSupport.usesStackStorage(item)) {
      String selectedStackFamilyKey =
          moveRoomStackToInventory(
              tenantId,
              character,
              normalizedGameInstanceId,
              normalizedRoomInstanceId,
              item,
              normalizeOptionalText(stackFamilyKey),
              quantity,
              effectId,
              sessionId);
      return inventoryDtoForStackMutation(character, item, selectedStackFamilyKey, quantity);
    }
    List<ItemInstance> selected =
        requireRoomItemInstances(
            tenantId,
            normalizedGameInstanceId,
            normalizedRoomInstanceId,
            item,
            itemInstanceId,
            normalizeOptionalText(containerInstanceId),
            quantity);
    moveItemInstancesToInventory(character, selected, effectId, sessionId);
    return inventoryDtoForMutation(selected.get(0), quantity);
  }

  private Character requireCharacter(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    return scopedCharacterResolver.requireScopedCharacter(
        tenantId, characterId, gameInstanceId, playableStateScope);
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
        containerHolderSyncSupport.ensureSynced(saved);
      }
      created.add(saved);
    }
    return created;
  }

  private void incrementInventoryStack(Character character, Item item, int quantity) {
    incrementInventoryStack(
        character, item, quantity, stackableItemSupport.authoredStackFamilyKey(item));
  }

  private void incrementInventoryStack(
      Character character, Item item, int quantity, String stackFamilyKey) {
    String compatibilityFingerprint =
        stackableItemSupport.compatibilityFingerprint(item, stackFamilyKey);
    ItemStack stack =
        itemStackRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
                character.getTenantId(), character.getId(), item.getId(), compatibilityFingerprint)
            .orElseGet(
                () -> {
                  ItemStack created = new ItemStack();
                  created.setTenantId(character.getTenantId());
                  created.setCharacter(character);
                  created.setItem(item);
                  created.setStackFamilyKey(
                      stackableItemSupport.normalizeStackFamilyKey(stackFamilyKey));
                  created.setCompatibilityFingerprint(compatibilityFingerprint);
                  created.setQuantity(0);
                  return created;
                });
    stack.setQuantity(stack.getQuantity() + quantity);
    itemStackRepository.save(stack);
  }

  private String moveInventoryStackToRoom(
      Long tenantId,
      Character character,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId) {
    ItemStack source =
        requireSingleInventoryStackSource(
            tenantId, character.getId(), item, stackFamilyKey, "Inventory item not found");
    requireStackQuantity(source, quantity, "Inventory item not found");
    decrementOrDelete(source, quantity);
    String compatibilityFingerprint = source.getCompatibilityFingerprint();
    ItemStack destination =
        itemStackRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
                tenantId, gameInstanceId, roomInstanceId, item.getId(), compatibilityFingerprint)
            .orElseGet(
                () -> {
                  ItemStack created = new ItemStack();
                  created.setTenantId(tenantId);
                  created.setGameInstanceId(gameInstanceId);
                  created.setRoomInstanceId(roomInstanceId);
                  created.setItem(item);
                  created.setStackFamilyKey(source.getStackFamilyKey());
                  created.setCompatibilityFingerprint(compatibilityFingerprint);
                  created.setQuantity(0);
                  return created;
                });
    destination.setQuantity(destination.getQuantity() + quantity);
    itemStackRepository.save(destination);
    itemTransferAuditWriter.recordStackTransfer(
        tenantId,
        item,
        quantity,
        source.getStackFamilyKey(),
        itemTransferSupport.inventoryHolder(tenantId, character.getId()),
        itemTransferSupport.roomHolder(tenantId, gameInstanceId, roomInstanceId),
        itemTransferSupport.audit("DROP", character.getId(), sessionId, effectId));
    return source.getStackFamilyKey();
  }

  private String moveRoomStackToInventory(
      Long tenantId,
      Character character,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId) {
    ItemStack source =
        requireSingleRoomStackSource(
            tenantId,
            gameInstanceId,
            roomInstanceId,
            item,
            stackFamilyKey,
            "Room ground item not found");
    requireStackQuantity(source, quantity, "Room ground item not found");
    decrementOrDelete(source, quantity);
    incrementInventoryStack(character, item, quantity, source.getStackFamilyKey());
    itemTransferAuditWriter.recordStackTransfer(
        tenantId,
        item,
        quantity,
        source.getStackFamilyKey(),
        itemTransferSupport.roomHolder(tenantId, gameInstanceId, roomInstanceId),
        itemTransferSupport.inventoryHolder(tenantId, character.getId()),
        itemTransferSupport.audit("GET", character.getId(), sessionId, effectId));
    return source.getStackFamilyKey();
  }

  private ItemStack requireSingleInventoryStackSource(
      Long tenantId, Long characterId, Item item, String stackFamilyKey, String notFoundMessage) {
    List<ItemStack> stacks =
        itemStackRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                tenantId, characterId, item.getId());
    return requireSingleStackSource(stacks, item, stackFamilyKey, notFoundMessage);
  }

  private ItemStack requireSingleRoomStackSource(
      Long tenantId,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      String stackFamilyKey,
      String notFoundMessage) {
    List<ItemStack> stacks =
        itemStackRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
                tenantId, gameInstanceId, roomInstanceId, item.getId());
    return requireSingleStackSource(stacks, item, stackFamilyKey, notFoundMessage);
  }

  private ItemStack requireSingleStackSource(
      List<ItemStack> stacks, Item item, String stackFamilyKey, String notFoundMessage) {
    if (stacks.isEmpty()) {
      throw new IllegalArgumentException(notFoundMessage);
    }
    if (stackFamilyKey != null) {
      return stacks.stream()
          .filter(
              stack ->
                  stackFamilyKey.equals(
                      stackableItemSupport.normalizeStackFamilyKey(stack.getStackFamilyKey())))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(notFoundMessage));
    }
    if (stacks.size() > 1) {
      throw new IllegalArgumentException(
          "Multiple stack families exist for item "
              + item.getId()
              + "; explicit stack selection required");
    }
    return stacks.get(0);
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
      if (quantity != 1) {
        throw new IllegalArgumentException("Explicit item_instance_id requires quantity 1");
      }
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
      Character character,
      List<ItemInstance> instances,
      String gameInstanceId,
      String roomInstanceId,
      String effectId,
      String sessionId) {
    for (ItemInstance instance : instances) {
      ItemTransferSupport.ExpectedSource expectedSource =
          itemTransferSupport.inventory(character.getTenantId(), character.getId());
      ItemTransferSupport.Destination destination =
          itemTransferSupport.room(gameInstanceId, roomInstanceId);
      ItemTransferSupport.TransferAuditContext auditContext =
          itemTransferSupport.audit("DROP", character.getId(), sessionId, effectId);
      itemTransferSupport.transfer(instance, expectedSource, destination, auditContext);
      itemInstanceRepository.save(instance);
      itemTransferAuditWriter.recordInstanceTransfer(
          instance, expectedSource, destination, auditContext);
      containerHolderSyncSupport.ensureSynced(instance);
    }
  }

  private void moveItemInstancesToInventory(
      Character character, List<ItemInstance> instances, String effectId, String sessionId) {
    for (ItemInstance instance : instances) {
      ItemTransferSupport.ExpectedSource expectedSource =
          itemTransferSupport.room(
              character.getTenantId(), instance.getGameInstanceId(), instance.getRoomInstanceId());
      ItemTransferSupport.Destination destination = itemTransferSupport.inventory(character);
      ItemTransferSupport.TransferAuditContext auditContext =
          itemTransferSupport.audit("GET", character.getId(), sessionId, effectId);
      itemTransferSupport.transfer(instance, expectedSource, destination, auditContext);
      itemInstanceRepository.save(instance);
      itemTransferAuditWriter.recordInstanceTransfer(
          instance, expectedSource, destination, auditContext);
      containerHolderSyncSupport.ensureSynced(instance);
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

  private InventoryEntryDto toInventoryDto(ItemStack stack) {
    return new InventoryEntryDto(
        stack.getTenantId(),
        stack.getCharacter().getId(),
        stack.getItem().getId(),
        stack.getItem().getName(),
        stack.getItem().getDescription(),
        stack.getQuantity(),
        null,
        null,
        stackSelector(stack));
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

  private InventoryEntryDto inventoryDtoForStackMutation(
      Character character, Item item, String stackFamilyKey, int quantity) {
    return new InventoryEntryDto(
        character.getTenantId(),
        character.getId(),
        item.getId(),
        item.getName(),
        item.getDescription(),
        quantity,
        null,
        null,
        stackFamilyKey);
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

  private RoomGroundInventoryEntryDto toRoomGroundDto(ItemStack stack) {
    return new RoomGroundInventoryEntryDto(
        stack.getTenantId(),
        stack.getGameInstanceId(),
        stack.getRoomInstanceId(),
        stack.getItem().getId(),
        stack.getItem().getName(),
        stack.getItem().getDescription(),
        stack.getQuantity(),
        null,
        null,
        stackSelector(stack));
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

  private RoomGroundInventoryEntryDto roomGroundDtoForStackMutation(
      Long tenantId,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      String stackFamilyKey,
      int quantity) {
    return new RoomGroundInventoryEntryDto(
        tenantId,
        gameInstanceId,
        roomInstanceId,
        item.getId(),
        item.getName(),
        item.getDescription(),
        quantity,
        null,
        null,
        stackFamilyKey);
  }

  private String stackSelector(ItemStack stack) {
    return stackableItemSupport.normalizeStackFamilyKey(stack.getStackFamilyKey());
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

  private String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be provided");
    }
    return value.trim();
  }

  private String normalizeOptionalText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private Comparator<InventoryEntryDto> inventoryOrdering() {
    return Comparator.comparing((InventoryEntryDto dto) -> dto.itemName().toLowerCase())
        .thenComparingInt(dto -> dto.itemInstanceId() == null ? 1 : 0)
        .thenComparingLong(
            dto -> dto.itemInstanceId() == null ? Long.MAX_VALUE : dto.itemInstanceId())
        .thenComparing(InventoryEntryDto::itemId);
  }

  private Comparator<RoomGroundInventoryEntryDto> roomGroundOrdering() {
    return Comparator.comparing((RoomGroundInventoryEntryDto dto) -> dto.itemName().toLowerCase())
        .thenComparingInt(dto -> dto.itemInstanceId() == null ? 1 : 0)
        .thenComparingLong(
            dto -> dto.itemInstanceId() == null ? Long.MAX_VALUE : dto.itemInstanceId())
        .thenComparing(RoomGroundInventoryEntryDto::itemId);
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
