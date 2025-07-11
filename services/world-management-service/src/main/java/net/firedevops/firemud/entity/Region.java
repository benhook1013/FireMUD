package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "regions")
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
}
