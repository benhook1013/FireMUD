package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.entitymanagement.entity.Item;
import org.springframework.stereotype.Component;

/** Resolves whether an item should use holder-local stack storage. */
@Component
final class StackableItemSupport {
  boolean usesStackStorage(Item item) {
    return item != null
        && item.isStackable()
        && !item.isContainer()
        && (item.getEquipmentSlot() == null || item.getEquipmentSlot().isBlank());
  }

  String compatibilityFingerprint(Item item) {
    if (item == null) {
      throw new IllegalArgumentException("item must be provided");
    }
    return "item-definition:" + item.getId();
  }
}
