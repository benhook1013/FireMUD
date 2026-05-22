package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;

@Data
public class EquipmentSlotDefinition {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private String slotKey;
  private String displayName;
  private String slotGroupKey;

  private int version;
}
