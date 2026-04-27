package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ContainerService {
  Page<ContainerContentEntryDto> listContainerContents(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Pageable pageable);

  default ContainerContentEntryDto putItemIntoContainer(
      Long tenantId,
      Long characterId,
      Long containerInstanceId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
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
        playableStateScope,
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
      String gameInstanceId,
      PlayableStateScope playableStateScope,
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
        playableStateScope,
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
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
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
      PlayableStateScope playableStateScope,
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
        playableStateScope,
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
      String gameInstanceId,
      PlayableStateScope playableStateScope,
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
        playableStateScope,
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
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId);
}
