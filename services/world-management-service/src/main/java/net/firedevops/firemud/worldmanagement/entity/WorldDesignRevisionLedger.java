package net.firedevops.firemud.worldmanagement.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorldDesignRevisionLedger {
  private Long id;
  private Long tenantId;
  private Long versionId;
  private String commitId;
  private String revisionId;
  private String operationType;
  private String aggregateType;
  private String requestedAggregateId = "";
  private Long appliedAggregateId;
  private String result;
  private Long aggregateEpochAfter;

  private Long scopeEpochAfter;
  private LocalDateTime createdAt = LocalDateTime.now();
}
