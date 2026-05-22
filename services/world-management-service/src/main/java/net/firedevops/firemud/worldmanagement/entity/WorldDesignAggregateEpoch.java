package net.firedevops.firemud.worldmanagement.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorldDesignAggregateEpoch {
  private Long id;
  private Long tenantId;
  private Long versionId;
  private String aggregateType;
  private Long aggregateId;
  private Long draftRevisionEpoch = 0L;
  private LocalDateTime updatedAt = LocalDateTime.now();
}
