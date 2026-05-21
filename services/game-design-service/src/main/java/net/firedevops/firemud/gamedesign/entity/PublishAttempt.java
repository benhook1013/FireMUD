package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;

@Data
public class PublishAttempt {
  private Long id;
  private String tenantId;
  private String publishWorkflowId;
  private PublishType publishType;
  private PublishAttemptStatus status = PublishAttemptStatus.PENDING;

  private Long versionId;
  private int versionNumber;
  private String scriptPatchVersion;
  private String failureCode;
  private String failureMessage;
  private LocalDateTime createdAt = LocalDateTime.now();

  private LocalDateTime completedAt;
}
