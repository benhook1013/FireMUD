package net.firedevops.firemud.entitymanagement.repository;

import java.util.List;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentEntry;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentKey;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemStack;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

final class JooqEntityManagementRepositorySupport {
  private JooqEntityManagementRepositorySupport() {}

  static <T> Page<T> page(List<T> content, Pageable pageable, long total) {
    if (pageable == null || pageable.isUnpaged()) {
      return new PageImpl<>(content);
    }
    return new PageImpl<>(content, pageable, total);
  }

  static int limitOrDefault(Pageable pageable, int fallback) {
    return JooqPersistenceSupport.limitOrDefault(pageable, fallback);
  }

  static int offsetOrZero(Pageable pageable) {
    return JooqPersistenceSupport.offsetOrZero(pageable);
  }

  static Character partialCharacter(
      Long id, Long tenantId, Long accountId, String playableStateKey, String name) {
    if (id == null) {
      return null;
    }
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    character.setAccountId(accountId);
    character.setPlayableStateKey(playableStateKey);
    character.setName(name);
    return character;
  }

  static Item partialItem(
      Long id,
      Long tenantId,
      Long versionId,
      String name,
      String description,
      String equipmentSlot,
      String equipmentSlotGroupKey,
      Boolean container,
      Boolean stackable,
      String stackCompatibilityMode,
      String stackVariantKey,
      String effectPayloadJson) {
    if (id == null) {
      return null;
    }
    Item item = new Item();
    item.setId(id);
    item.setTenantId(tenantId);
    if (versionId == null) {
      item.setVersionId(1L);
    } else {
      item.setVersionId(versionId);
    }
    item.setName(name);
    item.setDescription(description);
    item.setEquipmentSlot(equipmentSlot);
    item.setEquipmentSlotGroupKey(equipmentSlotGroupKey);
    item.setContainer(Boolean.TRUE.equals(container));
    item.setStackable(Boolean.TRUE.equals(stackable));
    item.setStackCompatibilityMode(
        stackCompatibilityMode == null
            ? ItemStackCompatibilityMode.DEFINITION_ONLY
            : ItemStackCompatibilityMode.valueOf(stackCompatibilityMode));
    item.setStackVariantKey(stackVariantKey);
    item.setEffectPayloadJson(effectPayloadJson);
    return item;
  }

  static ContainerInstance partialContainer(
      Long id,
      Long tenantId,
      Character character,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      Item item,
      Long itemInstanceId) {
    if (id == null) {
      return null;
    }
    ContainerInstance container = new ContainerInstance();
    container.setId(id);
    container.setTenantId(tenantId);
    container.setCharacter(character);
    container.setEquipmentSlot(equipmentSlot);
    container.setGameInstanceId(gameInstanceId);
    container.setRoomInstanceId(roomInstanceId);
    container.setItem(item);
    if (itemInstanceId != null) {
      net.firedevops.firemud.entitymanagement.entity.ItemInstance itemInstance =
          new net.firedevops.firemud.entitymanagement.entity.ItemInstance();
      itemInstance.setId(itemInstanceId);
      container.setItemInstance(itemInstance);
    }
    return container;
  }

  static ItemInstance partialItemInstance(
      Long id,
      Long tenantId,
      Character character,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      ContainerInstance containerInstance,
      Item item,
      String visibleRefToken,
      Long visibleRefSequence,
      String visibleRef,
      Integer version) {
    if (id == null) {
      return null;
    }
    ItemInstance instance = new ItemInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setCharacter(character);
    instance.setEquipmentSlot(equipmentSlot);
    instance.setGameInstanceId(gameInstanceId);
    instance.setRoomInstanceId(roomInstanceId);
    instance.setContainerInstance(containerInstance);
    instance.setItem(item);
    instance.setVisibleRefToken(visibleRefToken);
    instance.setVisibleRefSequence(visibleRefSequence);
    instance.setVisibleRef(visibleRef);
    instance.setVersion(version == null ? 0 : version);
    return instance;
  }

  static ItemStack partialItemStack(
      Long id,
      Long tenantId,
      Character character,
      String equipmentSlot,
      String gameInstanceId,
      String roomInstanceId,
      ContainerInstance containerInstance,
      Item item,
      String compatibilityFingerprint,
      String stackFamilyKey,
      Integer quantity,
      Integer version) {
    if (id == null) {
      return null;
    }
    ItemStack stack = new ItemStack();
    stack.setId(id);
    stack.setTenantId(tenantId);
    stack.setCharacter(character);
    stack.setEquipmentSlot(equipmentSlot);
    stack.setGameInstanceId(gameInstanceId);
    stack.setRoomInstanceId(roomInstanceId);
    stack.setContainerInstance(containerInstance);
    stack.setItem(item);
    stack.setCompatibilityFingerprint(compatibilityFingerprint);
    stack.setStackFamilyKey(stackFamilyKey);
    stack.setQuantity(quantity == null ? 0 : quantity);
    stack.setVersion(version == null ? 0 : version);
    return stack;
  }

  static InventoryEntry partialInventoryEntry(
      Long characterId,
      Long itemId,
      Character character,
      Item item,
      Integer quantity,
      Integer version) {
    if (characterId == null || itemId == null) {
      return null;
    }
    InventoryEntry entry = new InventoryEntry();
    InventoryKey key = new InventoryKey();
    key.setCharacterId(characterId);
    key.setItemId(itemId);
    entry.setId(key);
    entry.setCharacter(character);
    entry.setItem(item);
    entry.setQuantity(quantity == null ? 0 : quantity);
    entry.setVersion(version == null ? 0 : version);
    return entry;
  }

  static CharacterEquipmentEntry partialCharacterEquipmentEntry(
      Long characterId, String slot, Character character, Item item, Integer version) {
    if (characterId == null || slot == null) {
      return null;
    }
    CharacterEquipmentEntry entry = new CharacterEquipmentEntry();
    CharacterEquipmentKey key = new CharacterEquipmentKey();
    key.setCharacterId(characterId);
    key.setSlot(slot);
    entry.setId(key);
    entry.setCharacter(character);
    entry.setItem(item);
    entry.setVersion(version == null ? 0 : version);
    return entry;
  }

  static RoomGroundInventoryEntry partialRoomGroundInventoryEntry(
      Long tenantId,
      String gameInstanceId,
      String roomInstanceId,
      Long itemId,
      Item item,
      Integer quantity,
      Integer version) {
    if (tenantId == null || gameInstanceId == null || roomInstanceId == null || itemId == null) {
      return null;
    }
    RoomGroundInventoryEntry entry = new RoomGroundInventoryEntry();
    RoomGroundInventoryKey key = new RoomGroundInventoryKey();
    key.setTenantId(tenantId);
    key.setGameInstanceId(gameInstanceId);
    key.setRoomInstanceId(roomInstanceId);
    key.setItemId(itemId);
    entry.setId(key);
    entry.setItem(item);
    entry.setQuantity(quantity == null ? 0 : quantity);
    entry.setVersion(version == null ? 0 : version);
    return entry;
  }
}
