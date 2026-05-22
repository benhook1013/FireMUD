package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Revision {
  private Long id;
  private String tenantId;
  private Long versionId;
  private Long authorAccountId;
  private String data;
  private String revisionKind;
  private String logicalRevisionId;
  private LocalDateTime createdAt = LocalDateTime.now();
}
