package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ContainerService {
  Page<ContainerContentEntryDto> listContainerContents(
      Long tenantId, Long characterId, Long containerInstanceId, Pageable pageable);

  default Page<ContainerContentEntryDto> listContainerContents(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      String roomInstanceId,
      Pageable pageable) {
    return listContainerContents(tenantId, characterId, containerInstanceId, pageable);
  }

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

  default ContainerContentEntryDto putItemIntoContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    return putItemIntoContainer(
        tenantId,
        characterId,
        containerInstanceId,
        gameInstanceId,
        roomInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  default ContainerContentEntryDto putItemIntoContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    return putItemIntoContainer(
        tenantId,
        characterId,
        containerInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
        null);
  }

  default ContainerContentEntryDto putItemIntoContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    return putItemIntoContainer(
        tenantId,
        characterId,
        containerInstanceId,
        gameInstanceId,
        roomInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
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
      String effectId,
      String sessionId);

  default ContainerContentEntryDto putItemIntoContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId) {
    return putItemIntoContainer(
        tenantId,
        characterId,
        containerInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
        sessionId);
  }

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

  default InventoryEntryDto takeItemFromContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    return takeItemFromContainer(
        tenantId,
        characterId,
        containerInstanceId,
        gameInstanceId,
        roomInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  default InventoryEntryDto takeItemFromContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    return takeItemFromContainer(
        tenantId,
        characterId,
        containerInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
        null);
  }

  default InventoryEntryDto takeItemFromContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    return takeItemFromContainer(
        tenantId,
        characterId,
        containerInstanceId,
        gameInstanceId,
        roomInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
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
      String effectId,
      String sessionId);

  default InventoryEntryDto takeItemFromContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId) {
    return takeItemFromContainer(
        tenantId,
        characterId,
        containerInstanceId,
        itemId,
        itemInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
        sessionId);
  }
}
