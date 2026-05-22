package net.firedevops.firemud.entitymanagement.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class Npc {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private String name;
  private String behavior;
  private int respawnDelaySeconds = 60;
  private Instant lastDefeatedAt;

  private int version;
}
