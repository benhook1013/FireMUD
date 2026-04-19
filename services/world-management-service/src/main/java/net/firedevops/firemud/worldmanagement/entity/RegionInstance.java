package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "region_instance")
public class RegionInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "world_instance_id", nullable = false)
  private WorldInstance worldInstance;

  @Column(name = "shard_id", nullable = false)
  private Integer shardId = 0;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 50)
  private String weather;

  @Column(name = "generation_seed", nullable = false)
  private Long generationSeed = 0L;

  @Column(name = "generator_type", length = 50)
  private String generatorType;

  @Column(name = "generator_params", columnDefinition = "TEXT")
  private String generatorParams;

  @Column(name = "spacing_multiplier", nullable = false)
  private Double spacingMultiplier = 1.0;

  @Version private int version;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public WorldInstance getWorldInstance() {
    return worldInstance;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setWorldInstance(WorldInstance worldInstance) {
    this.worldInstance = worldInstance;
  }
}
