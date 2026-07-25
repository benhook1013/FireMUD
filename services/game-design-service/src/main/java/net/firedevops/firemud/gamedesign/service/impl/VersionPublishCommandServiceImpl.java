package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
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
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators remain internal service dependencies")
public class VersionPublishCommandServiceImpl {
  private static final Logger logger =
      LoggingUtil.getLogger(VersionPublishCommandServiceImpl.class);

  private final VersionRepository versionRepository;
  private final GameRepository gameRepository;
  private final PublishAttemptRepository publishAttemptRepository;
  private final VersionMapper versionMapper;
  private final AssetExportService assetExportService;
  private final PublishAttemptService publishAttemptService;
  private final PublishGateService publishGateService;
  private final ControlPlaneDigestService controlPlaneDigestService;
  private final VersionAssetArtifactService versionAssetArtifactService;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final RecordedParticipantDigestService recordedParticipantDigestService;

  public VersionPublishCommandServiceImpl(
      VersionRepository versionRepository,
      GameRepository gameRepository,
      PublishAttemptRepository publishAttemptRepository,
      VersionMapper versionMapper,
      AssetExportService assetExportService,
      PublishAttemptService publishAttemptService,
      PublishGateService publishGateService,
      ControlPlaneDigestService controlPlaneDigestService,
      VersionAssetArtifactService versionAssetArtifactService,
      PublishedReleaseBundleService publishedReleaseBundleService,
      RecordedParticipantDigestService recordedParticipantDigestService) {
    this.versionRepository = versionRepository;
    this.gameRepository = gameRepository;
    this.publishAttemptRepository = publishAttemptRepository;
    this.versionMapper = versionMapper;
    this.assetExportService = assetExportService;
    this.publishAttemptService = publishAttemptService;
    this.publishGateService = publishGateService;
    this.controlPlaneDigestService = controlPlaneDigestService;
    this.versionAssetArtifactService = versionAssetArtifactService;
    this.publishedReleaseBundleService = publishedReleaseBundleService;
    this.recordedParticipantDigestService = recordedParticipantDigestService;
  }

  @Transactional
  public VersionDto publishFullVersion(String tenantId, String notes, String publishWorkflowId) {
    PublishWorkflowSnapshot snapshot =
        reconcileFullVersionPublish(new PublishWorkflowRequest(tenantId, notes, publishWorkflowId));
    if (!snapshot.isSucceeded()) {
      throw publishFailure(snapshot.failureCode(), snapshot.failureMessage());
    }
    return versionMapper.toDto(requireTenantVersion(tenantId, snapshot.versionId()));
  }

  @Transactional
  public PublishWorkflowSnapshot reconcileFullVersionPublish(PublishWorkflowRequest request) {
    logger.info(
        "Reconciling full-version publish workflow tenant={} workflowId={}",
        request.tenantId(),
        request.publishWorkflowId());
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()).orElse(null);
    if (attempt == null) {
      attempt = createDraftAttempt(request);
    }
    if (attempt.getStatus() == PublishAttemptStatus.SUCCEEDED) {
      return new PublishWorkflowSnapshot(
          attempt.getVersionId(),
          attempt.getVersionNumber(),
          request.publishWorkflowId(),
          "SUCCEEDED",
          "",
          "");
    }
    if (attempt.getStatus() == PublishAttemptStatus.FAILED) {
      return new PublishWorkflowSnapshot(
          attempt.getVersionId() == null ? 0L : attempt.getVersionId(),
          attempt.getVersionNumber(),
          request.publishWorkflowId(),
          "FAILED",
          emptyIfNull(attempt.getFailureCode()),
          emptyIfNull(attempt.getFailureMessage()));
    }

    Version version = requireTenantVersion(request.tenantId(), attempt.getVersionId());
    VersionDto dto = versionMapper.toDto(version);
    PublishedReleaseBundleDto existingBundle =
        tryGetPublishedReleaseBundle(request.tenantId(), dto.id());
    if (version.getVersionState() == VersionLifecycleState.PUBLISHED && existingBundle != null) {
      publishAttemptService.markSucceeded(request.publishWorkflowId());
      return new PublishWorkflowSnapshot(
          dto.id(), dto.versionNumber(), request.publishWorkflowId(), "SUCCEEDED", "", "");
    }

    ExportedAssetManifest exportedManifest = null;
    try {
      List<PublishParticipantDigestDto> participantDigests =
          publishGateService.collectFullVersionParticipantDigests(dto);
      publishAttemptService.recordParticipantDigests(
          request.publishWorkflowId(), participantDigests);
      publishGateService.assertGatePassed(dto, participantDigests);
      recordedParticipantDigestService.assertMatchesRecordedDigests(
          dto.tenantId(), PublishType.FULL_VERSION, participantDigests);
      exportedManifest = assetExportService.exportAssets(request.tenantId(), dto.versionNumber());
      long exportedStateEpoch =
          versionAssetArtifactService
              .markExportedUnattested(
                  dto.tenantId(),
                  dto.id(),
                  dto.versionNumber(),
                  request.publishWorkflowId(),
                  exportedManifest)
              .stateEpoch();
      String generationConfigRevision = generationConfigRevision(dto, exportedManifest);
      publishedReleaseBundleService.createFullVersionBundle(
          dto,
          request.publishWorkflowId(),
          exportedManifest,
          generationConfigRevision,
          participantDigests);
      version.setVersionState(VersionLifecycleState.PUBLISHED);
      version.setVersionStateEpoch(version.getVersionStateEpoch() + 1L);
      version.setUpdatedAt(LocalDateTime.now());
      versionRepository.save(version);
      versionAssetArtifactService.markPublished(
          dto.tenantId(),
          dto.id(),
          exportedStateEpoch,
          request.publishWorkflowId(),
          exportedManifest.manifestHash());
      recordedParticipantDigestService.recordVerifiedDigests(
          dto.tenantId(),
          PublishType.FULL_VERSION,
          request.publishWorkflowId(),
          participantDigests);
      publishAttemptService.markSucceeded(request.publishWorkflowId());
      return new PublishWorkflowSnapshot(
          dto.id(), dto.versionNumber(), request.publishWorkflowId(), "SUCCEEDED", "", "");
    } catch (RuntimeException ex) {
      publishAttemptService.markFailed(
          request.publishWorkflowId(), publishFailureCode(ex), publishFailureMessage(ex));
      if (exportedManifest != null) {
        versionAssetArtifactService.markFailed(
            dto.tenantId(),
            dto.id(),
            dto.versionNumber(),
            request.publishWorkflowId(),
            exportedManifest,
            publishFailureCode(ex),
            publishFailureMessage(ex));
      }
      cleanupExportedAssets(request.tenantId(), dto.versionNumber(), exportedManifest);
      versionRepository.delete(version);
      return new PublishWorkflowSnapshot(
          dto.id(),
          dto.versionNumber(),
          request.publishWorkflowId(),
          "FAILED",
          publishFailureCode(ex),
          publishFailureMessage(ex));
    }
  }

  private PublishAttempt createDraftAttempt(PublishWorkflowRequest request) {
    Game game =
        Optional.ofNullable(gameRepository.findByTenantIdForUpdate(request.tenantId()))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setNotes(request.notes());
    version.setVersionNumber(calculateNextNumber(request.tenantId()));
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setUpdatedAt(LocalDateTime.now());
    Version saved = versionRepository.save(version);
    publishAttemptService.createAttempt(
        versionMapper.toDto(saved), PublishType.FULL_VERSION, request.publishWorkflowId());
    return publishAttemptRepository
        .findByPublishWorkflowId(request.publishWorkflowId())
        .orElseThrow(() -> new IllegalStateException("publish attempt not found"));
  }

  private int calculateNextNumber(String tenantId) {
    return versionRepository
            .findTopByTenantIdOrderByVersionNumberDesc(tenantId)
            .map(Version::getVersionNumber)
            .orElse(0)
        + 1;
  }

  private PublishedReleaseBundleDto tryGetPublishedReleaseBundle(String tenantId, long versionId) {
    try {
      return publishedReleaseBundleService.getPublishedReleaseBundle(tenantId, versionId);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private String generationConfigRevision(
      VersionDto version, ExportedAssetManifest exportedManifest) {
    String manifestHash = exportedManifest == null ? "manifest" : exportedManifest.manifestHash();
    String worldDigest =
        controlPlaneDigestService.getDigestForVersion(version).contentDigest() == null
            ? "world"
            : controlPlaneDigestService.getDigestForVersion(version).contentDigest();
    return "genrev:"
        + version.tenantId()
        + ":"
        + version.id()
        + ":"
        + manifestHash
        + ":"
        + worldDigest;
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

  private RuntimeException publishFailure(String failureCode, String failureMessage) {
    if (failureCode != null && !failureCode.isBlank()) {
      try {
        return new PublishGateFailureException(
            net.firedevops.firemud.gamedesign.model.PublishGateFailureCode.valueOf(failureCode),
            failureMessage);
      } catch (IllegalArgumentException ignored) {
        // Fall through to a generic failure when the code is not a gate-failure enum.
      }
    }
    return new IllegalStateException(
        (failureMessage == null || failureMessage.isBlank())
            ? emptyIfNull(failureCode)
            : failureMessage);
  }

  private void cleanupExportedAssets(
      String tenantId, int versionNumber, ExportedAssetManifest exportedManifest) {
    if (exportedManifest == null) {
      return;
    }
    try {
      assetExportService.deleteExportedAssets(
          tenantId, versionNumber, exportedManifest.requiredManifestAssetKeys());
    } catch (RuntimeException cleanupEx) {
      logger.warn(
          "Failed cleanup of exported assets for tenant {} version {} after publish failure: {}",
          tenantId,
          versionNumber,
          cleanupEx.getMessage());
    }
  }

  private Version requireTenantVersion(String tenantId, long versionId) {
    return versionRepository
        .findByTenantIdAndId(tenantId, versionId)
        .orElseThrow(() -> new IllegalArgumentException("version not found"));
  }

  private String emptyIfNull(String value) {
    return value == null ? "" : value;
  }
}
