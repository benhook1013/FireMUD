package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class GenerationRule {
  private Long id;
  private Long tenantId;
  private Long versionId;
  private String name;
  private String scopeType;
  private String scopeId;
  private String value;

  private int version;
}
