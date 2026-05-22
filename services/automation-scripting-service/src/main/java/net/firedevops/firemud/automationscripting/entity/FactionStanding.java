package net.firedevops.firemud.automationscripting.entity;

import lombok.Data;

@Data
public class FactionStanding {
  private Long id;
  private Long tenantId;
  private Long characterId;
  private String playableStateKey;
  private Faction faction;
  private int reputation = 0;
  private int version;

  public Faction getFaction() {
    if (faction == null) {
      return null;
    }
    Faction copy = new Faction();
    copy.setId(faction.getId());
    return copy;
  }

  public void setFaction(Faction faction) {
    if (faction == null) {
      this.faction = null;
    } else {
      Faction copy = new Faction();
      copy.setId(faction.getId());
      this.faction = copy;
    }
  }
}
