package net.firedevops.firemud.gamesession.entity;

import lombok.Data;

@Data
public class FeatureFlag {
  private Long id;
  private Long tenantId;
  private String name;
  private boolean enabled;
}
