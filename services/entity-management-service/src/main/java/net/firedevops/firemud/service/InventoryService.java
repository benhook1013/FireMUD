package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.InventoryEntryDto;

public interface InventoryService {
  List<InventoryEntryDto> listInventory(Long characterId);
}
