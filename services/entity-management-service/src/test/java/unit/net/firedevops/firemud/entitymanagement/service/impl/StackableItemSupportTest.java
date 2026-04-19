package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemStackCompatibilityMode;
import org.junit.jupiter.api.Test;

class StackableItemSupportTest {
  private final StackableItemSupport support = new StackableItemSupport();

  @Test
  void compatibilityFingerprintDefaultsToDefinitionOnly() {
    Item item = item(7L);

    assertEquals("item-definition:7", support.compatibilityFingerprint(item));
  }

  @Test
  void compatibilityFingerprintUsesFamilyWhenConfigured() {
    Item item = item(7L);
    item.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);
    item.setDefaultStackFamilyKey("ammo/iron");

    assertEquals("item-definition:7:family:ammo/iron", support.compatibilityFingerprint(item));
  }

  @Test
  void familyModeRequiresFamilyKey() {
    Item item = item(7L);
    item.setStackCompatibilityMode(ItemStackCompatibilityMode.DEFINITION_AND_FAMILY);

    assertThrows(IllegalArgumentException.class, () -> support.compatibilityFingerprint(item));
  }

  private static Item item(Long id) {
    Item item = new Item();
    item.setId(id);
    item.setTenantId(1L);
    item.setName("Arrows");
    item.setStackable(true);
    return item;
  }
}
