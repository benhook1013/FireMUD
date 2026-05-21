package net.firedevops.firemud.automationscripting.entity;

import lombok.Data;

@Data
public class ScriptDefinition {
  private Long id;
  private Long tenantId;
  private String name;
  private String scriptVersion;
  private String definition;
  private int rowVersion;
}
