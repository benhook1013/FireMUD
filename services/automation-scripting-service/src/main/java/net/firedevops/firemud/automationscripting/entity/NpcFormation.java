package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.*;
import lombok.Data;
import net.firedevops.firemud.automationscripting.model.FormationType;

@Data
@Entity
@Table(name = "npc_formations")
public class NpcFormation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(name = "leader_npc_id", nullable = false)
  private Long leaderNpcId;

  @Enumerated(EnumType.STRING)
  @Column(name = "formation_type", nullable = false)
  private FormationType formationType;

  @Version
  @Column(name = "row_version")
  private int version;
}
