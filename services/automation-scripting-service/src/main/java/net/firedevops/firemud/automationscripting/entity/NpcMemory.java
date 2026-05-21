package net.firedevops.firemud.automationscripting.entity;

import lombok.Data;

@Data
public class NpcMemory {
  private Long id;
  private Long npcId;
  private String key;
  private String value;
  private Long tenantId;
  private int version;
}
