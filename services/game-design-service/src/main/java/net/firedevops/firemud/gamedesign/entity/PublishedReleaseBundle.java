package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class PublishedReleaseBundle {
  private Long id;
  private String publishedReleaseBundleRef;
  private String tenantId;
  private Long versionId;
  private UUID canonicalTenantId;
  private UUID canonicalVersionId;
  private int versionNumber;
  private String attestationSchemaVersion;
  private String publishWorkflowId;
  private String manifestHash;
  private Integer manifestSchemaVersion;
  private String artifactDigestsJson;
  private String generationConfigRevision;
  private String requiredManifestAssetKeysJson;
  private String participantDigestsJson = "[]";
  private String commandDefinitionsJson = "[]";
  private String worldPublishedStartLocationEvidenceJson;
  private boolean scriptOnly;
  private String scriptPatchVersion;
  private LocalDateTime publishedAt = LocalDateTime.now();
}
