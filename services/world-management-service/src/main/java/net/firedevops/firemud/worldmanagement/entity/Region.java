package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "region")
public class Region {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  /**
   * Identifier for the world shard hosting this region. Shards allow the world to span multiple
   * servers while keeping regions isolated. A value of {@code 0} indicates the default shard.
   */
  @Column(name = "shard_id", nullable = false)
  private Integer shardId = 0;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 50)
  private String weather;

  /** Seed used for procedural generation of this region. */
  @Column(name = "generation_seed", nullable = false)
  private Long generationSeed = 0L;

  /** Generator type used to create this region. */
  @Column(name = "generator_type", length = 50)
  private String generatorType;

  /** Raw generator parameters serialized as JSON. */
  @Column(name = "generator_params")
  private String generatorParams;

  /** Multiplier applied to travel cost in sparse areas. */
  @Column(name = "spacing_multiplier", nullable = false)
  private Double spacingMultiplier = 1.0;

  @Version private int version;
}
