package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class WorldEntitySpawnBinding {
  private Long id;
  private Long tenantId;
  private Long versionId;
  private Room room;
  private String entityTemplateType;
  private Long entityTemplateId;
  private int spawnCount = 1;
  private int respawnDelaySeconds;

  private int version;
}
