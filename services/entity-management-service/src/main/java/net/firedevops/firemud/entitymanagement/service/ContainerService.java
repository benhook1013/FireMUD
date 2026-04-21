package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ContainerService {
  Page<ContainerContentEntryDto> listContainerContents(
      Long tenantId, Long characterId, Long containerInstanceId, Pageable pageable);

  default ContainerContentEntryDto putItemIntoContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    return putItemIntoContainer(
        tenantId,
        characterId,
        containerInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  ContainerContentEntryDto putItemIntoContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId);

  default InventoryEntryDto takeItemFromContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    return takeItemFromContainer(
        tenantId,
        characterId,
        containerInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  InventoryEntryDto takeItemFromContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId);
}
