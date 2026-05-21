package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.VersionAssetPurgeWorkflowStatus;

@Data
public class VersionAssetPurgeWorkflow {
  private Long id;
  private String tenantId;
  private Long versionId;
  private String purgeWorkflowId;
  private VersionAssetPurgeWorkflowStatus workflowStatus;
  private long startedFromStateEpoch;
  private String lastErrorCode;
  private String lastErrorMessage;
  private LocalDateTime requestedAt = LocalDateTime.now();
  private LocalDateTime updatedAt = LocalDateTime.now();

  private LocalDateTime completedAt;
}
