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
@Table(name = "world_design_revision_ledger")
public class WorldDesignRevisionLedger {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long versionId;

  @Column(nullable = false, length = 100)
  private String commitId;

  @Column(nullable = false, length = 100)
  private String revisionId;

  @Column(nullable = false, length = 50)
  private String operationType;

  @Column(nullable = false, length = 50)
  private String aggregateType;

  @Column(nullable = false, length = 100)
  private String requestedAggregateId = "";

  @Column(nullable = false)
  private Long appliedAggregateId;

  @Column(nullable = false, length = 50)
  private String result;

  @Column(nullable = false)
  private Long aggregateEpochAfter;

  private Long scopeEpochAfter;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
