package net.firedevops.firemud.entitymanagement.entity;

import java.io.Serializable;
import lombok.Data;

@Data
public class InventoryKey implements Serializable {
  private Long characterId;
  private Long itemId;
}
