package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.InventoryEntryDto;

public interface InventoryService {
  List<InventoryEntryDto> listInventory(Long characterId);

  InventoryEntryDto addItem(Long characterId, Long itemId, int quantity);

  void removeItem(Long characterId, Long itemId);
}
