package net.firedevops.firemud.entitymanagement.entity;

import java.io.Serializable;
import lombok.Data;

@Data
public class RoomGroundInventoryKey implements Serializable {
  private Long tenantId;
  private String gameInstanceId;
  private String roomInstanceId;
  private Long itemId;
}
