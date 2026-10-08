package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetDeletionEligibilityDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetPurgeWorkflowStatusDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.entity.VersionAssetPurgeWorkflow;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionAssetPurgeWorkflowStatus;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPurgeWorkflowRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators remain internal service dependencies")
public class VersionAssetArtifactServiceImpl implements VersionAssetArtifactService {
  private final VersionAssetArtifactRepository repository;
  private final VersionAssetPurgeWorkflowRepository purgeWorkflowRepository;
  private final VersionRepository versionRepository;
  private final PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  private final LaunchDescriptorRepository launchDescriptorRepository;
  private final VersionTemplateRemapSetRepository remapSetRepository;
  private final AssetExportService assetExportService;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final ObjectMapper objectMapper;

  public VersionAssetArtifactServiceImpl(
      VersionAssetArtifactRepository repository,
      VersionAssetPurgeWorkflowRepository purgeWorkflowRepository,
      VersionRepository versionRepository,
      PublishedReleaseBundleRepository publishedReleaseBundleRepository,
      LaunchDescriptorRepository launchDescriptorRepository,
      VersionTemplateRemapSetRepository remapSetRepository,
      AssetExportService assetExportService,
      PublishedReleaseBundleService publishedReleaseBundleService,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.purgeWorkflowRepository = purgeWorkflowRepository;
    this.versionRepository = versionRepository;
    this.publishedReleaseBundleRepository = publishedReleaseBundleRepository;
    this.launchDescriptorRepository = launchDescriptorRepository;
    this.remapSetRepository = remapSetRepository;
    this.assetExportService = assetExportService;
    this.publishedReleaseBundleService = publishedReleaseBundleService;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public VersionAssetArtifactStateDto getState(String tenantId, long versionId) {
    return findState(tenantId, versionId)
        .orElseThrow(() -> new IllegalArgumentException("version asset artifact state not found"));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<VersionAssetArtifactStateDto> findState(String tenantId, long versionId) {
    return repository.findByTenantIdAndVersionId(tenantId, versionId).map(this::toDto);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public VersionAssetArtifactStateDto stageExport(
      String tenantId, long versionId, int versionNumber, String workflowId) {
    if (tenantId == null
        || tenantId.isBlank()
        || versionId <= 0
        || versionNumber <= 0
        || workflowId == null
        || workflowId.isBlank()
        || workflowId.length() > 1024) {
      throw new IllegalArgumentException("Exact asset export scope and workflow are required");
    }
    Version version =
        versionRepository
            .findByTenantIdAndIdForUpdate(tenantId, versionId)
            .orElseThrow(() -> new IllegalArgumentException("version not found"));
    if (version.getVersionState() != VersionLifecycleState.DRAFT
        || !Objects.equals(version.getTenantId(), tenantId)
        || !Objects.equals(version.getId(), versionId)
        || version.getVersionNumber() != versionNumber
        || version.getVersionStateEpoch() == null
        || version.getVersionStateEpoch() <= 0
        || version.getCanonicalTenantId() == null
        || version.getCanonicalTenantId().equals(new UUID(0L, 0L))
        || version.getCanonicalVersionId() == null
        || version.getCanonicalVersionId().equals(new UUID(0L, 0L))
        || version.getIdentitySourceGameRowId() == null
        || version.getIdentitySourceGameRowId() <= 0
        || !Objects.equals(version.getIdentitySourceGameTenantKey(), tenantId)
        || version.getIdentitySourceProvenanceKind() == null
        || !List.of("NEW_GAME_ROW", "RETAINED_GAME_V29")
            .contains(version.getIdentitySourceProvenanceKind())) {
      throw new IllegalStateException("ASSET_EXPORT_VERSION_CONFLICT");
    }
    Optional<VersionAssetArtifact> stored =
        repository.findByTenantIdAndVersionIdForUpdate(tenantId, versionId);
    if (stored.isPresent()) {
      VersionAssetArtifact artifact = stored.orElseThrow();
      if (!Objects.equals(workflowId, artifact.getLastWorkflowId())
          || artifact.getExportedVersionNumber() != versionNumber
          || artifact.getStateEpoch() <= 0
          || (artifact.getArtifactState() != VersionAssetArtifactState.STAGED
              && artifact.getArtifactState() != VersionAssetArtifactState.EXPORTED_UNATTESTED)) {
        throw new IllegalStateException("ASSET_EXPORT_OPERATION_CONFLICT");
      }
      return toDto(artifact);
    }
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId(tenantId);
    artifact.setVersionId(versionId);
    artifact.setExportedVersionNumber(versionNumber);
    artifact.setArtifactState(VersionAssetArtifactState.STAGED);
    artifact.setStateEpoch(1L);
    artifact.setLastWorkflowId(workflowId);
    return toDto(repository.save(artifact));
  }

  @Override
  @Transactional
  public VersionAssetArtifactStateDto markExportedUnattested(
      String tenantId,
      long versionId,
      int exportedVersionNumber,
      String workflowId,
      ExportedAssetManifest exportedManifest) {
    VersionAssetArtifact artifact =
        repository
            .findByTenantIdAndVersionIdForUpdate(tenantId, versionId)
            .orElseThrow(() -> new IllegalStateException("ASSET_EXPORT_OPERATION_CONFLICT"));
    if (!Objects.equals(workflowId, artifact.getLastWorkflowId())
        || artifact.getExportedVersionNumber() != exportedVersionNumber
        || !Objects.equals(candidateOf(artifact), exportedManifest)) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_CONFLICT");
    }
    if (artifact.getArtifactState() == VersionAssetArtifactState.EXPORTED_UNATTESTED) {
      return toDto(artifact);
    }
    if (artifact.getArtifactState() != VersionAssetArtifactState.STAGED) {
      throw new IllegalStateException("ASSET_ARTIFACT_STATE_CONFLICT");
    }
    artifact.setArtifactState(VersionAssetArtifactState.EXPORTED_UNATTESTED);
    artifact.setExportedVersionNumber(exportedVersionNumber);
    artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
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
  @Transactional(readOnly = true)
  public ExportedAssetManifest getExportCandidate(String tenantId, long versionId) {
    if (tenantId == null || tenantId.isBlank() || versionId <= 0) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_UNAVAILABLE");
    }
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    if (!Objects.equals(tenantId, artifact.getTenantId())
        || !Objects.equals(versionId, artifact.getVersionId())) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_UNAVAILABLE");
    }
    if (isInitialStagedIntentWithoutCandidate(artifact)) {
      return null;
    }
    return candidateOf(artifact);
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
    artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
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
      int exportedVersionNumber,
      String workflowId,
      ExportedAssetManifest exportedManifest,
      String errorCode,
      String errorMessage) {
    VersionAssetArtifact artifact = findOrCreate(tenantId, versionId);
    artifact.setArtifactState(VersionAssetArtifactState.FAILED);
    artifact.setExportedVersionNumber(exportedVersionNumber);
    artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
    if (artifact.getManifestSchemaVersion() != null) {
      if (exportedManifest != null && !Objects.equals(candidateOf(artifact), exportedManifest)) {
        throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_CONFLICT");
      }
    } else {
      artifact.setManifestHash(exportedManifest == null ? null : exportedManifest.manifestHash());
    }
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
    artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
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
    if (artifact.getArtifactState() != VersionAssetArtifactState.TOMBSTONED
        && artifact.getArtifactState() != VersionAssetArtifactState.PURGE_FAILED) {
      return new VersionAssetDeletionEligibilityDto(
          artifact.getTenantId(),
          artifact.getVersionId(),
          false,
          artifact.getArtifactState().name(),
          artifact.getStateEpoch(),
          "VERSION_ASSET_NOT_DELETABLE",
          "version assets must be tombstoned before purge can begin");
    }
    var version =
        versionRepository.findById(versionId).filter(found -> found.getTenantId().equals(tenantId));
    boolean hasPublishedReleaseBundle =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(tenantId, versionId)
            .isPresent();
    if (artifact.getExportedVersionNumber() <= 0) {
      return new VersionAssetDeletionEligibilityDto(
          artifact.getTenantId(),
          artifact.getVersionId(),
          false,
          artifact.getArtifactState().name(),
          artifact.getStateEpoch(),
          "VERSION_ASSET_EXPORT_PROOF_MISSING",
          "version asset artifact proof is missing the exported version number");
    }
    if (launchDescriptorRepository.existsByTenantIdAndVersionId(tenantId, versionId)) {
      return new VersionAssetDeletionEligibilityDto(
          artifact.getTenantId(),
          artifact.getVersionId(),
          false,
          artifact.getArtifactState().name(),
          artifact.getStateEpoch(),
          "VERSION_ASSET_LAUNCH_REFERENCE_EXISTS",
          "version assets cannot be purged while launch descriptors still reference the version");
    }
    if (remapSetRepository.existsByTenantIdAndSourceVersionIdAndStatus(
            tenantId, versionId, TemplateRemapSetStatus.APPROVED)
        || remapSetRepository.existsByTenantIdAndTargetVersionIdAndStatus(
            tenantId, versionId, TemplateRemapSetStatus.APPROVED)) {
      return new VersionAssetDeletionEligibilityDto(
          artifact.getTenantId(),
          artifact.getVersionId(),
          false,
          artifact.getArtifactState().name(),
          artifact.getStateEpoch(),
          "VERSION_ASSET_TEMPLATE_REMAP_REFERENCE_EXISTS",
          "version assets cannot be purged while approved template remap sets still reference the version");
    }
    if (version.isPresent() && version.get().getVersionState() != VersionLifecycleState.RETIRED) {
      return new VersionAssetDeletionEligibilityDto(
          artifact.getTenantId(),
          artifact.getVersionId(),
          false,
          artifact.getArtifactState().name(),
          artifact.getStateEpoch(),
          "VERSION_STATE_NOT_RETIRED",
          "version assets cannot be purged until the version is retired");
    }
    if (version.isEmpty() && hasPublishedReleaseBundle) {
      return new VersionAssetDeletionEligibilityDto(
          artifact.getTenantId(),
          artifact.getVersionId(),
          false,
          artifact.getArtifactState().name(),
          artifact.getStateEpoch(),
          "PUBLISHED_RELEASE_BUNDLE_STILL_PRESENT",
          "version assets cannot be purged while an attested release bundle still exists without version state");
    }
    return new VersionAssetDeletionEligibilityDto(
        artifact.getTenantId(),
        artifact.getVersionId(),
        true,
        artifact.getArtifactState().name(),
        artifact.getStateEpoch(),
        null,
        null);
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
    artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
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
  @Transactional
  public VersionAssetPurgeWorkflowStatusDto finalizePurgeVersionAssets(
      String tenantId, long versionId, String purgeWorkflowId, long expectedStateEpoch) {
    VersionAssetArtifact artifact = requireArtifact(tenantId, versionId);
    requireEpoch(artifact, expectedStateEpoch);
    if (artifact.getArtifactState() != VersionAssetArtifactState.PURGE_IN_PROGRESS
        || !Objects.equals(artifact.getLastWorkflowId(), purgeWorkflowId)) {
      throw new IllegalStateException("PURGE_FINALIZATION_CONFLICT");
    }
    VersionAssetPurgeWorkflow workflow = requireWorkflow(tenantId, versionId, purgeWorkflowId);
    try {
      assetExportService.deleteExportedAssets(
          tenantId,
          artifact.getExportedVersionNumber(),
          deserializeKeys(artifact.getExportedManifestAssetKeysJson()));
      artifact.setArtifactState(VersionAssetArtifactState.PURGED);
      artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
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
      artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
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
    try {
      PublishedReleaseBundleContract.requireExactRepairMatch(bundle, exported);
    } catch (IllegalStateException ex) {
      artifact.setLastWorkflowId(repairWorkflowId);
      artifact.setLastErrorCode(ex.getMessage().split(":", 2)[0]);
      artifact.setLastErrorMessage(ex.getMessage().split(":", 2)[1].trim());
      artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
      artifact.setUpdatedAt(LocalDateTime.now());
      repository.save(artifact);
      throw new IllegalStateException(artifact.getLastErrorCode());
    }
    artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
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
              created.setExportedVersionNumber(0);
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
        artifact.getExportedVersionNumber(),
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

  private ExportedAssetManifest candidateOf(VersionAssetArtifact artifact) {
    if (artifact.getManifestSchemaVersion() == null
        || artifact.getArtifactDigestsJson() == null
        || artifact.getPublishedObjectProofsJson() == null
        || !Objects.equals(artifact.getCandidateSnapshotVersionId(), artifact.getVersionId())) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_UNAVAILABLE");
    }
    try {
      List<PublishedArtifactDigest> digests =
          objectMapper.readValue(
              artifact.getArtifactDigestsJson(),
              objectMapper
                  .getTypeFactory()
                  .constructCollectionType(List.class, PublishedArtifactDigest.class));
      return new ExportedAssetManifest(
          artifact.getManifestHash(),
          artifact.getManifestSchemaVersion(),
          deserializeKeys(artifact.getExportedManifestAssetKeysJson()),
          digests);
    } catch (Exception ex) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_UNAVAILABLE", ex);
    }
  }

  private boolean isInitialStagedIntentWithoutCandidate(VersionAssetArtifact artifact) {
    if (artifact.getArtifactState() != VersionAssetArtifactState.STAGED
        || artifact.getStateEpoch() <= 0
        || artifact.getLastWorkflowId() == null
        || artifact.getLastWorkflowId().isBlank()
        || artifact.getManifestHash() != null
        || artifact.getManifestSchemaVersion() != null
        || artifact.getArtifactDigestsJson() != null
        || artifact.getPublishedObjectProofsJson() != null
        || artifact.getCandidateSnapshotVersionId() != null) {
      return false;
    }
    String keysJson = artifact.getExportedManifestAssetKeysJson();
    if (keysJson == null || keysJson.isBlank()) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_UNAVAILABLE");
    }
    List<String> keys = deserializeKeys(keysJson);
    if (keys == null) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_UNAVAILABLE");
    }
    return keys.isEmpty();
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
