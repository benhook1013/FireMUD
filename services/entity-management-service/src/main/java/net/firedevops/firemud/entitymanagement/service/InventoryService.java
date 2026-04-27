package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryService {
  Page<InventoryEntryDto> listInventory(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Pageable pageable);

  InventoryEntryDto addItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long itemId,
      int quantity);

  void removeItem(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long itemId);

  Page<RoomGroundInventoryEntryDto> listRoomGroundItems(
      Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable);

  default RoomGroundInventoryEntryDto dropItemToRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity) {
    return dropItemToRoom(
        tenantId,
        characterId,
        gameInstanceId,
        playableStateScope,
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  default RoomGroundInventoryEntryDto dropItemToRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    return dropItemToRoom(
        tenantId,
        characterId,
        gameInstanceId,
        playableStateScope,
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
        null);
  }

  RoomGroundInventoryEntryDto dropItemToRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId);

  default InventoryEntryDto pickupItemFromRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity) {
    return pickupItemFromRoom(
        tenantId,
        characterId,
        gameInstanceId,
        playableStateScope,
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  default InventoryEntryDto pickupItemFromRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    return pickupItemFromRoom(
        tenantId,
        characterId,
        gameInstanceId,
        playableStateScope,
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        effectId,
        null);
  }

  InventoryEntryDto pickupItemFromRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId,
      String sessionId);
}
