package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryService {
  Page<InventoryEntryDto> listInventory(Long characterId, Pageable pageable);

  InventoryEntryDto addItem(Long characterId, Long itemId, int quantity);

  void removeItem(Long characterId, Long itemId);
}
