package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "npc_memory")
public class NpcMemory {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "npc_id", nullable = false)
  private Long npcId;

  @Column(nullable = false, length = 100)
  private String key;

  @Column(length = 255)
  private String value;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Version
  @Column(name = "row_version")
  private int version;
}
