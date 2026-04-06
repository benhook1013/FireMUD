package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ContainerService {
  Page<ContainerContentEntryDto> listContainerContents(
      Long tenantId, Long characterId, Long containerItemId, Pageable pageable);

  ContainerContentEntryDto putItemIntoContainer(
      Long tenantId, Long characterId, Long containerItemId, Long itemId, int quantity);

  InventoryEntryDto takeItemFromContainer(
      Long tenantId, Long characterId, Long containerItemId, Long itemId, int quantity);
}
