package net.firedevops.firemud.entity;

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
}
