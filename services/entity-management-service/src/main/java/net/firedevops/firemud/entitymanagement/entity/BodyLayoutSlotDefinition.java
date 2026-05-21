package net.firedevops.firemud.entitymanagement.entity;

import lombok.Data;

@Data
public class BodyLayoutSlotDefinition {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private String bodyLayoutKey;
  private String slotKey;

  private int version;
}
