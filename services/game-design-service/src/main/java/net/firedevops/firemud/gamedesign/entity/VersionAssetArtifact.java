package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;

@Data
public class VersionAssetArtifact {
  private Long id;
  private String tenantId;
  private Long versionId;
  private int exportedVersionNumber;
  private VersionAssetArtifactState artifactState;
  private long stateEpoch;
  private String manifestHash;
  private String lastWorkflowId;
  private String lastErrorCode;
  private String lastErrorMessage;
  private String exportedManifestAssetKeysJson = "[]";
  private LocalDateTime updatedAt = LocalDateTime.now();
}
