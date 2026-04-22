package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

@Data
@Entity
@Table(name = "world_entity_spawn_binding")
public class WorldEntitySpawnBinding {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long versionId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @Column(name = "entity_template_type", nullable = false, length = 32)
  private String entityTemplateType;

  @Column(name = "entity_template_id", nullable = false)
  private Long entityTemplateId;

  @Column(name = "spawn_count", nullable = false)
  private int spawnCount = 1;

  @Column(name = "respawn_delay_seconds", nullable = false)
  private int respawnDelaySeconds;

  @Version private int version;
}
