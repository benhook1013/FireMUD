package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@Entity
@Table(name = "room_ground_inventory")
public class RoomGroundInventoryEntry {
  @EmbeddedId private RoomGroundInventoryKey id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("itemId")
  private Item item;

  @Column(nullable = false)
  private int quantity;

  @Transient @EqualsAndHashCode.Exclude @ToString.Exclude private Long itemInstanceId;
  @Transient @EqualsAndHashCode.Exclude @ToString.Exclude private Long containerInstanceId;
  @Transient @EqualsAndHashCode.Exclude @ToString.Exclude private String visibleRef;

  @Version private int version;

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
    copy.setDefaultStackFamilyKey(item.getDefaultStackFamilyKey());
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
      copy.setDefaultStackFamilyKey(item.getDefaultStackFamilyKey());
      this.item = copy;
    }
  }
}
