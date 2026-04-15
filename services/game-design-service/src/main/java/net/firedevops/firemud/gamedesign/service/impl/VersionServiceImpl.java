package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed")
public class VersionServiceImpl implements VersionService {
  private static final Logger logger = LoggingUtil.getLogger(VersionServiceImpl.class);

  private final VersionRepository versionRepository;
  private final GameRepository gameRepository;
  private final VersionMapper versionMapper;
  private final AutomationScriptingClient scriptingClient;
  private final AssetExportService assetExportService;
  private final PublishAttemptService publishAttemptService;
  private final PublishGateService publishGateService;
  private final ControlPlaneDigestService controlPlaneDigestService;
  private final VersionAssetArtifactService versionAssetArtifactService;
  private final PublishedReleaseBundleService publishedReleaseBundleService;

  @Autowired
  public VersionServiceImpl(
      VersionRepository versionRepository,
      GameRepository gameRepository,
      VersionMapper versionMapper,
      AutomationScriptingClient scriptingClient,
      AssetExportService assetExportService,
      PublishAttemptService publishAttemptService,
      PublishGateService publishGateService,
      ControlPlaneDigestService controlPlaneDigestService,
      VersionAssetArtifactService versionAssetArtifactService,
      PublishedReleaseBundleService publishedReleaseBundleService) {
    this.versionRepository = versionRepository;
    this.gameRepository = gameRepository;
    this.versionMapper = versionMapper;
    this.scriptingClient = scriptingClient;
    this.assetExportService = assetExportService;
    this.publishAttemptService = publishAttemptService;
    this.publishGateService = publishGateService;
    this.controlPlaneDigestService = controlPlaneDigestService;
    this.versionAssetArtifactService = versionAssetArtifactService;
    this.publishedReleaseBundleService = publishedReleaseBundleService;
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publish")
  public VersionDto publishVersion(String tenantId, String notes) {
    logger.info("Publishing version for tenant {}", tenantId);
    Game game =
        Optional.ofNullable(gameRepository.findByTenantIdForUpdate(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(tenantId));
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setUpdatedAt(LocalDateTime.now());
    Version saved = versionRepository.save(version);
    VersionDto dto = versionMapper.toDto(saved);
    String publishWorkflowId = UUID.randomUUID().toString();
    publishAttemptService.createAttempt(dto, PublishType.FULL_VERSION, publishWorkflowId);
    ExportedAssetManifest exportedManifest = null;
    Long exportedStateEpoch = null;
    try {
      List<PublishParticipantDigestDto> participantDigests =
          publishGateService.collectFullVersionParticipantDigests(dto);
      publishAttemptService.recordParticipantDigests(publishWorkflowId, participantDigests);
      publishGateService.assertGatePassed(dto, participantDigests);
      exportedManifest = assetExportService.exportAssets(tenantId, saved.getVersionNumber());
      exportedStateEpoch =
          versionAssetArtifactService
              .markExportedUnattested(dto.tenantId(), dto.id(), publishWorkflowId, exportedManifest)
              .stateEpoch();
      String generationConfigRevision = generationConfigRevision(dto, exportedManifest);
      publishedReleaseBundleService.createFullVersionBundle(
          dto, publishWorkflowId, exportedManifest, generationConfigRevision, participantDigests);
      saved.setVersionState(VersionLifecycleState.PUBLISHED);
      saved.setVersionStateEpoch(saved.getVersionStateEpoch() + 1L);
      saved.setUpdatedAt(LocalDateTime.now());
      saved = versionRepository.save(saved);
      versionAssetArtifactService.markPublished(
          dto.tenantId(),
          dto.id(),
          exportedStateEpoch,
          publishWorkflowId,
          exportedManifest.manifestHash());
      publishAttemptService.markSucceeded(publishWorkflowId);
      return versionMapper.toDto(saved);
    } catch (RuntimeException ex) {
      publishAttemptService.markFailed(publishWorkflowId, "PUBLISH_GATE_FAILED", ex.getMessage());
      if (exportedManifest != null) {
        versionAssetArtifactService.markFailed(
            dto.tenantId(),
            dto.id(),
            publishWorkflowId,
            exportedManifest,
            "PUBLISH_FAILED",
            ex.getMessage());
      }
      cleanupExportedAssets(tenantId, saved.getVersionNumber(), exportedManifest);
      versionRepository.delete(saved);
      throw ex;
    }
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publishScriptPatch")
  public VersionDto publishScriptPatchVersion(
      String tenantId, Long baseVersionId, String scriptPatchVersion, String notes) {
    logger.info(
        "Publishing script patch {} for tenant {} base {}",
        scriptPatchVersion,
        tenantId,
        baseVersionId);
    Game game =
        Optional.ofNullable(gameRepository.findByTenantIdForUpdate(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(tenantId));
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setScriptPatchVersion(scriptPatchVersion);
    version.setBaseVersionId(baseVersionId);
    version.setScriptOnly(true);
    version.setUpdatedAt(LocalDateTime.now());

    Version saved = versionRepository.save(version);
    VersionDto dto = versionMapper.toDto(saved);
    String publishWorkflowId = UUID.randomUUID().toString();
    publishAttemptService.createAttempt(dto, PublishType.SCRIPT_PATCH, publishWorkflowId);
    try {
      List<PublishParticipantDigestDto> participantDigests =
          publishGateService.collectScriptPatchParticipantDigests(dto);
      publishAttemptService.recordParticipantDigests(publishWorkflowId, participantDigests);
      publishGateService.assertGatePassed(dto, participantDigests);
      runSafely(
          "notify script patch version update",
          () ->
              scriptingClient.notifyScriptVersionUpdate(
                  String.valueOf(game.getTenantId()), scriptPatchVersion, List.of()));
      saved.setVersionState(VersionLifecycleState.PUBLISHED);
      saved.setVersionStateEpoch(saved.getVersionStateEpoch() + 1L);
      saved.setUpdatedAt(LocalDateTime.now());
      saved = versionRepository.save(saved);
      publishAttemptService.markSucceeded(publishWorkflowId);
      return versionMapper.toDto(saved);
    } catch (RuntimeException ex) {
      publishAttemptService.markFailed(publishWorkflowId, "PUBLISH_GATE_FAILED", ex.getMessage());
      versionRepository.delete(saved);
      throw ex;
    }
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "gamedesign.version.list")
  public List<VersionDto> listVersions(String tenantId) {
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    return versionRepository.findAllByTenantIdOrderByVersionNumberAsc(game.getTenantId()).stream()
        .map(versionMapper::toDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public DesignControlPlaneDigestDto getDesignControlPlaneDigest(String tenantId, Long versionId) {
    Version version = requireTenantVersion(tenantId, versionId);
    return controlPlaneDigestService.getDigestForVersion(versionMapper.toDto(version));
  }

  @Override
  @Transactional(readOnly = true)
  public DesignControlPlaneDigestDto getDesignControlPlaneDigestForScriptPatch(
      String tenantId, String scriptPatchVersion) {
    Version version =
        versionRepository
            .findTopByTenantIdAndScriptPatchVersionOrderByVersionNumberDesc(
                tenantId, scriptPatchVersion)
            .orElseThrow(() -> new IllegalArgumentException("script patch version not found"));
    return controlPlaneDigestService.getDigestForScriptPatch(versionMapper.toDto(version));
  }

  @Override
  @Transactional(readOnly = true)
  public PublishedReleaseBundleDto getPublishedReleaseBundle(String tenantId, long versionId) {
    return publishedReleaseBundleService.getPublishedReleaseBundle(tenantId, versionId);
  }

  @Override
  @Transactional(readOnly = true)
  public VersionStateDto getVersionState(String tenantId, long versionId) {
    return toVersionStateDto(requireTenantVersion(tenantId, versionId));
  }

  @Override
  @Transactional
  public VersionStateDto compareAndSetVersionState(
      String tenantId,
      long versionId,
      long expectedVersionStateEpoch,
      VersionLifecycleState newState,
      String reason) {
    Version version = requireTenantVersion(tenantId, versionId);
    if (newState == null) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: new version state is required");
    }
    if (!version.getVersionStateEpoch().equals(expectedVersionStateEpoch)) {
      throw new IllegalArgumentException(
          "VERSION_STATE_EPOCH_STALE: expected epoch "
              + expectedVersionStateEpoch
              + " does not match current epoch "
              + version.getVersionStateEpoch());
    }
    if (version.getVersionState() == newState) {
      return toVersionStateDto(version);
    }
    version.setVersionState(newState);
    version.setVersionStateEpoch(version.getVersionStateEpoch() + 1L);
    version.setUpdatedAt(LocalDateTime.now());
    Version saved = versionRepository.save(version);
    logger.info(
        "Updated version state tenant={} version={} state={} epoch={} reason={}",
        tenantId,
        versionId,
        newState,
        saved.getVersionStateEpoch(),
        reason == null ? "" : reason);
    return toVersionStateDto(saved);
  }

  private int calculateNextNumber(String tenantId) {
    return versionRepository
            .findTopByTenantIdOrderByVersionNumberDesc(tenantId)
            .map(Version::getVersionNumber)
            .orElse(0)
        + 1;
  }

  private Version requireTenantVersion(String tenantId, long versionId) {
    return versionRepository
        .findByTenantIdAndId(tenantId, versionId)
        .orElseThrow(() -> new IllegalArgumentException("version not found"));
  }

  private VersionStateDto toVersionStateDto(Version version) {
    return new VersionStateDto(
        version.getTenantId(),
        version.getId(),
        version.getVersionState(),
        version.getVersionStateEpoch(),
        version.getUpdatedAt());
  }

  private void cleanupExportedAssets(
      String tenantId, int versionNumber, ExportedAssetManifest exportedManifest) {
    try {
      if (exportedManifest != null) {
        assetExportService.deleteExportedAssets(
            tenantId, versionNumber, exportedManifest.requiredManifestAssetKeys());
      }
    } catch (RuntimeException ex) {
      logger.warn(
          "Failed to clean exported assets after publish failure tenant={} version={}",
          tenantId,
          versionNumber,
          ex);
    }
  }

  private void runSafely(String actionName, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ex) {
      logger.warn("Failed to {}", actionName, ex);
    }
  }

  private String generationConfigRevision(
      VersionDto version, ExportedAssetManifest exportedManifest) {
    return "genrev:"
        + version.tenantId()
        + ":"
        + version.id()
        + ":"
        + exportedManifest.manifestHash();
  }
}
