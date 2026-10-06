package net.firedevops.firemud.gamedesign.entity;

import java.util.UUID;
import lombok.Data;

@Data
public class Game {
  private Long id;
  private String tenantId;
  private UUID canonicalTenantId;
  private String name;
  private String description;
}
