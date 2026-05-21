package net.firedevops.firemud.automationscripting.entity;

import lombok.Data;

@Data
public class Faction {
  private Long id;
  private Long tenantId;
  private String name;

  private String description;
  private int version;
}
