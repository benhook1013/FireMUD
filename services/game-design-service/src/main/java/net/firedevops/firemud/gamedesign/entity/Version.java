package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

@Data
public class Version {
  private Long id;
  private String tenantId;
  private int versionNumber;
  private VersionLifecycleState versionState = VersionLifecycleState.DRAFT;
  private Long versionStateEpoch = 1L;
  private String scriptPatchVersion;
  private Long baseVersionId;
  private boolean scriptOnly;
  private String notes;
  private LocalDateTime createdAt = LocalDateTime.now();
  private LocalDateTime updatedAt = LocalDateTime.now();
}
