package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.Data;

@Data
@Embeddable
public class RoomGroundInventoryKey implements Serializable {
  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "game_instance_id")
  private String gameInstanceId;

  @Column(name = "room_instance_id")
  private String roomInstanceId;

  @Column(name = "item_id")
  private Long itemId;
}
