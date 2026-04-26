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
import net.firedevops.firemud.gamedesign.dto.PublishedPluginVersionDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedPluginVersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.RecordedParticipantDigestService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed")
public class VersionServiceImpl implements VersionService {
  private static final int DEFAULT_PLUGIN_VERSION_STATUS_LIMIT = 100;
  private static final int MAX_PLUGIN_VERSION_STATUS_LIMIT = 200;

  private static final Logger logger = LoggingUtil.getLogger(VersionServiceImpl.class);

  private final VersionRepository versionRepository;
  private final GameRepository gameRepository;
  private final PublishedPluginVersionRepository publishedPluginVersionRepository;
  private final VersionMapper versionMapper;
  private final AutomationScriptingClient scriptingClient;
  private final AssetExportService assetExportService;
  private final PublishAttemptService publishAttemptService;
  private final PublishGateService publishGateService;
  private final ControlPlaneDigestService controlPlaneDigestService;
  private final VersionAssetArtifactService versionAssetArtifactService;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final RecordedParticipantDigestService recordedParticipantDigestService;

  @Autowired
  public VersionServiceImpl(
      VersionRepository versionRepository,
      GameRepository gameRepository,
      PublishedPluginVersionRepository publishedPluginVersionRepository,
      VersionMapper versionMapper,
      AutomationScriptingClient scriptingClient,
      AssetExportService assetExportService,
      PublishAttemptService publishAttemptService,
      PublishGateService publishGateService,
      ControlPlaneDigestService controlPlaneDigestService,
      VersionAssetArtifactService versionAssetArtifactService,
      PublishedReleaseBundleService publishedReleaseBundleService,
      RecordedParticipantDigestService recordedParticipantDigestService) {
    this.versionRepository = versionRepository;
    this.gameRepository = gameRepository;
    this.publishedPluginVersionRepository = publishedPluginVersionRepository;
    this.versionMapper = versionMapper;
    this.scriptingClient = scriptingClient;
    this.assetExportService = assetExportService;
    this.publishAttemptService = publishAttemptService;
    this.publishGateService = publishGateService;
    this.controlPlaneDigestService = controlPlaneDigestService;
    this.versionAssetArtifactService = versionAssetArtifactService;
    this.publishedReleaseBundleService = publishedReleaseBundleService;
    this.recordedParticipantDigestService = recordedParticipantDigestService;
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
      recordedParticipantDigestService.assertMatchesRecordedDigests(
          dto.tenantId(), PublishType.FULL_VERSION, participantDigests);
      exportedManifest = assetExportService.exportAssets(tenantId, saved.getVersionNumber());
      exportedStateEpoch =
          versionAssetArtifactService
              .markExportedUnattested(
                  dto.tenantId(),
                  dto.id(),
                  saved.getVersionNumber(),
                  publishWorkflowId,
                  exportedManifest)
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
      recordedParticipantDigestService.recordVerifiedDigests(
          dto.tenantId(), PublishType.FULL_VERSION, publishWorkflowId, participantDigests);
      publishAttemptService.markSucceeded(publishWorkflowId);
      return versionMapper.toDto(saved);
    } catch (RuntimeException ex) {
      publishAttemptService.markFailed(
          publishWorkflowId, publishFailureCode(ex), publishFailureMessage(ex));
      if (exportedManifest != null) {
        versionAssetArtifactService.markFailed(
            dto.tenantId(),
            dto.id(),
            saved.getVersionNumber(),
            publishWorkflowId,
            exportedManifest,
            publishFailureCode(ex),
            publishFailureMessage(ex));
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
      recordedParticipantDigestService.assertMatchesRecordedDigests(
          dto.tenantId(), PublishType.SCRIPT_PATCH, participantDigests);
      runSafely(
          "notify script patch version update",
          () ->
              scriptingClient.notifyScriptVersionUpdate(
                  String.valueOf(game.getTenantId()), scriptPatchVersion, List.of()));
      saved.setVersionState(VersionLifecycleState.PUBLISHED);
      saved.setVersionStateEpoch(saved.getVersionStateEpoch() + 1L);
      saved.setUpdatedAt(LocalDateTime.now());
      saved = versionRepository.save(saved);
      recordedParticipantDigestService.recordVerifiedDigests(
          dto.tenantId(), PublishType.SCRIPT_PATCH, publishWorkflowId, participantDigests);
      publishAttemptService.markSucceeded(publishWorkflowId);
      return versionMapper.toDto(saved);
    } catch (RuntimeException ex) {
      publishAttemptService.markFailed(
          publishWorkflowId, publishFailureCode(ex), publishFailureMessage(ex));
      versionRepository.delete(saved);
      throw ex;
    }
  }

  @Override
  @Transactional(readOnly = true)
  public VersionDto getPublishedScriptPatchVersion(String tenantId, String scriptPatchVersion) {
    if (scriptPatchVersion == null || scriptPatchVersion.isBlank()) {
      throw new IllegalArgumentException("script patch version is required");
    }
    return versionMapper.toDto(
        versionRepository
            .findTopByTenantIdAndScriptPatchVersionOrderByVersionNumberDesc(
                tenantId, scriptPatchVersion)
            .filter(Version::isScriptOnly)
            .orElseThrow(() -> new IllegalArgumentException("script patch version not found")));
  }

  @Override
  @Transactional
  public PublishedPluginVersionDto publishPluginVersion(
      String tenantId,
      String pluginId,
      String pluginVersionId,
      long baseVersionId,
      String abilitySchemaDigest,
      String bundleDigest,
      int manifestSchemaVersion,
      String distributionManifestHash,
      String distributionManifestPath,
      String signerKeyId,
      boolean signerRevoked,
      String componentPolicyDecision,
      String notes) {
    requireText(pluginId, "pluginId");
    requireText(pluginVersionId, "pluginVersionId");
    requireText(abilitySchemaDigest, "abilitySchemaDigest");
    requireText(bundleDigest, "bundleDigest");
    requireText(signerKeyId, "signerKeyId");
    requireComponentPolicyDecision(componentPolicyDecision);
    if (baseVersionId <= 0L) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: baseVersionId must be positive");
    }
    if (manifestSchemaVersion <= 0) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: manifestSchemaVersion must be positive");
    }

    Optional<PublishedPluginVersion> existing =
        publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            tenantId, pluginId, pluginVersionId);
    if (existing.isPresent()) {
      PublishedPluginVersion entity = existing.get();
      if (!samePublication(
          entity,
          baseVersionId,
          abilitySchemaDigest,
          bundleDigest,
          manifestSchemaVersion,
          distributionManifestHash,
          distributionManifestPath,
          signerKeyId,
          signerRevoked,
          componentPolicyDecision,
          notes)) {
        throw new IllegalArgumentException(
            "PLUGIN_VERSION_IMMUTABLE: plugin version already exists with different metadata");
      }
      return toPublishedPluginVersionDto(entity);
    }

    requireTenantVersion(tenantId, baseVersionId);
    requireDistributionManifestFields(distributionManifestHash, distributionManifestPath);
    if (signerRevoked) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: revoked signer metadata cannot be published");
    }
    if ("BLOCKED".equals(componentPolicyDecision)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: blocked component policy cannot be published");
    }
    PublishedReleaseBundleDto baseBundle =
        publishedReleaseBundleService.getPublishedReleaseBundle(tenantId, baseVersionId);
    PublishedReleaseBundleContract.requireSupportedSchemaForRead(baseBundle);
    String expectedAbilitySchemaDigest = requiredAutomationAbilitySchemaDigest(baseBundle);
    if (!expectedAbilitySchemaDigest.equals(abilitySchemaDigest)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: abilitySchemaDigest does not match published release bundle");
    }

    PublishedPluginVersion entity = new PublishedPluginVersion();
    entity.setTenantId(tenantId);
    entity.setPluginId(pluginId);
    entity.setPluginVersionId(pluginVersionId);
    entity.setBaseVersionId(baseVersionId);
    entity.setPublicationState(VersionLifecycleState.PUBLISHED);
    entity.setAbilitySchemaDigest(abilitySchemaDigest);
    entity.setBundleDigest(bundleDigest);
    entity.setManifestSchemaVersion(manifestSchemaVersion);
    entity.setDistributionManifestHash(normalizeBlank(distributionManifestHash));
    entity.setDistributionManifestPath(normalizeBlank(distributionManifestPath));
    entity.setSignerKeyId(signerKeyId);
    entity.setSignerRevoked(signerRevoked);
    entity.setComponentPolicyDecision(componentPolicyDecision);
    entity.setNotes(normalizeBlank(notes));
    entity.setLastChangedAt(LocalDateTime.now());
    return toPublishedPluginVersionDto(publishedPluginVersionRepository.save(entity));
  }

  @Override
  @Transactional(readOnly = true)
  public PublishedPluginVersionDto getPublishedPluginVersion(
      String tenantId, String pluginId, String pluginVersionId) {
    requireText(pluginId, "pluginId");
    requireText(pluginVersionId, "pluginVersionId");
    return publishedPluginVersionRepository
        .findByTenantIdAndPluginIdAndPluginVersionId(tenantId, pluginId, pluginVersionId)
        .map(this::toPublishedPluginVersionDto)
        .orElseThrow(() -> new IllegalArgumentException("NOT_FOUND: plugin version not found"));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PublishedPluginVersionDto> listPublishedPluginVersions(
      String tenantId,
      String pluginId,
      VersionLifecycleState publicationState,
      LocalDateTime changedAfter,
      LocalDateTime changedBefore,
      int limit) {
    if (changedAfter != null && changedBefore != null && changedAfter.isAfter(changedBefore)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: changedAfter must be before or equal to changedBefore");
    }
    return publishedPluginVersionRepository
        .listPublishedPluginVersions(
            tenantId,
            normalizeBlank(pluginId),
            publicationState,
            changedAfter,
            changedBefore,
            PageRequest.of(0, sanitizePluginVersionStatusLimit(limit)))
        .stream()
        .map(this::toPublishedPluginVersionDto)
        .toList();
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

  private String publishFailureCode(RuntimeException ex) {
    if (ex instanceof PublishGateFailureException publishGateFailureException) {
      return publishGateFailureException.failureCode().name();
    }
    return "PUBLISH_FAILED";
  }

  private String publishFailureMessage(RuntimeException ex) {
    return ex.getMessage() == null ? publishFailureCode(ex) : ex.getMessage();
  }

  private Version requireTenantVersion(String tenantId, long versionId) {
    return versionRepository
        .findByTenantIdAndId(tenantId, versionId)
        .orElseThrow(() -> new IllegalArgumentException("version not found"));
  }

  private boolean samePublication(
      PublishedPluginVersion entity,
      long baseVersionId,
      String abilitySchemaDigest,
      String bundleDigest,
      int manifestSchemaVersion,
      String distributionManifestHash,
      String distributionManifestPath,
      String signerKeyId,
      boolean signerRevoked,
      String componentPolicyDecision,
      String notes) {
    return entity.getBaseVersionId() == baseVersionId
        && entity.getPublicationState() == VersionLifecycleState.PUBLISHED
        && entity.getAbilitySchemaDigest().equals(abilitySchemaDigest)
        && entity.getBundleDigest().equals(bundleDigest)
        && entity.getManifestSchemaVersion() == manifestSchemaVersion
        && normalizeBlank(entity.getDistributionManifestHash())
            .equals(normalizeBlank(distributionManifestHash))
        && normalizeBlank(entity.getDistributionManifestPath())
            .equals(normalizeBlank(distributionManifestPath))
        && entity.getSignerKeyId().equals(signerKeyId)
        && entity.isSignerRevoked() == signerRevoked
        && entity.getComponentPolicyDecision().equals(componentPolicyDecision)
        && normalizeBlank(entity.getNotes()).equals(normalizeBlank(notes));
  }

  private PublishedPluginVersionDto toPublishedPluginVersionDto(PublishedPluginVersion entity) {
    return new PublishedPluginVersionDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getPluginId(),
        entity.getPluginVersionId(),
        entity.getBaseVersionId(),
        entity.getPublicationState(),
        entity.getAbilitySchemaDigest(),
        entity.getBundleDigest(),
        entity.getManifestSchemaVersion(),
        normalizeBlank(entity.getDistributionManifestHash()),
        normalizeBlank(entity.getDistributionManifestPath()),
        entity.getSignerKeyId(),
        entity.isSignerRevoked(),
        entity.getComponentPolicyDecision(),
        normalizeBlank(entity.getNotes()),
        entity.getLastChangedAt());
  }

  private void requireComponentPolicyDecision(String componentPolicyDecision) {
    requireText(componentPolicyDecision, "componentPolicyDecision");
    if (!List.of("ALLOWED", "REPORT_ONLY", "BLOCKED").contains(componentPolicyDecision)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: componentPolicyDecision must be ALLOWED, REPORT_ONLY, or BLOCKED");
    }
  }

  private void requireDistributionManifestFields(
      String distributionManifestHash, String distributionManifestPath) {
    boolean hasHash = !normalizeBlank(distributionManifestHash).isBlank();
    boolean hasPath = !normalizeBlank(distributionManifestPath).isBlank();
    if (hasHash != hasPath) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: distributionManifestHash and distributionManifestPath must both be set or both be empty");
    }
  }

  private String requiredAutomationAbilitySchemaDigest(PublishedReleaseBundleDto bundle) {
    return bundle.participantDigests().stream()
        .filter(
            digest ->
                PublishParticipantKey.AUTOMATION_SCRIPTING.name().equals(digest.participantKey()))
        .map(PublishParticipantDigestDto::contentDigest)
        .filter(digest -> digest != null && !digest.isBlank())
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "INVALID_ARGUMENT: published release bundle is missing the Automation ability-schema digest"));
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

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: " + fieldName + " is required");
    }
  }

  private static String normalizeBlank(String value) {
    return value == null ? "" : value;
  }

  private static int sanitizePluginVersionStatusLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_PLUGIN_VERSION_STATUS_LIMIT;
    }
    return Math.min(limit, MAX_PLUGIN_VERSION_STATUS_LIMIT);
  }
}
