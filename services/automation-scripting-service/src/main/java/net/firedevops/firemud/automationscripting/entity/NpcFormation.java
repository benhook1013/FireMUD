package net.firedevops.firemud.automationscripting.entity;

import lombok.Data;
import net.firedevops.firemud.automationscripting.model.FormationType;

@Data
public class NpcFormation {
  private Long id;
  private Long tenantId;
  private String name;
  private Long leaderNpcId;
  private FormationType formationType;
  private int version;
}
