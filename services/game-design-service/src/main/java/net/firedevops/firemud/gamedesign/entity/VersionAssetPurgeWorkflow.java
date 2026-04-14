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
import net.firedevops.firemud.gamedesign.model.VersionAssetPurgeWorkflowStatus;

@Data
@Entity
@Table(name = "version_asset_purge_workflow")
public class VersionAssetPurgeWorkflow {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private Long versionId;

  @Column(nullable = false, length = 64, unique = true)
  private String purgeWorkflowId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private VersionAssetPurgeWorkflowStatus workflowStatus;

  @Column(nullable = false)
  private long startedFromStateEpoch;

  @Column(length = 64)
  private String lastErrorCode;

  @Column(length = 512)
  private String lastErrorMessage;

  @Column(nullable = false)
  private LocalDateTime requestedAt = LocalDateTime.now();

  @Column(nullable = false)
  private LocalDateTime updatedAt = LocalDateTime.now();

  private LocalDateTime completedAt;
}
