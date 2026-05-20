package net.firedevops.firemud.entitymanagement.repository;

import java.util.List;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;
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
}
