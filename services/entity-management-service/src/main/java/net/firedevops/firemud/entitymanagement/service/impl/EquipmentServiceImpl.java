package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentEntry;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentKey;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.mapper.CharacterEquipmentEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterEquipmentRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EquipmentServiceImpl implements EquipmentService {
  private final CharacterEquipmentRepository equipmentRepository;
  private final CharacterEquipmentEntryMapper equipmentMapper;
  private final InventoryEntryRepository inventoryRepository;
  private final CharacterRepository characterRepository;
  private final ItemRepository itemRepository;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "equipment.list")
  public Page<CharacterEquipmentEntryDto> listEquipment(
      Long tenantId, Long characterId, Pageable pageable) {
    requireCharacter(tenantId, characterId);
    return equipmentRepository
        .findByIdCharacterIdAndCharacterTenantId(characterId, tenantId, pageable)
        .map(equipmentMapper::toDto);
  }

  @Override
  @Transactional
  @Timed(value = "equipment.wear")
  public CharacterEquipmentEntryDto wearItem(Long tenantId, Long characterId, Long itemId) {
    Character character = requireCharacter(tenantId, characterId);
    Item item = requireWearableItem(tenantId, itemId);
    String slot = normalizeSlot(requireWearableSlot(item));
    CharacterEquipmentKey key = equipmentKey(character.getId(), slot);
    if (equipmentRepository.findById(key).isPresent()) {
      throw new IllegalArgumentException("Equipment slot is occupied");
    }
    InventoryEntry carried = requireInventoryEntry(character.getId(), itemId);
    adjustInventoryQuantity(carried, -1);
    CharacterEquipmentEntry entry = new CharacterEquipmentEntry();
    entry.setId(key);
    entry.setCharacter(character);
    entry.setItem(item);
    return equipmentMapper.toDto(equipmentRepository.save(entry));
  }

  @Override
  @Transactional
  @Timed(value = "equipment.remove")
  public CharacterEquipmentEntryDto removeWornItem(Long tenantId, Long characterId, String slot) {
    String normalizedSlot = normalizeSlot(requireText(slot, "slot"));
    Character character = requireCharacter(tenantId, characterId);
    CharacterEquipmentEntry entry =
        equipmentRepository
            .findById(equipmentKey(character.getId(), normalizedSlot))
            .orElseThrow(() -> new IllegalArgumentException("Equipment slot is empty"));
    Item item = entry.getItem();
    equipmentRepository.delete(entry);
    upsertInventoryEntry(character, item, 1);
    return equipmentMapper.toDto(entry);
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
    String slot = requireText(item.getEquipmentSlot(), "equipmentSlot");
    return slot;
  }

  private InventoryEntry requireInventoryEntry(Long characterId, Long itemId) {
    return inventoryRepository
        .findById(inventoryKey(characterId, itemId))
        .orElseThrow(() -> new IllegalArgumentException("Inventory item not found"));
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

  private void adjustQuantity(
      Supplier<Integer> currentQuantity, java.util.function.IntConsumer quantityWriter, int delta) {
    int nextQuantity = currentQuantity.get() + delta;
    if (nextQuantity < 0) {
      throw new IllegalArgumentException("Quantity cannot go negative");
    }
    quantityWriter.accept(nextQuantity);
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

  private InventoryKey inventoryKey(Long characterId, Long itemId) {
    InventoryKey key = new InventoryKey();
    key.setCharacterId(characterId);
    key.setItemId(itemId);
    return key;
  }

  private CharacterEquipmentKey equipmentKey(Long characterId, String slot) {
    CharacterEquipmentKey key = new CharacterEquipmentKey();
    key.setCharacterId(characterId);
    key.setSlot(slot);
    return key;
  }
}
