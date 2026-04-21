package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
    return compatibilityFingerprint(item, authoredStackFamilyKey(item));
  }

  String compatibilityFingerprint(Item item, String stackFamilyKey) {
    if (item == null) {
      throw new IllegalArgumentException("item must be provided");
    }
    ItemStackCompatibilityMode mode =
        item.getStackCompatibilityMode() == null
            ? ItemStackCompatibilityMode.DEFINITION_ONLY
            : item.getStackCompatibilityMode();
    return switch (mode) {
      case DEFINITION_ONLY -> "item-definition:" + item.getId();
      case DEFINITION_AND_FAMILY -> {
        String normalizedFamilyKey = normalizeStackFamilyKey(stackFamilyKey);
        if (!StringUtils.hasText(normalizedFamilyKey)) {
          throw new IllegalArgumentException(
              "Stack-compatible item "
                  + item.getId()
                  + " requires stack family key for DEFINITION_AND_FAMILY mode");
        }
        yield "item-definition:" + item.getId() + ":family:" + normalizedFamilyKey;
      }
    };
  }

  String authoredStackFamilyKey(Item item) {
    if (item == null) {
      throw new IllegalArgumentException("item must be provided");
    }
    return normalizeStackFamilyKey(item.getStackVariantKey());
  }

  String normalizeStackFamilyKey(String stackFamilyKey) {
    if (!StringUtils.hasText(stackFamilyKey)) {
      return null;
    }
    return stackFamilyKey.trim();
  }
}
