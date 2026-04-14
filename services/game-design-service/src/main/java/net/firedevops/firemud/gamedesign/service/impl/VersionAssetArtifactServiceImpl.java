package net.firedevops.firemud.gamedesign.service.impl;

import java.time.LocalDateTime;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VersionAssetArtifactServiceImpl implements VersionAssetArtifactService {
  private final VersionAssetArtifactRepository repository;
  private final VersionRepository versionRepository;
  private final AssetExportService assetExportService;
  private final PublishedReleaseBundleService publishedReleaseBundleService;

  public VersionAssetArtifactServiceImpl(
      VersionAssetArtifactRepository repository,
      VersionRepository versionRepository,
      AssetExportService assetExportService,
      PublishedReleaseBundleService publishedReleaseBundleService) {
    this.repository = repository;
    this.versionRepository = versionRepository;
    this.assetExportService = assetExportService;
    this.publishedReleaseBundleService = publishedReleaseBundleService;
  }

  @Override
  @Transactional(readOnly = true)
  public VersionAssetArtifactStateDto getState(String tenantId, long versionId) {
    return repository
        .findByTenantIdAndVersionId(tenantId, versionId)
        .map(this::toDto)
        .orElseThrow(() -> new IllegalArgumentException("version asset artifact state not found"));
  }

  @Override
  @Transactional
  public VersionAssetArtifactStateDto markExportedUnattested(
      String tenantId, long versionId, String workflowId, String manifestHash) {
    VersionAssetArtifact artifact = findOrCreate(tenantId, versionId);
    artifact.setArtifactState(VersionAssetArtifactState.EXPORTED_UNATTESTED);
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setManifestHash(manifestHash);
    artifact.setLastWorkflowId(workflowId);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setUpdatedAt(LocalDateTime.now());
    return toDto(repository.save(artifact));
  }

  @Override
  @Transactional
  public VersionAssetArtifactStateDto markPublished(
      String tenantId,
      long versionId,
      long expectedStateEpoch,
      String workflowId,
      String manifestHash) {
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    requireEpoch(artifact, expectedStateEpoch);
    if (artifact.getArtifactState() != VersionAssetArtifactState.EXPORTED_UNATTESTED) {
      throw new IllegalStateException("ASSET_ARTIFACT_STATE_CONFLICT");
    }
    if (!Objects.equals(manifestHash, artifact.getManifestHash())) {
      throw new IllegalStateException("REPAIR_ATTESTATION_MISMATCH");
    }
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setLastWorkflowId(workflowId);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setUpdatedAt(LocalDateTime.now());
    return toDto(repository.save(artifact));
  }

  @Override
  @Transactional
  public void markFailed(
      String tenantId,
      long versionId,
      String workflowId,
      String manifestHash,
      String errorCode,
      String errorMessage) {
    VersionAssetArtifact artifact = findOrCreate(tenantId, versionId);
    artifact.setArtifactState(VersionAssetArtifactState.FAILED);
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setManifestHash(manifestHash);
    artifact.setLastWorkflowId(workflowId);
    artifact.setLastErrorCode(errorCode);
    artifact.setLastErrorMessage(errorMessage);
    artifact.setUpdatedAt(LocalDateTime.now());
    repository.save(artifact);
  }

  @Override
  @Transactional
  public VersionAssetArtifactStateDto repairPublishedVersionAssets(
      String tenantId, long versionId, long expectedStateEpoch, String repairWorkflowId) {
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    requireEpoch(artifact, expectedStateEpoch);
    if (artifact.getArtifactState() != VersionAssetArtifactState.PUBLISHED) {
      throw new IllegalArgumentException("VERSION_ASSET_NOT_REPAIRABLE");
    }
    var bundle = publishedReleaseBundleService.getPublishedReleaseBundle(tenantId, versionId);
    Version version =
        versionRepository
            .findById(versionId)
            .filter(found -> found.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("version not found"));
    var exported = assetExportService.exportAssets(tenantId, version.getVersionNumber());
    if (!Objects.equals(bundle.manifestHash(), exported.manifestHash())) {
      artifact.setLastWorkflowId(repairWorkflowId);
      artifact.setLastErrorCode("REPAIR_ATTESTATION_MISMATCH");
      artifact.setLastErrorMessage("repair could not reproduce the attested manifest hash");
      artifact.setUpdatedAt(LocalDateTime.now());
      repository.save(artifact);
      throw new IllegalStateException("REPAIR_ATTESTATION_MISMATCH");
    }
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setManifestHash(exported.manifestHash());
    artifact.setLastWorkflowId(repairWorkflowId);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setUpdatedAt(LocalDateTime.now());
    return toDto(repository.save(artifact));
  }

  private VersionAssetArtifact requireArtifact(String tenantId, long versionId) {
    return repository
        .findByTenantIdAndVersionId(tenantId, versionId)
        .orElseThrow(() -> new IllegalArgumentException("version asset artifact state not found"));
  }

  private VersionAssetArtifact findOrCreate(String tenantId, long versionId) {
    return repository
        .findByTenantIdAndVersionId(tenantId, versionId)
        .orElseGet(
            () -> {
              VersionAssetArtifact created = new VersionAssetArtifact();
              created.setTenantId(tenantId);
              created.setVersionId(versionId);
              created.setArtifactState(VersionAssetArtifactState.STAGED);
              created.setStateEpoch(0);
              return created;
            });
  }

  private void requireEpoch(VersionAssetArtifact artifact, long expectedStateEpoch) {
    if (artifact.getStateEpoch() != expectedStateEpoch) {
      throw new IllegalStateException("ASSET_ARTIFACT_STATE_CONFLICT");
    }
  }

  private VersionAssetArtifactStateDto toDto(VersionAssetArtifact artifact) {
    return new VersionAssetArtifactStateDto(
        artifact.getTenantId(),
        artifact.getVersionId(),
        artifact.getArtifactState().name(),
        artifact.getStateEpoch(),
        artifact.getManifestHash(),
        artifact.getLastWorkflowId(),
        artifact.getLastErrorCode(),
        artifact.getLastErrorMessage(),
        artifact.getUpdatedAt());
  }
}
