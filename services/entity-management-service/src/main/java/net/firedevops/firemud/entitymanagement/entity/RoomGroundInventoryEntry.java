package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

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
    return item;
  }

  public void setItem(Item item) {
    this.item = item;
  }
}
