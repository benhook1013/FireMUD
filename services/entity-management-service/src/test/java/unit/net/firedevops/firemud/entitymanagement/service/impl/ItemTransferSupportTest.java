package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import org.junit.jupiter.api.Test;

class ItemTransferSupportTest {
  private final ItemTransferSupport support = new ItemTransferSupport();

  @Test
  void transfersInventoryItemToRoomWhenExpectedSourceMatches() {
    Character character = character(11L, 1L);
    ItemInstance instance = inventoryInstance(41L, 1L, character);

    support.transfer(
        instance,
        support.inventory(1L, 11L),
        support.room("GI-1", "ROOM-2"),
        support.audit("DROP", 11L));

    assertNull(instance.getCharacter());
    assertNull(instance.getEquipmentSlot());
    assertEquals("GI-1", instance.getGameInstanceId());
    assertEquals("ROOM-2", instance.getRoomInstanceId());
    assertNull(instance.getContainerInstance());
  }

  @Test
  void rejectsStaleRoomSourceMismatch() {
    ItemInstance instance = roomInstance(41L, 1L, "GI-OTHER", "ROOM-9");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                support.transfer(
                    instance,
                    support.room(1L, "GI-1", "ROOM-2"),
                    support.inventory(character(11L, 1L)),
                    support.audit("GET", 11L)));

    assertEquals("Item no longer on expected room ground source", ex.getMessage());
    assertEquals("GI-OTHER", instance.getGameInstanceId());
    assertEquals("ROOM-9", instance.getRoomInstanceId());
  }

  @Test
  void rejectsAlreadyAtDestinationForEquipmentMove() {
    Character character = character(11L, 1L);
    ItemInstance instance = equippedInstance(41L, 1L, character, "HEAD");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                support.transfer(
                    instance,
                    support.inventory(1L, 11L),
                    support.equipment(character, "HEAD"),
                    support.audit("WEAR", 11L)));

    assertEquals("Item already at destination", ex.getMessage());
    assertEquals(character, instance.getCharacter());
    assertEquals("HEAD", instance.getEquipmentSlot());
  }

  @Test
  void rejectsContainerTransferWhenTenantDoesNotMatchExpectedSource() {
    ContainerInstance container = containerInstance(99L, 1L);
    ItemInstance instance = containedInstance(41L, 2L, container);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                support.transfer(
                    instance,
                    support.container(1L, 99L),
                    support.inventory(character(11L, 1L)),
                    support.audit("TAKE", 11L)));

    assertEquals("Item tenant mismatch", ex.getMessage());
    assertEquals(container, instance.getContainerInstance());
  }

  private static Character character(Long id, Long tenantId) {
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    return character;
  }

  private static ContainerInstance containerInstance(Long id, Long tenantId) {
    ContainerInstance instance = new ContainerInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    return instance;
  }

  private static Item item(Long id, Long tenantId) {
    Item item = new Item();
    item.setId(id);
    item.setTenantId(tenantId);
    item.setName("Torch");
    item.setDescription("Torch desc");
    return item;
  }

  private static ItemInstance inventoryInstance(Long id, Long tenantId, Character character) {
    ItemInstance instance = baseInstance(id, tenantId);
    instance.setCharacter(character);
    return instance;
  }

  private static ItemInstance roomInstance(
      Long id, Long tenantId, String gameInstanceId, String roomInstanceId) {
    ItemInstance instance = baseInstance(id, tenantId);
    instance.setGameInstanceId(gameInstanceId);
    instance.setRoomInstanceId(roomInstanceId);
    return instance;
  }

  private static ItemInstance equippedInstance(
      Long id, Long tenantId, Character character, String slot) {
    ItemInstance instance = baseInstance(id, tenantId);
    instance.setCharacter(character);
    instance.setEquipmentSlot(slot);
    return instance;
  }

  private static ItemInstance containedInstance(
      Long id, Long tenantId, ContainerInstance containerInstance) {
    ItemInstance instance = baseInstance(id, tenantId);
    instance.setContainerInstance(containerInstance);
    return instance;
  }

  private static ItemInstance baseInstance(Long id, Long tenantId) {
    ItemInstance instance = new ItemInstance();
    instance.setId(id);
    instance.setTenantId(tenantId);
    instance.setItem(item(7L, tenantId));
    instance.setVisibleRef("torch" + id);
    instance.setVisibleRefToken("torch");
    instance.setVisibleRefSequence(id);
    return instance;
  }
}
