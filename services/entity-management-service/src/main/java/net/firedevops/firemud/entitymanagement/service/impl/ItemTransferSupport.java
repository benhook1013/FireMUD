package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import org.springframework.stereotype.Component;

/** Applies guarded handoff semantics to item-instance holder mutations. */
@Component
final class ItemTransferSupport {

  void transfer(ItemInstance instance, ExpectedSource expectedSource, Destination destination) {
    requireExpectedSource(instance, expectedSource);
    applyDestination(instance, destination);
  }

  ExpectedSource inventory(Long tenantId, Long characterId) {
    return new ExpectedSource(tenantId, HolderKind.INVENTORY, characterId, null, null, null, null);
  }

  ExpectedSource room(Long tenantId, String gameInstanceId, String roomInstanceId) {
    return new ExpectedSource(
        tenantId, HolderKind.ROOM_GROUND, null, null, gameInstanceId, roomInstanceId, null);
  }

  ExpectedSource equipment(Long tenantId, Long characterId, String equipmentSlot) {
    return new ExpectedSource(
        tenantId, HolderKind.EQUIPMENT, characterId, equipmentSlot, null, null, null);
  }

  ExpectedSource container(Long tenantId, Long containerInstanceId) {
    return new ExpectedSource(
        tenantId, HolderKind.CONTAINER, null, null, null, null, containerInstanceId);
  }

  Destination inventory(Character character) {
    return new Destination(HolderKind.INVENTORY, character, null, null, null, null);
  }

  Destination room(String gameInstanceId, String roomInstanceId) {
    return new Destination(
        HolderKind.ROOM_GROUND, null, null, gameInstanceId, roomInstanceId, null);
  }

  Destination equipment(Character character, String equipmentSlot) {
    return new Destination(HolderKind.EQUIPMENT, character, equipmentSlot, null, null, null);
  }

  Destination container(ContainerInstance containerInstance) {
    return new Destination(HolderKind.CONTAINER, null, null, null, null, containerInstance);
  }

  private void requireExpectedSource(ItemInstance instance, ExpectedSource expectedSource) {
    if (!instance.getTenantId().equals(expectedSource.tenantId())) {
      throw new IllegalArgumentException("Item tenant mismatch");
    }
    switch (expectedSource.kind()) {
      case INVENTORY -> requireInventorySource(instance, expectedSource.characterId());
      case ROOM_GROUND ->
          requireRoomSource(
              instance, expectedSource.gameInstanceId(), expectedSource.roomInstanceId());
      case EQUIPMENT ->
          requireEquipmentSource(
              instance, expectedSource.characterId(), expectedSource.equipmentSlot());
      case CONTAINER -> requireContainerSource(instance, expectedSource.containerInstanceId());
    }
  }

  private void applyDestination(ItemInstance instance, Destination destination) {
    switch (destination.kind()) {
      case INVENTORY -> {
        instance.setCharacter(destination.character());
        instance.setEquipmentSlot(null);
        instance.setGameInstanceId(null);
        instance.setRoomInstanceId(null);
        instance.setContainerInstance(null);
      }
      case ROOM_GROUND -> {
        instance.setCharacter(null);
        instance.setEquipmentSlot(null);
        instance.setGameInstanceId(destination.gameInstanceId());
        instance.setRoomInstanceId(destination.roomInstanceId());
        instance.setContainerInstance(null);
      }
      case EQUIPMENT -> {
        instance.setCharacter(destination.character());
        instance.setEquipmentSlot(destination.equipmentSlot());
        instance.setGameInstanceId(null);
        instance.setRoomInstanceId(null);
        instance.setContainerInstance(null);
      }
      case CONTAINER -> {
        instance.setCharacter(null);
        instance.setEquipmentSlot(null);
        instance.setGameInstanceId(null);
        instance.setRoomInstanceId(null);
        instance.setContainerInstance(destination.containerInstance());
      }
    }
  }

  private void requireInventorySource(ItemInstance instance, Long characterId) {
    if (instance.getCharacter() == null
        || !instance.getCharacter().getId().equals(characterId)
        || instance.getEquipmentSlot() != null
        || instance.getGameInstanceId() != null
        || instance.getRoomInstanceId() != null
        || instance.getContainerInstance() != null) {
      throw new IllegalArgumentException("Item no longer in expected inventory source");
    }
  }

  private void requireRoomSource(
      ItemInstance instance, String gameInstanceId, String roomInstanceId) {
    if (instance.getCharacter() != null
        || instance.getEquipmentSlot() != null
        || instance.getContainerInstance() != null
        || !gameInstanceId.equals(instance.getGameInstanceId())
        || !roomInstanceId.equals(instance.getRoomInstanceId())) {
      throw new IllegalArgumentException("Item no longer on expected room ground source");
    }
  }

  private void requireEquipmentSource(
      ItemInstance instance, Long characterId, String equipmentSlot) {
    if (instance.getCharacter() == null
        || !instance.getCharacter().getId().equals(characterId)
        || !equipmentSlot.equals(instance.getEquipmentSlot())
        || instance.getGameInstanceId() != null
        || instance.getRoomInstanceId() != null
        || instance.getContainerInstance() != null) {
      throw new IllegalArgumentException("Item no longer in expected equipment source");
    }
  }

  private void requireContainerSource(ItemInstance instance, Long containerInstanceId) {
    if (instance.getCharacter() != null
        || instance.getEquipmentSlot() != null
        || instance.getGameInstanceId() != null
        || instance.getRoomInstanceId() != null
        || instance.getContainerInstance() == null
        || !instance.getContainerInstance().getId().equals(containerInstanceId)) {
      throw new IllegalArgumentException("Item no longer in expected container source");
    }
  }

  private enum HolderKind {
    INVENTORY,
    ROOM_GROUND,
    EQUIPMENT,
    CONTAINER
  }

  private record ExpectedSource(
      Long tenantId,
      HolderKind kind,
      Long characterId,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      Long containerInstanceId) {}

  private record Destination(
      HolderKind kind,
      Character character,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      ContainerInstance containerInstance) {}
}
