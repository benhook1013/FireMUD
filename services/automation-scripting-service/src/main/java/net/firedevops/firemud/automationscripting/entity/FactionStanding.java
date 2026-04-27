package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "faction_standing")
public class FactionStanding {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "character_id", nullable = false)
  private Long characterId;

  @Column(name = "playable_state_key", nullable = false, length = 120)
  private String playableStateKey;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "faction_id", nullable = false)
  private Faction faction;

  @Column(nullable = false)
  private int reputation = 0;

  @Version
  @Column(name = "row_version")
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
