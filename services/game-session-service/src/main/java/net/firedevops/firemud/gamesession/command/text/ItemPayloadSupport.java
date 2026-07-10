package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.ItemMutationResultOutput;
import org.springframework.util.StringUtils;

final class ItemPayloadSupport {
  private ItemPayloadSupport() {}

  static InventoryViewOutput.ItemEntry toItemEntry(InventoryItem item) {
    return new InventoryViewOutput.ItemEntry(
        item.getItemId(),
        item.getItemInstanceId(),
        item.getContainerInstanceId(),
        item.getVisibleRef(),
        item.getItemName(),
        item.getItemDescription(),
        item.getQuantity(),
        "");
  }

  static InventoryViewOutput.ItemEntry toItemEntry(RoomGroundInventoryItem item) {
    return new InventoryViewOutput.ItemEntry(
        item.getItemId(),
        item.getItemInstanceId(),
        item.getContainerInstanceId(),
        item.getVisibleRef(),
        item.getItemName(),
        item.getItemDescription(),
        item.getQuantity(),
        "");
  }

  static InventoryViewOutput.ItemEntry toItemEntry(ContainerItem item) {
    return new InventoryViewOutput.ItemEntry(
        item.getItemId(),
        item.getItemInstanceId(),
        item.getContainerInstanceId(),
        item.getVisibleRef(),
        item.getItemName(),
        item.getItemDescription(),
        item.getQuantity(),
        "");
  }

  static InventoryViewOutput.ItemEntry toItemEntry(EquipmentItem item) {
    return new InventoryViewOutput.ItemEntry(
        item.getItemId(),
        item.getItemInstanceId(),
        item.getContainerInstanceId(),
        item.getVisibleRef(),
        item.getItemName(),
        item.getItemDescription(),
        1,
        item.getSlot());
  }

  static InventoryViewOutput.ItemEntry withFallback(
      InventoryViewOutput.ItemEntry item, String fallbackName, int fallbackQuantity) {
    return new InventoryViewOutput.ItemEntry(
        item.itemId(),
        item.itemInstanceId(),
        item.containerInstanceId(),
        item.visibleRef(),
        StringUtils.hasText(item.itemName()) ? item.itemName() : fallbackName,
        item.itemDescription(),
        item.quantity() > 0 ? item.quantity() : fallbackQuantity,
        item.slot());
  }

  static ItemMutationResultOutput.HolderContext inventoryHolder() {
    return new ItemMutationResultOutput.HolderContext(
        InventoryViewOutput.Source.INVENTORY, "", "", "", "");
  }

  static ItemMutationResultOutput.HolderContext roomGroundHolder() {
    return new ItemMutationResultOutput.HolderContext(
        InventoryViewOutput.Source.ROOM_GROUND, "", "", "", "");
  }

  static ItemMutationResultOutput.HolderContext containerHolder(
      String displayName, String containerInstanceId, String visibleRef) {
    return new ItemMutationResultOutput.HolderContext(
        InventoryViewOutput.Source.CONTAINER, displayName, containerInstanceId, visibleRef, "");
  }

  static ItemMutationResultOutput.HolderContext equipmentHolder(String slot) {
    return new ItemMutationResultOutput.HolderContext(
        InventoryViewOutput.Source.EQUIPMENT, "", "", "", slot);
  }
}
