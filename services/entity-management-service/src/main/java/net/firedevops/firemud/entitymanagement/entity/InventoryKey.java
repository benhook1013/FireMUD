package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.Data;

@Data
@Embeddable
public class InventoryKey implements Serializable {
  @Column(name = "character_id")
  private Long characterId;

  @Column(name = "item_id")
  private Long itemId;
}
