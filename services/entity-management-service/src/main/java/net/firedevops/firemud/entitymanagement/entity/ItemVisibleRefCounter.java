package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;

@Data
public class ItemVisibleRefCounter {
  private Long id;
  private Long tenantId;
  private String visibleRefToken;
  private Long nextSequence;

  private int version;
}
