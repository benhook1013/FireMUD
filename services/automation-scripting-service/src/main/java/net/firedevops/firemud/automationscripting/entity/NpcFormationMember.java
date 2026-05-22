package net.firedevops.firemud.automationscripting.entity;

import lombok.Data;

@Data
public class NpcFormationMember {
  private Long id;
  private NpcFormation formation;
  private Long npcId;
  private int version;

  public NpcFormation getFormation() {
    if (formation == null) {
      return null;
    }
    NpcFormation copy = new NpcFormation();
    copy.setId(formation.getId());
    return copy;
  }

  public void setFormation(NpcFormation formation) {
    if (formation == null) {
      this.formation = null;
    } else {
      NpcFormation copy = new NpcFormation();
      copy.setId(formation.getId());
      this.formation = copy;
    }
  }
}
