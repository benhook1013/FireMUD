package net.firedevops.firemud.worldmanagement.entity;

import java.util.UUID;
import lombok.Data;

@Data
public class WorldEntitySpawnBinding {
  private Long id;
  private Long tenantId;
  private Long versionId;
  private Room room;
  private String entityTemplateType;
  private Long entityTemplateId;
  private UUID entityCanonicalTenantId;
  private UUID entityCanonicalVersionId;
  private UUID entityCanonicalTemplateId;
  private int spawnCount = 1;
  private int respawnDelaySeconds;

  private int version;
}
