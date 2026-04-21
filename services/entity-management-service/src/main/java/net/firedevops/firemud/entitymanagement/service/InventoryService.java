package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryService {
  Page<InventoryEntryDto> listInventory(Long tenantId, Long characterId, Pageable pageable);

  InventoryEntryDto addItem(Long tenantId, Long characterId, Long itemId, int quantity);

  void removeItem(Long tenantId, Long characterId, Long itemId);

  Page<RoomGroundInventoryEntryDto> listRoomGroundItems(
      Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable);

  default RoomGroundInventoryEntryDto dropItemToRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
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
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  RoomGroundInventoryEntryDto dropItemToRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId);

  default InventoryEntryDto pickupItemFromRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
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
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  InventoryEntryDto pickupItemFromRoom(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Long itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId);
}
