package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PublishedReleaseBundle {
  private Long id;
  private String tenantId;
  private Long versionId;
  private int versionNumber;
  private String attestationSchemaVersion;
  private String publishWorkflowId;
  private String manifestHash;
  private String generationConfigRevision;
  private String requiredManifestAssetKeysJson;
  private String participantDigestsJson = "[]";
  private boolean scriptOnly;
  private String scriptPatchVersion;
  private LocalDateTime publishedAt = LocalDateTime.now();
}
