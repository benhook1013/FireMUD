package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;

@Data
@Entity
@Table(name = "version_asset_artifact")
public class VersionAssetArtifact {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private Long versionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private VersionAssetArtifactState artifactState;

  @Column(nullable = false)
  private long stateEpoch;

  @Column(length = 128)
  private String manifestHash;

  @Column(length = 64)
  private String lastWorkflowId;

  @Column(length = 64)
  private String lastErrorCode;

  @Column(length = 512)
  private String lastErrorMessage;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String exportedManifestAssetKeysJson = "[]";

  @Column(nullable = false)
  private LocalDateTime updatedAt = LocalDateTime.now();
}
