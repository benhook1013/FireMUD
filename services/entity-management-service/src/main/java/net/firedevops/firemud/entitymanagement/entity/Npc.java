package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "npcs")
public class Npc {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long versionId = 1L;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 100)
  private String behavior;

  @Column(name = "respawn_delay", nullable = false)
  private int respawnDelaySeconds = 60;

  @Column(name = "last_defeated_at")
  private Instant lastDefeatedAt;

  @Version private int version;
}
