package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.Data;

@Data
@Embeddable
public class ContainerContentKey implements Serializable {
  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "character_id")
  private Long characterId;

  @Column(name = "container_item_id")
  private Long containerItemId;

  @Column(name = "item_id")
  private Long itemId;
}
