package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EquipmentServiceImpl implements EquipmentService {
  private final ItemInstanceRepository itemInstanceRepository;
  private final ContainerInstanceRepository containerInstanceRepository;
  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "equipment.list")
  public Page<CharacterEquipmentEntryDto> listEquipment(
      Long tenantId, Long characterId, Pageable pageable) {
    requireCharacter(tenantId, characterId);
    return itemInstanceRepository
        .findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
            tenantId, characterId, pageable)
        .map(this::toDto);
  }

  @Override
  @Transactional
  @Timed(value = "equipment.wear")
  public CharacterEquipmentEntryDto wearItem(
      Long tenantId, Long characterId, Long itemId, Long itemInstanceId) {
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireWearableItem(tenantId, itemId);
    String slot = normalizeSlot(requireWearableSlot(item));
    if (itemInstanceRepository
        .existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
            tenantId, characterId, slot)) {
      throw new IllegalArgumentException("Equipment slot is occupied");
    }
    ItemInstance instance =
        resolveWearableItemInstance(tenantId, characterId, itemId, itemInstanceId);
    instance.setCharacter(character);
    instance.setEquipmentSlot(slot);
    instance.setGameInstanceId(null);
    instance.setRoomInstanceId(null);
    ItemInstance saved = itemInstanceRepository.save(instance);
    syncContainerHolder(saved);
    return toDto(saved);
  }

  @Override
  @Transactional
  @Timed(value = "equipment.remove")
  public CharacterEquipmentEntryDto removeWornItem(Long tenantId, Long characterId, String slot) {
    String normalizedSlot = normalizeSlot(requireText(slot, "slot"));
    Character character = requireCharacter(tenantId, characterId);
    ItemInstance instance =
        itemInstanceRepository
            .findByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
                tenantId, characterId, normalizedSlot)
            .orElseThrow(() -> new IllegalArgumentException("Equipment slot is empty"));
    instance.setCharacter(character);
    instance.setEquipmentSlot(null);
    instance.setGameInstanceId(null);
    instance.setRoomInstanceId(null);
    ItemInstance saved = itemInstanceRepository.save(instance);
    syncContainerHolder(saved);
    return toDto(saved);
  }

  private Character requireCharacter(Long tenantId, Long characterId) {
    return characterRepository
        .findByIdAndTenantId(characterId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("Character not found for tenant"));
  }

  private Item requireWearableItem(Long tenantId, Long itemId) {
    return itemRepository
        .findByIdAndTenantId(itemId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("Item not found for tenant"));
  }

  private String requireWearableSlot(Item item) {
    return requireText(item.getEquipmentSlot(), "equipmentSlot");
  }

  private void syncContainerHolder(ItemInstance itemInstance) {
    if (itemInstance.getItem() == null || !itemInstance.getItem().isContainer()) {
      return;
    }
    ContainerInstance containerInstance =
        containerInstanceRepository
            .findByItemInstance_Id(itemInstance.getId())
            .orElseGet(
                () -> {
                  ContainerInstance created = new ContainerInstance();
                  created.setItem(itemInstance.getItem());
                  created.setItemInstance(itemInstance);
                  created.setTenantId(itemInstance.getTenantId());
                  return created;
                });
    containerInstance.setTenantId(itemInstance.getTenantId());
    containerInstance.setCharacter(itemInstance.getCharacter());
    containerInstance.setEquipmentSlot(itemInstance.getEquipmentSlot());
    containerInstance.setGameInstanceId(itemInstance.getGameInstanceId());
    containerInstance.setRoomInstanceId(itemInstance.getRoomInstanceId());
    containerInstance.setItem(itemInstance.getItem());
    containerInstance.setItemInstance(itemInstance);
    containerInstanceRepository.save(containerInstance);
  }

  private CharacterEquipmentEntryDto toDto(ItemInstance instance) {
    return new CharacterEquipmentEntryDto(
        instance.getTenantId(),
        instance.getCharacter().getId(),
        instance.getEquipmentSlot(),
        instance.getItem().getId(),
        instance.getItem().getName(),
        instance.getItem().getDescription(),
        instance.getId(),
        resolveContainerInstanceId(instance),
        instance.getVisibleRef());
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

  private ItemInstance resolveWearableItemInstance(
      Long tenantId, Long characterId, Long itemId, Long itemInstanceId) {
    if (itemInstanceId != null) {
      return itemInstanceRepository
          .findByIdAndTenantId(itemInstanceId, tenantId)
          .filter(instance -> instance.getCharacter() != null)
          .filter(instance -> instance.getCharacter().getId().equals(characterId))
          .filter(instance -> instance.getEquipmentSlot() == null)
          .filter(instance -> instance.getGameInstanceId() == null)
          .filter(instance -> instance.getRoomInstanceId() == null)
          .filter(instance -> instance.getItem().getId().equals(itemId))
          .orElseThrow(() -> new IllegalArgumentException("Inventory item not found"));
    }
    return itemInstanceRepository
        .findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
            tenantId, characterId, itemId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Inventory item not found"));
  }

  private String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be provided");
    }
    return value.trim();
  }

  private String normalizeSlot(String slot) {
    return requireText(slot, "slot").toUpperCase(Locale.ROOT);
  }
}
