package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/** Applies guarded handoff semantics to item-instance holder mutations. */
@Component
final class ItemTransferSupport {
  private static final Logger LOG = LoggingUtil.getLogger(ItemTransferSupport.class);

  void transfer(ItemInstance instance, ExpectedSource expectedSource, Destination destination) {
    transfer(instance, expectedSource, destination, TransferAuditContext.unknown());
  }

  void transfer(
      ItemInstance instance,
      ExpectedSource expectedSource,
      Destination destination,
      TransferAuditContext auditContext) {
    try {
      requireNotAlreadyAtDestination(instance, destination);
      requireExpectedSource(instance, expectedSource);
    } catch (IllegalArgumentException ex) {
      LOG.warn(
          "Rejected item transfer verb={} actorCharacterId={} itemInstanceId={} itemId={} tenantId={} expectedSource={} currentHolder={} destination={}",
          auditContext.verb(),
          auditContext.actorCharacterId(),
          instance.getId(),
          instance.getItem() != null ? instance.getItem().getId() : null,
          instance.getTenantId(),
          describeExpectedSource(expectedSource),
          describeCurrentHolder(instance),
          describeDestination(destination),
          ex);
      throw ex;
    }
    applyDestination(instance, destination);
  }

  TransferAuditContext audit(String verb, Long actorCharacterId) {
    return new TransferAuditContext(verb, actorCharacterId, null, null, null);
  }

  TransferAuditContext audit(String verb, Long actorCharacterId, String effectId) {
    return new TransferAuditContext(verb, actorCharacterId, null, effectId, effectId);
  }

  TransferAuditContext audit(
      String verb, Long actorCharacterId, String sessionId, String effectId) {
    return new TransferAuditContext(verb, actorCharacterId, sessionId, effectId, effectId);
  }

  TransferAuditContext audit(
      String verb, Long actorCharacterId, String sessionId, String effectId, String correlationId) {
    return new TransferAuditContext(verb, actorCharacterId, sessionId, effectId, correlationId);
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

  HolderSnapshot snapshot(ExpectedSource expectedSource) {
    return new HolderSnapshot(
        expectedSource.kind(),
        expectedSource.tenantId(),
        expectedSource.characterId(),
        expectedSource.equipmentSlot(),
        expectedSource.gameInstanceId(),
        expectedSource.roomInstanceId(),
        expectedSource.containerInstanceId());
  }

  HolderSnapshot snapshot(Destination destination) {
    return switch (destination.kind()) {
      case INVENTORY ->
          inventoryHolder(
              destination.character() != null ? destination.character().getTenantId() : null,
              requireCharacterId(destination.character()));
      case ROOM_GROUND ->
          roomHolder(null, destination.gameInstanceId(), destination.roomInstanceId());
      case EQUIPMENT ->
          equipmentHolder(
              destination.character() != null ? destination.character().getTenantId() : null,
              requireCharacterId(destination.character()),
              destination.equipmentSlot());
      case CONTAINER ->
          containerHolder(
              destination.containerInstance() != null
                  ? destination.containerInstance().getTenantId()
                  : null,
              destination.containerInstance() != null
                  ? destination.containerInstance().getId()
                  : null);
    };
  }

  HolderSnapshot inventoryHolder(Long tenantId, Long characterId) {
    return new HolderSnapshot(HolderKind.INVENTORY, tenantId, characterId, null, null, null, null);
  }

  HolderSnapshot roomHolder(Long tenantId, String gameInstanceId, String roomInstanceId) {
    return new HolderSnapshot(
        HolderKind.ROOM_GROUND, tenantId, null, null, gameInstanceId, roomInstanceId, null);
  }

  HolderSnapshot equipmentHolder(Long tenantId, Long characterId, String equipmentSlot) {
    return new HolderSnapshot(
        HolderKind.EQUIPMENT, tenantId, characterId, equipmentSlot, null, null, null);
  }

  HolderSnapshot containerHolder(Long tenantId, Long containerInstanceId) {
    return new HolderSnapshot(
        HolderKind.CONTAINER, tenantId, null, null, null, null, containerInstanceId);
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

  private void requireNotAlreadyAtDestination(ItemInstance instance, Destination destination) {
    if (matchesDestination(instance, destination)) {
      throw new IllegalArgumentException("Item already at destination");
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

  private boolean matchesDestination(ItemInstance instance, Destination destination) {
    return switch (destination.kind()) {
      case INVENTORY ->
          instance.getCharacter() != null
              && destination.character() != null
              && instance.getCharacter().getId().equals(destination.character().getId())
              && instance.getEquipmentSlot() == null
              && instance.getGameInstanceId() == null
              && instance.getRoomInstanceId() == null
              && instance.getContainerInstance() == null;
      case ROOM_GROUND ->
          instance.getCharacter() == null
              && instance.getEquipmentSlot() == null
              && instance.getContainerInstance() == null
              && destination.gameInstanceId() != null
              && destination.roomInstanceId() != null
              && destination.gameInstanceId().equals(instance.getGameInstanceId())
              && destination.roomInstanceId().equals(instance.getRoomInstanceId());
      case EQUIPMENT ->
          instance.getCharacter() != null
              && destination.character() != null
              && instance.getCharacter().getId().equals(destination.character().getId())
              && destination.equipmentSlot() != null
              && destination.equipmentSlot().equals(instance.getEquipmentSlot())
              && instance.getGameInstanceId() == null
              && instance.getRoomInstanceId() == null
              && instance.getContainerInstance() == null;
      case CONTAINER ->
          instance.getCharacter() == null
              && instance.getEquipmentSlot() == null
              && instance.getGameInstanceId() == null
              && instance.getRoomInstanceId() == null
              && instance.getContainerInstance() != null
              && destination.containerInstance() != null
              && instance
                  .getContainerInstance()
                  .getId()
                  .equals(destination.containerInstance().getId());
    };
  }

  private String describeExpectedSource(ExpectedSource expectedSource) {
    return switch (expectedSource.kind()) {
      case INVENTORY -> "inventory(characterId=" + expectedSource.characterId() + ")";
      case ROOM_GROUND ->
          "room(gameInstanceId="
              + expectedSource.gameInstanceId()
              + ", roomInstanceId="
              + expectedSource.roomInstanceId()
              + ")";
      case EQUIPMENT ->
          "equipment(characterId="
              + expectedSource.characterId()
              + ", slot="
              + expectedSource.equipmentSlot()
              + ")";
      case CONTAINER ->
          "container(containerInstanceId=" + expectedSource.containerInstanceId() + ")";
    };
  }

  private String describeDestination(Destination destination) {
    return switch (destination.kind()) {
      case INVENTORY ->
          "inventory(characterId=" + requireCharacterId(destination.character()) + ")";
      case ROOM_GROUND ->
          "room(gameInstanceId="
              + destination.gameInstanceId()
              + ", roomInstanceId="
              + destination.roomInstanceId()
              + ")";
      case EQUIPMENT ->
          "equipment(characterId="
              + requireCharacterId(destination.character())
              + ", slot="
              + destination.equipmentSlot()
              + ")";
      case CONTAINER ->
          "container(containerInstanceId="
              + (destination.containerInstance() != null
                  ? destination.containerInstance().getId()
                  : null)
              + ")";
    };
  }

  private String describeCurrentHolder(ItemInstance instance) {
    if (instance.getContainerInstance() != null) {
      return "container(containerInstanceId=" + instance.getContainerInstance().getId() + ")";
    }
    if (instance.getCharacter() != null && instance.getEquipmentSlot() != null) {
      return "equipment(characterId="
          + instance.getCharacter().getId()
          + ", slot="
          + instance.getEquipmentSlot()
          + ")";
    }
    if (instance.getCharacter() != null) {
      return "inventory(characterId=" + instance.getCharacter().getId() + ")";
    }
    if (instance.getGameInstanceId() != null || instance.getRoomInstanceId() != null) {
      return "room(gameInstanceId="
          + instance.getGameInstanceId()
          + ", roomInstanceId="
          + instance.getRoomInstanceId()
          + ")";
    }
    return "unbound";
  }

  private Long requireCharacterId(Character character) {
    return character != null ? character.getId() : null;
  }

  enum HolderKind {
    INVENTORY,
    ROOM_GROUND,
    EQUIPMENT,
    CONTAINER
  }

  record HolderSnapshot(
      HolderKind kind,
      Long tenantId,
      Long characterId,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      Long containerInstanceId) {}

  record ExpectedSource(
      Long tenantId,
      HolderKind kind,
      Long characterId,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      Long containerInstanceId) {}

  record Destination(
      HolderKind kind,
      Character character,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      ContainerInstance containerInstance) {}

  record TransferAuditContext(
      String verb, Long actorCharacterId, String sessionId, String effectId, String correlationId) {
    static TransferAuditContext unknown() {
      return new TransferAuditContext("UNKNOWN", null, null, null, null);
    }
  }
}
