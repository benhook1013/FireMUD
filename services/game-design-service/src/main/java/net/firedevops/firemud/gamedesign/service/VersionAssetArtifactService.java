package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetDeletionEligibilityDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetPurgeWorkflowStatusDto;

public interface VersionAssetArtifactService {
  VersionAssetArtifactStateDto getState(String tenantId, long versionId);

  VersionAssetArtifactStateDto markExportedUnattested(
      String tenantId,
      long versionId,
      int exportedVersionNumber,
      String workflowId,
      net.firedevops.firemud.gamedesign.service.ExportedAssetManifest exportedManifest);

  VersionAssetArtifactStateDto markPublished(
      String tenantId,
      long versionId,
      long expectedStateEpoch,
      String workflowId,
      String manifestHash);

  void markFailed(
      String tenantId,
      long versionId,
      int exportedVersionNumber,
      String workflowId,
      net.firedevops.firemud.gamedesign.service.ExportedAssetManifest exportedManifest,
      String errorCode,
      String errorMessage);

  VersionAssetArtifactStateDto tombstoneVersionAssets(
      String tenantId, long versionId, long expectedStateEpoch, String tombstoneWorkflowId);

  VersionAssetDeletionEligibilityDto canDeleteVersionAssets(String tenantId, long versionId);

  VersionAssetPurgeWorkflowStatusDto beginPurgeVersionAssets(
      String tenantId, long versionId, long expectedStateEpoch);

  VersionAssetPurgeWorkflowStatusDto finalizePurgeVersionAssets(
      String tenantId, long versionId, String purgeWorkflowId, long expectedStateEpoch);

  VersionAssetPurgeWorkflowStatusDto getPurgeStatus(
      String tenantId, long versionId, String purgeWorkflowId);

  VersionAssetArtifactStateDto repairPublishedVersionAssets(
      String tenantId, long versionId, long expectedStateEpoch, String repairWorkflowId);
}
