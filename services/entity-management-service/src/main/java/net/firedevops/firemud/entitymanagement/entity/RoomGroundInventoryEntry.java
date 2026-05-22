package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
public class RoomGroundInventoryEntry {
  private RoomGroundInventoryKey id;
  private Item item;
  private int quantity;

  @EqualsAndHashCode.Exclude @ToString.Exclude private Long itemInstanceId;
  @EqualsAndHashCode.Exclude @ToString.Exclude private Long containerInstanceId;
  @EqualsAndHashCode.Exclude @ToString.Exclude private String visibleRef;

  private int version;

  public RoomGroundInventoryKey getId() {
    if (id == null) {
      return null;
    }
    RoomGroundInventoryKey copy = new RoomGroundInventoryKey();
    copy.setTenantId(id.getTenantId());
    copy.setGameInstanceId(id.getGameInstanceId());
    copy.setRoomInstanceId(id.getRoomInstanceId());
    copy.setItemId(id.getItemId());
    return copy;
  }

  public void setId(RoomGroundInventoryKey id) {
    if (id == null) {
      this.id = null;
    } else {
      RoomGroundInventoryKey copy = new RoomGroundInventoryKey();
      copy.setTenantId(id.getTenantId());
      copy.setGameInstanceId(id.getGameInstanceId());
      copy.setRoomInstanceId(id.getRoomInstanceId());
      copy.setItemId(id.getItemId());
      this.id = copy;
    }
  }

  public Item getItem() {
    if (item == null) {
      return null;
    }
    Item copy = new Item();
    copy.setId(item.getId());
    copy.setTenantId(item.getTenantId());
    copy.setName(item.getName());
    copy.setDescription(item.getDescription());
    copy.setEquipmentSlot(item.getEquipmentSlot());
    copy.setContainer(item.isContainer());
    copy.setStackable(item.isStackable());
    copy.setStackCompatibilityMode(item.getStackCompatibilityMode());
    copy.setStackVariantKey(item.getStackVariantKey());
    copy.setEffectPayloadJson(item.getEffectPayloadJson());
    return copy;
  }

  public void setItem(Item item) {
    if (item == null) {
      this.item = null;
    } else {
      Item copy = new Item();
      copy.setId(item.getId());
      copy.setTenantId(item.getTenantId());
      copy.setName(item.getName());
      copy.setDescription(item.getDescription());
      copy.setEquipmentSlot(item.getEquipmentSlot());
      copy.setContainer(item.isContainer());
      copy.setStackable(item.isStackable());
      copy.setStackCompatibilityMode(item.getStackCompatibilityMode());
      copy.setStackVariantKey(item.getStackVariantKey());
      copy.setEffectPayloadJson(item.getEffectPayloadJson());
      this.item = copy;
    }
  }
}
