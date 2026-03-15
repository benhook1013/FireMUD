package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "npc_formation_member")
public class NpcFormationMember {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "formation_id", nullable = false)
  private NpcFormation formation;

  @Column(name = "npc_id", nullable = false)
  private Long npcId;

  @Version
  @Column(name = "row_version")
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
