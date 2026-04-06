package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EquipmentService {
  Page<CharacterEquipmentEntryDto> listEquipment(
      Long tenantId, Long characterId, Pageable pageable);

  CharacterEquipmentEntryDto wearItem(Long tenantId, Long characterId, Long itemId);

  CharacterEquipmentEntryDto removeWornItem(Long tenantId, Long characterId, String slot);
}
