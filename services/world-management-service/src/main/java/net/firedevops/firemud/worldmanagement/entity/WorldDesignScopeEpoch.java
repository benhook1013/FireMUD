package net.firedevops.firemud.worldmanagement.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorldDesignScopeEpoch {
  private Long id;
  private Long tenantId;
  private Long versionId;
  private String scopeType;
  private String scopeId;
  private Long draftScopeRevisionEpoch = 0L;
  private LocalDateTime updatedAt = LocalDateTime.now();
}
