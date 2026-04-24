package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EquipmentService {
  Page<CharacterEquipmentEntryDto> listEquipment(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Pageable pageable);

  default CharacterEquipmentEntryDto wearItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long itemId,
      Long itemInstanceId) {
    return wearItem(
        tenantId, characterId, gameInstanceId, playableStateScope, itemId, itemInstanceId, null);
  }

  default CharacterEquipmentEntryDto wearItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long itemId,
      Long itemInstanceId,
      String effectId) {
    return wearItem(
        tenantId,
        characterId,
        gameInstanceId,
        playableStateScope,
        itemId,
        itemInstanceId,
        effectId,
        null);
  }

  CharacterEquipmentEntryDto wearItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long itemId,
      Long itemInstanceId,
      String effectId,
      String sessionId);

  default CharacterEquipmentEntryDto removeWornItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String slot) {
    return removeWornItem(tenantId, characterId, gameInstanceId, playableStateScope, slot, null);
  }

  default CharacterEquipmentEntryDto removeWornItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String slot,
      String effectId) {
    return removeWornItem(
        tenantId, characterId, gameInstanceId, playableStateScope, slot, effectId, null);
  }

  CharacterEquipmentEntryDto removeWornItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String slot,
      String effectId,
      String sessionId);
}
