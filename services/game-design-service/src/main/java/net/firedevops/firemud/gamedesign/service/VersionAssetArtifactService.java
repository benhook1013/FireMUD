package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;

public interface VersionAssetArtifactService {
  VersionAssetArtifactStateDto getState(String tenantId, long versionId);

  VersionAssetArtifactStateDto markExportedUnattested(
      String tenantId, long versionId, String workflowId, String manifestHash);

  VersionAssetArtifactStateDto markPublished(
      String tenantId,
      long versionId,
      long expectedStateEpoch,
      String workflowId,
      String manifestHash);

  void markFailed(
      String tenantId,
      long versionId,
      String workflowId,
      String manifestHash,
      String errorCode,
      String errorMessage);

  VersionAssetArtifactStateDto repairPublishedVersionAssets(
      String tenantId, long versionId, long expectedStateEpoch, String repairWorkflowId);
}
