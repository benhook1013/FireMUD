package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EquipmentService {
  Page<CharacterEquipmentEntryDto> listEquipment(
      Long tenantId, Long characterId, Pageable pageable);

  default CharacterEquipmentEntryDto wearItem(
      Long tenantId, Long characterId, Long itemId, Long itemInstanceId) {
    return wearItem(tenantId, characterId, itemId, itemInstanceId, null);
  }

  default CharacterEquipmentEntryDto wearItem(
      Long tenantId, Long characterId, Long itemId, Long itemInstanceId, String effectId) {
    return wearItem(tenantId, characterId, itemId, itemInstanceId, effectId, null);
  }

  CharacterEquipmentEntryDto wearItem(
      Long tenantId,
      Long characterId,
      Long itemId,
      Long itemInstanceId,
      String effectId,
      String sessionId);

  default CharacterEquipmentEntryDto removeWornItem(Long tenantId, Long characterId, String slot) {
    return removeWornItem(tenantId, characterId, slot, null);
  }

  default CharacterEquipmentEntryDto removeWornItem(
      Long tenantId, Long characterId, String slot, String effectId) {
    return removeWornItem(tenantId, characterId, slot, effectId, null);
  }

  CharacterEquipmentEntryDto removeWornItem(
      Long tenantId, Long characterId, String slot, String effectId, String sessionId);
}
