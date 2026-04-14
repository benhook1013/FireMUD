package net.firedevops.firemud.gamedesign.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetDeletionEligibilityDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetPurgeWorkflowStatusDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.entity.VersionAssetPurgeWorkflow;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionAssetPurgeWorkflowStatus;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPurgeWorkflowRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class VersionAssetArtifactServiceImpl implements VersionAssetArtifactService {
  private final VersionAssetArtifactRepository repository;
  private final VersionAssetPurgeWorkflowRepository purgeWorkflowRepository;
  private final VersionRepository versionRepository;
  private final AssetExportService assetExportService;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final ObjectMapper objectMapper;

  public VersionAssetArtifactServiceImpl(
      VersionAssetArtifactRepository repository,
      VersionAssetPurgeWorkflowRepository purgeWorkflowRepository,
      VersionRepository versionRepository,
      AssetExportService assetExportService,
      PublishedReleaseBundleService publishedReleaseBundleService,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.purgeWorkflowRepository = purgeWorkflowRepository;
    this.versionRepository = versionRepository;
    this.assetExportService = assetExportService;
    this.publishedReleaseBundleService = publishedReleaseBundleService;
    this.objectMapper = objectMapper;
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
      String tenantId, long versionId, String workflowId, ExportedAssetManifest exportedManifest) {
    VersionAssetArtifact artifact = findOrCreate(tenantId, versionId);
    artifact.setArtifactState(VersionAssetArtifactState.EXPORTED_UNATTESTED);
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setManifestHash(exportedManifest.manifestHash());
    artifact.setLastWorkflowId(workflowId);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setExportedManifestAssetKeysJson(
        serializeKeys(exportedManifest.requiredManifestAssetKeys()));
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
      ExportedAssetManifest exportedManifest,
      String errorCode,
      String errorMessage) {
    VersionAssetArtifact artifact = findOrCreate(tenantId, versionId);
    artifact.setArtifactState(VersionAssetArtifactState.FAILED);
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setManifestHash(exportedManifest == null ? null : exportedManifest.manifestHash());
    artifact.setLastWorkflowId(workflowId);
    artifact.setLastErrorCode(errorCode);
    artifact.setLastErrorMessage(errorMessage);
    if (exportedManifest != null) {
      artifact.setExportedManifestAssetKeysJson(
          serializeKeys(exportedManifest.requiredManifestAssetKeys()));
    }
    artifact.setUpdatedAt(LocalDateTime.now());
    repository.save(artifact);
  }

  @Override
  @Transactional
  public VersionAssetArtifactStateDto tombstoneVersionAssets(
      String tenantId, long versionId, long expectedStateEpoch, String tombstoneWorkflowId) {
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    requireEpoch(artifact, expectedStateEpoch);
    if (artifact.getArtifactState() != VersionAssetArtifactState.FAILED
        && artifact.getArtifactState() != VersionAssetArtifactState.PURGE_FAILED) {
      throw new IllegalStateException("VERSION_ASSET_NOT_DELETABLE");
    }
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setLastWorkflowId(tombstoneWorkflowId);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setUpdatedAt(LocalDateTime.now());
    return toDto(repository.save(artifact));
  }

  @Override
  @Transactional(readOnly = true)
  public VersionAssetDeletionEligibilityDto canDeleteVersionAssets(
      String tenantId, long versionId) {
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    boolean deletable =
        artifact.getArtifactState() == VersionAssetArtifactState.TOMBSTONED
            || artifact.getArtifactState() == VersionAssetArtifactState.PURGE_FAILED;
    return new VersionAssetDeletionEligibilityDto(
        artifact.getTenantId(),
        artifact.getVersionId(),
        deletable,
        artifact.getArtifactState().name(),
        artifact.getStateEpoch(),
        deletable ? null : "VERSION_ASSET_NOT_DELETABLE",
        deletable ? null : "version assets must be tombstoned before purge can begin");
  }

  @Override
  @Transactional
  public VersionAssetPurgeWorkflowStatusDto beginPurgeVersionAssets(
      String tenantId, long versionId, long expectedStateEpoch) {
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    requireEpoch(artifact, expectedStateEpoch);
    VersionAssetDeletionEligibilityDto eligibility = canDeleteVersionAssets(tenantId, versionId);
    if (!eligibility.deletable()) {
      throw new IllegalStateException(eligibility.failureCode());
    }
    String purgeWorkflowId = UUID.randomUUID().toString();
    artifact.setArtifactState(VersionAssetArtifactState.PURGE_IN_PROGRESS);
    artifact.setStateEpoch(artifact.getStateEpoch() + 1);
    artifact.setLastWorkflowId(purgeWorkflowId);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setUpdatedAt(LocalDateTime.now());
    repository.save(artifact);

    VersionAssetPurgeWorkflow workflow = new VersionAssetPurgeWorkflow();
    workflow.setTenantId(tenantId);
    workflow.setVersionId(versionId);
    workflow.setPurgeWorkflowId(purgeWorkflowId);
    workflow.setWorkflowStatus(VersionAssetPurgeWorkflowStatus.IN_PROGRESS);
    workflow.setStartedFromStateEpoch(expectedStateEpoch);
    workflow.setRequestedAt(LocalDateTime.now());
    workflow.setUpdatedAt(LocalDateTime.now());
    return toWorkflowDto(purgeWorkflowRepository.save(workflow));
  }

  @Override
  public VersionAssetPurgeWorkflowStatusDto finalizePurgeVersionAssets(
      String tenantId, long versionId, String purgeWorkflowId, long expectedStateEpoch) {
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    requireEpoch(artifact, expectedStateEpoch);
    if (artifact.getArtifactState() != VersionAssetArtifactState.PURGE_IN_PROGRESS
        || !Objects.equals(artifact.getLastWorkflowId(), purgeWorkflowId)) {
      throw new IllegalStateException("PURGE_FINALIZATION_CONFLICT");
    }
    VersionAssetPurgeWorkflow workflow = requireWorkflow(tenantId, versionId, purgeWorkflowId);
    Version version =
        versionRepository
            .findById(versionId)
            .filter(found -> found.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("version not found"));
    try {
      assetExportService.deleteExportedAssets(
          tenantId,
          version.getVersionNumber(),
          deserializeKeys(artifact.getExportedManifestAssetKeysJson()));
      artifact.setArtifactState(VersionAssetArtifactState.PURGED);
      artifact.setStateEpoch(artifact.getStateEpoch() + 1);
      artifact.setLastErrorCode(null);
      artifact.setLastErrorMessage(null);
      artifact.setUpdatedAt(LocalDateTime.now());
      repository.save(artifact);

      workflow.setWorkflowStatus(VersionAssetPurgeWorkflowStatus.SUCCEEDED);
      workflow.setLastErrorCode(null);
      workflow.setLastErrorMessage(null);
      workflow.setUpdatedAt(LocalDateTime.now());
      workflow.setCompletedAt(LocalDateTime.now());
      purgeWorkflowRepository.save(workflow);
      return toWorkflowDto(workflow);
    } catch (RuntimeException ex) {
      artifact.setArtifactState(VersionAssetArtifactState.PURGE_FAILED);
      artifact.setStateEpoch(artifact.getStateEpoch() + 1);
      artifact.setLastErrorCode("PURGE_FINALIZATION_CONFLICT");
      artifact.setLastErrorMessage(ex.getMessage());
      artifact.setUpdatedAt(LocalDateTime.now());
      repository.save(artifact);

      workflow.setWorkflowStatus(VersionAssetPurgeWorkflowStatus.FAILED);
      workflow.setLastErrorCode("PURGE_FINALIZATION_CONFLICT");
      workflow.setLastErrorMessage(ex.getMessage());
      workflow.setUpdatedAt(LocalDateTime.now());
      workflow.setCompletedAt(LocalDateTime.now());
      purgeWorkflowRepository.save(workflow);
      throw new IllegalStateException("PURGE_FINALIZATION_CONFLICT");
    }
  }

  @Override
  @Transactional(readOnly = true)
  public VersionAssetPurgeWorkflowStatusDto getPurgeStatus(
      String tenantId, long versionId, String purgeWorkflowId) {
    return toWorkflowDto(requireWorkflow(tenantId, versionId, purgeWorkflowId));
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

  private VersionAssetPurgeWorkflow requireWorkflow(
      String tenantId, long versionId, String purgeWorkflowId) {
    return purgeWorkflowRepository
        .findByTenantIdAndVersionIdAndPurgeWorkflowId(tenantId, versionId, purgeWorkflowId)
        .orElseThrow(() -> new IllegalArgumentException("PURGE_WORKFLOW_NOT_FOUND"));
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
        artifact.getUpdatedAt(),
        deserializeKeys(artifact.getExportedManifestAssetKeysJson()));
  }

  private VersionAssetPurgeWorkflowStatusDto toWorkflowDto(VersionAssetPurgeWorkflow workflow) {
    return new VersionAssetPurgeWorkflowStatusDto(
        workflow.getTenantId(),
        workflow.getVersionId(),
        workflow.getPurgeWorkflowId(),
        workflow.getWorkflowStatus().name(),
        workflow.getStartedFromStateEpoch(),
        workflow.getRequestedAt(),
        workflow.getUpdatedAt(),
        workflow.getCompletedAt(),
        workflow.getLastErrorCode(),
        workflow.getLastErrorMessage());
  }

  private String serializeKeys(List<String> keys) {
    try {
      return objectMapper.writeValueAsString(keys == null ? List.of() : List.copyOf(keys));
    } catch (Exception ex) {
      throw new IllegalStateException("failed to serialize exported manifest asset keys", ex);
    }
  }

  private List<String> deserializeKeys(String keysJson) {
    try {
      if (keysJson == null || keysJson.isBlank()) {
        return List.of();
      }
      return objectMapper.readValue(
          keysJson,
          objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception ex) {
      throw new IllegalStateException("failed to deserialize exported manifest asset keys", ex);
    }
  }
}
