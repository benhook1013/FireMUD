package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "world_design_aggregate_epoch")
public class WorldDesignAggregateEpoch {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long versionId;

  @Column(nullable = false, length = 50)
  private String aggregateType;

  @Column(nullable = false)
  private Long aggregateId;

  @Column(nullable = false)
  private Long draftRevisionEpoch = 0L;

  @Column(nullable = false)
  private LocalDateTime updatedAt = LocalDateTime.now();
}
