package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class Region {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;

  /**
   * Identifier for the world shard hosting this region. Shards allow the world to span multiple
   * servers while keeping regions isolated. A value of {@code 0} indicates the default shard.
   */
  private Integer shardId = 0;

  private String name;
  private String weather;

  /** Seed used for procedural generation of this region. */
  private Long generationSeed = 0L;

  /** Generator type used to create this region. */
  private String generatorType;

  /** Raw generator parameters serialized as JSON. */
  private String generatorParams;

  /** Multiplier applied to travel cost in sparse areas. */
  private Double spacingMultiplier = 1.0;

  private int version;
}
