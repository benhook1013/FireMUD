package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
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

  public VersionDto publishFullVersion(
      String tenantId, String notes, String publishRequestId, String publishWorkflowId) {
    PublishWorkflowSnapshot snapshot =
        reconcileFullVersionPublish(
            new PublishWorkflowRequest(tenantId, notes, publishRequestId, publishWorkflowId));
    if (!snapshot.isSucceeded()) {
      throw publishFailure(snapshot.failureCode(), snapshot.failureMessage());
    }
    return versionMapper.toDto(requireTenantVersion(tenantId, snapshot.versionId()));
  }

  public PublishWorkflowSnapshot reconcileFullVersionPublish(PublishWorkflowRequest request) {
    validateRequestIdentity(request);
    logger.info(
        "Reconciling full-version publish workflow tenant={} workflowId={}",
        request.tenantId(),
        request.publishWorkflowId());
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()).orElse(null);
    if (attempt == null) {
      attempt = reserveDraftAttempt(request);
    }
    validateFullVersionAttempt(attempt, request);
    if (attempt.getStatus() == PublishAttemptStatus.SUCCEEDED) {
      return reconcileCommittedAttempt(request, attempt);
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

    Version version = requireAttemptVersion(attempt, request);
    PublicationReadback existingPublication = readPublication(request, attempt);
    if (existingPublication.isComplete()) {
      return reconcileCommittedAttempt(request, attempt);
    }
    if (existingPublication.isPartial()) {
      throw pendingReconciliation(
          "published release evidence is incomplete; readback/reconciliation is required");
    }
    if (version.getVersionState() != VersionLifecycleState.DRAFT) {
      throw pendingReconciliation(
          "pending full-version attempt does not reference a draft version");
    }

    List<PublishParticipantDigestDto> participantDigests;
    ExportedAssetManifest exportedManifest;
    try {
      VersionDto dto = versionMapper.toDto(version);
      participantDigests =
          publishGateService.collectFullVersionParticipantDigests(
              dto, request.publishRequestId(), request.publishWorkflowId());
      publishGateService.assertGatePassed(dto, participantDigests);
      recordedParticipantDigestService.assertMatchesRecordedDigests(
          dto.tenantId(), PublishType.FULL_VERSION, participantDigests);
      exportedManifest = assetExportService.exportAssets(request.tenantId(), dto.versionNumber());
    } catch (RuntimeException ex) {
      return failDefinitively(request, attempt, version, null, ex);
    }

    try {
      return publishAttemptService.executeFullVersionTransaction(
          () -> finalizeFullVersion(request, participantDigests, exportedManifest));
    } catch (PublishAttemptService.FullVersionTransactionException ex) {
      RuntimeException operationFailure = ex.causeException();
      if (operationFailure instanceof PendingReconciliationException) {
        throw operationFailure;
      }
      PublicationReadback readback = readPublication(request, attempt);
      if (readback.isComplete()) {
        return reconcileCommittedAttempt(request, attempt);
      }
      if (readback.isPartial()) {
        throw pendingReconciliation(
            "full-version finalization left incomplete evidence; readback/reconciliation is required",
            operationFailure);
      }
      return failDefinitively(request, attempt, version, exportedManifest, operationFailure);
    } catch (RuntimeException ambiguousCommit) {
      PublicationReadback readback = readPublication(request, attempt);
      if (readback.isComplete()) {
        return reconcileCommittedAttempt(request, attempt);
      }
      throw pendingReconciliation(
          "full-version finalization commit outcome is unknown; readback/reconciliation is required",
          ambiguousCommit);
    }
  }

  private PublishAttempt reserveDraftAttempt(PublishWorkflowRequest request) {
    try {
      return publishAttemptService.executeFullVersionTransaction(() -> createDraftAttempt(request));
    } catch (PublishAttemptService.FullVersionTransactionException ex) {
      throw ex.causeException();
    } catch (RuntimeException ambiguousReservation) {
      return publishAttemptRepository
          .findByPublishWorkflowId(request.publishWorkflowId())
          .orElseThrow(() -> ambiguousReservation);
    }
  }

  private PublishAttempt createDraftAttempt(PublishWorkflowRequest request) {
    Game game =
        Optional.ofNullable(gameRepository.findByTenantIdForUpdate(request.tenantId()))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    Optional<PublishAttempt> existingAttempt =
        publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId());
    if (existingAttempt.isPresent()) {
      return existingAttempt.get();
    }
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setNotes(request.notes());
    version.setVersionNumber(calculateNextNumber(request.tenantId()));
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setUpdatedAt(LocalDateTime.now());
    Version saved = versionRepository.save(version);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(
            request.tenantId(), String.valueOf(saved.getId()), request.publishRequestId());
    publishAttemptService.createFullVersionAttempt(
        versionMapper.toDto(saved), request.publishWorkflowId(), binding.requestDigest());
    return publishAttemptRepository
        .findByPublishWorkflowId(request.publishWorkflowId())
        .orElseThrow(() -> new IllegalStateException("publish attempt not found"));
  }

  private PublishWorkflowSnapshot finalizeFullVersion(
      PublishWorkflowRequest request,
      List<PublishParticipantDigestDto> participantDigests,
      ExportedAssetManifest exportedManifest) {
    if (gameRepository.findByTenantIdForUpdate(request.tenantId()) == null) {
      throw new IllegalArgumentException("game not found");
    }
    PublishAttempt attempt =
        publishAttemptRepository
            .findByPublishWorkflowId(request.publishWorkflowId())
            .orElseThrow(() -> new PendingReconciliationException("publish attempt not found"));
    validateFullVersionAttempt(attempt, request);
    if (attempt.getStatus() == PublishAttemptStatus.SUCCEEDED) {
      if (!readPublication(request, attempt).isComplete()) {
        throw pendingReconciliation(
            "succeeded full-version attempt lacks exact committed release evidence");
      }
      return succeededSnapshot(attempt);
    }
    if (attempt.getStatus() != PublishAttemptStatus.PENDING) {
      throw pendingReconciliation("full-version attempt is no longer pending");
    }

    Version version = requireAttemptVersion(attempt, request);
    PublicationReadback existingPublication = readPublication(request, attempt);
    if (existingPublication.isComplete()) {
      recordedParticipantDigestService.recordVerifiedDigests(
          request.tenantId(),
          PublishType.FULL_VERSION,
          request.publishWorkflowId(),
          existingPublication.bundle().participantDigests());
      publishAttemptService.markFullVersionSucceeded(request.publishWorkflowId());
      return succeededSnapshot(attempt);
    }
    if (existingPublication.isPartial()) {
      throw pendingReconciliation(
          "published release evidence is incomplete; readback/reconciliation is required");
    }
    if (version.getVersionState() != VersionLifecycleState.DRAFT) {
      throw pendingReconciliation(
          "pending full-version attempt does not reference a draft version");
    }

    VersionDto dto = versionMapper.toDto(version);
    publishAttemptService.recordFullVersionParticipantDigests(
        request.publishWorkflowId(), participantDigests);
    VersionAssetArtifactStateDto exportedState =
        versionAssetArtifactService.markExportedUnattested(
            dto.tenantId(),
            dto.id(),
            dto.versionNumber(),
            request.publishWorkflowId(),
            exportedManifest);
    if (exportedState == null) {
      throw new IllegalStateException("asset export state was not recorded");
    }
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
        exportedState.stateEpoch(),
        request.publishWorkflowId(),
        exportedManifest.manifestHash());
    recordedParticipantDigestService.recordVerifiedDigests(
        dto.tenantId(), PublishType.FULL_VERSION, request.publishWorkflowId(), participantDigests);
    publishAttemptService.markFullVersionSucceeded(request.publishWorkflowId());
    return succeededSnapshot(attempt);
  }

  private PublishWorkflowSnapshot reconcileCommittedAttempt(
      PublishWorkflowRequest request, PublishAttempt attempt) {
    PublicationReadback readback = readPublication(request, attempt);
    if (!readback.isComplete()) {
      throw pendingReconciliation(
          "succeeded full-version attempt lacks exact committed release evidence");
    }
    recordVerifiedAndSucceed(request, readback.bundle());
    return succeededSnapshot(attempt);
  }

  private void recordVerifiedAndSucceed(
      PublishWorkflowRequest request, PublishedReleaseBundleDto bundle) {
    try {
      publishAttemptService.executeFullVersionTransaction(
          () -> {
            if (gameRepository.findByTenantIdForUpdate(request.tenantId()) == null) {
              throw new IllegalArgumentException("game not found");
            }
            PublishAttempt current =
                publishAttemptRepository
                    .findByPublishWorkflowId(request.publishWorkflowId())
                    .orElseThrow(
                        () -> new PendingReconciliationException("publish attempt not found"));
            validateFullVersionAttempt(current, request);
            if (current.getStatus() != PublishAttemptStatus.PENDING
                && current.getStatus() != PublishAttemptStatus.SUCCEEDED) {
              throw pendingReconciliation("full-version attempt is no longer pending");
            }
            PublicationReadback currentReadback = readPublication(request, current);
            if (!currentReadback.isComplete()) {
              throw pendingReconciliation(
                  "published release evidence is incomplete; readback/reconciliation is required");
            }
            if (!Objects.equals(bundle, currentReadback.bundle())) {
              throw pendingReconciliation("published release bundle changed during reconciliation");
            }
            recordedParticipantDigestService.recordVerifiedDigests(
                request.tenantId(),
                PublishType.FULL_VERSION,
                request.publishWorkflowId(),
                currentReadback.bundle().participantDigests());
            if (current.getStatus() == PublishAttemptStatus.PENDING) {
              publishAttemptService.markFullVersionSucceeded(request.publishWorkflowId());
            }
            return null;
          });
    } catch (PublishAttemptService.FullVersionTransactionException ex) {
      throw pendingReconciliation(
          "committed publication readback could not be reconciled", ex.causeException());
    } catch (RuntimeException ex) {
      throw pendingReconciliation("committed publication readback could not be reconciled", ex);
    }
  }

  private PublishWorkflowSnapshot failDefinitively(
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      Version version,
      ExportedAssetManifest exportedManifest,
      RuntimeException failure) {
    String failureCode = publishFailureCode(failure);
    String failureMessage = publishFailureMessage(failure);
    try {
      publishAttemptService.executeFullVersionTransaction(
          () -> {
            if (gameRepository.findByTenantIdForUpdate(request.tenantId()) == null) {
              throw new IllegalArgumentException("game not found");
            }
            PublishAttempt current =
                publishAttemptRepository
                    .findByPublishWorkflowId(request.publishWorkflowId())
                    .orElseThrow(
                        () -> new PendingReconciliationException("publish attempt not found"));
            validateFullVersionAttempt(current, request);
            if (current.getStatus() != PublishAttemptStatus.PENDING) {
              throw pendingReconciliation("full-version attempt is no longer pending");
            }
            PublicationReadback readback = readPublication(request, current);
            if (!readback.isAbsent()) {
              throw pendingReconciliation(
                  "publication failure has committed or partial release evidence; reconciliation is required");
            }
            Version currentVersion = requireAttemptVersion(current, request);
            if (exportedManifest != null) {
              versionAssetArtifactService.markFailed(
                  request.tenantId(),
                  currentVersion.getId(),
                  currentVersion.getVersionNumber(),
                  request.publishWorkflowId(),
                  exportedManifest,
                  failureCode,
                  failureMessage);
            }
            publishAttemptService.markFullVersionFailed(
                request.publishWorkflowId(), failureCode, failureMessage);
            // A failed candidate may be abandoned only while no bundle references it. The
            // readback above is in this same locked transaction, so never delete a bundled version.
            versionRepository.delete(currentVersion);
            return Boolean.TRUE;
          });
    } catch (PublishAttemptService.FullVersionTransactionException ex) {
      throw ex.causeException();
    } catch (RuntimeException ambiguousFailure) {
      throw ambiguousFailure;
    }
    cleanupExportedAssets(request.tenantId(), version.getVersionNumber(), exportedManifest);
    return new PublishWorkflowSnapshot(
        attempt.getVersionId(),
        attempt.getVersionNumber(),
        request.publishWorkflowId(),
        "FAILED",
        failureCode,
        failureMessage);
  }

  private void validateRequestIdentity(PublishWorkflowRequest request) {
    PublicationDigestRequestBinding.validatePublicationIdentity(
        request.tenantId(), request.publishRequestId());
    String expectedWorkflowId =
        TemporalVersionPublishOrchestrator.workflowId(
            request.tenantId(), request.publishRequestId());
    if (!Objects.equals(expectedWorkflowId, request.publishWorkflowId())) {
      throw new IllegalArgumentException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: publish workflow does not match request identity");
    }
  }

  private void validateFullVersionAttempt(PublishAttempt attempt, PublishWorkflowRequest request) {
    if (attempt == null
        || !Objects.equals(attempt.getTenantId(), request.tenantId())
        || !Objects.equals(attempt.getPublishWorkflowId(), request.publishWorkflowId())
        || attempt.getPublishType() != PublishType.FULL_VERSION
        || attempt.getVersionId() == null
        || attempt.getVersionNumber() <= 0) {
      throw new IllegalStateException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: full-version attempt evidence does not match request");
    }
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(
            request.tenantId(), String.valueOf(attempt.getVersionId()), request.publishRequestId());
    if (!Objects.equals(binding.requestDigest(), attempt.getRequestDigest())) {
      throw new IllegalStateException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: full-version request digest does not match request");
    }
  }

  private Version requireAttemptVersion(PublishAttempt attempt, PublishWorkflowRequest request) {
    validateFullVersionAttempt(attempt, request);
    Version version = requireTenantVersion(request.tenantId(), attempt.getVersionId());
    if (!Objects.equals(version.getTenantId(), request.tenantId())
        || version.getVersionNumber() != attempt.getVersionNumber()
        || version.isScriptOnly()) {
      throw new IllegalStateException(
          "PUBLISH_ATTEMPT_SCOPE_MISMATCH: referenced version evidence does not match request");
    }
    return version;
  }

  private PublicationReadback readPublication(
      PublishWorkflowRequest request, PublishAttempt attempt) {
    Optional<Version> version =
        versionRepository.findByTenantIdAndId(request.tenantId(), attempt.getVersionId());
    if (version.isEmpty()) {
      return PublicationReadback.partial();
    }
    PublishedReleaseBundleDto bundle =
        tryGetPublishedReleaseBundle(request.tenantId(), attempt.getVersionId());
    VersionAssetArtifactStateDto artifact =
        tryGetVersionAssetArtifactState(request.tenantId(), attempt.getVersionId());
    if (bundle == null
        && artifact == null
        && version.get().getVersionState() == VersionLifecycleState.DRAFT) {
      return PublicationReadback.absent();
    }
    if (bundle == null || artifact == null) {
      return PublicationReadback.partial();
    }
    try {
      PublishedReleaseBundleContract.requireSupportedSchemaForRead(bundle);
      requireExactBundleEvidence(request, attempt, version.get(), bundle);
      requireExactArtifactEvidence(request, attempt, bundle, artifact);
    } catch (RuntimeException ex) {
      return PublicationReadback.partial();
    }
    if (version.get().getVersionState() != VersionLifecycleState.PUBLISHED) {
      return PublicationReadback.partial();
    }
    return PublicationReadback.complete(version.get(), bundle, artifact);
  }

  private void requireExactBundleEvidence(
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      Version version,
      PublishedReleaseBundleDto bundle) {
    if (!Objects.equals(bundle.tenantId(), request.tenantId())
        || !Objects.equals(bundle.versionId(), attempt.getVersionId())
        || bundle.versionNumber() != attempt.getVersionNumber()
        || !Objects.equals(bundle.publishWorkflowId(), request.publishWorkflowId())
        || !Objects.equals(version.getTenantId(), bundle.tenantId())
        || version.getVersionNumber() != bundle.versionNumber()
        || version.isScriptOnly()
        || bundle.scriptOnly()
        || bundle.scriptPatchVersion() != null) {
      throw new IllegalStateException("PUBLISH_ATTEMPT_BUNDLE_SCOPE_MISMATCH");
    }
  }

  private void requireExactArtifactEvidence(
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      PublishedReleaseBundleDto bundle,
      VersionAssetArtifactStateDto artifact) {
    if (!Objects.equals(artifact.tenantId(), request.tenantId())
        || !Objects.equals(artifact.versionId(), attempt.getVersionId())
        || artifact.exportedVersionNumber() != attempt.getVersionNumber()
        || !"PUBLISHED".equals(artifact.artifactState())
        || !Objects.equals(artifact.lastWorkflowId(), request.publishWorkflowId())
        || !Objects.equals(artifact.manifestHash(), bundle.manifestHash())
        || !Objects.equals(
            artifact.exportedManifestAssetKeys(), bundle.requiredManifestAssetKeys())) {
      throw new IllegalStateException("PUBLISH_ATTEMPT_ARTIFACT_SCOPE_MISMATCH");
    }
  }

  private VersionAssetArtifactStateDto tryGetVersionAssetArtifactState(
      String tenantId, long versionId) {
    try {
      return versionAssetArtifactService.getState(tenantId, versionId);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private PublishWorkflowSnapshot succeededSnapshot(PublishAttempt attempt) {
    return new PublishWorkflowSnapshot(
        attempt.getVersionId(),
        attempt.getVersionNumber(),
        attempt.getPublishWorkflowId(),
        "SUCCEEDED",
        "",
        "");
  }

  private PendingReconciliationException pendingReconciliation(String message) {
    return new PendingReconciliationException(message);
  }

  private PendingReconciliationException pendingReconciliation(
      String message, RuntimeException cause) {
    return new PendingReconciliationException(message, cause);
  }

  private static final class PendingReconciliationException extends IllegalStateException {
    private PendingReconciliationException(String message) {
      super(message);
    }

    private PendingReconciliationException(String message, RuntimeException cause) {
      super(message, cause);
    }
  }

  private record PublicationReadback(
      ReadbackState state,
      Version version,
      PublishedReleaseBundleDto bundle,
      VersionAssetArtifactStateDto artifact) {
    private static PublicationReadback absent() {
      return new PublicationReadback(ReadbackState.ABSENT, null, null, null);
    }

    private static PublicationReadback partial() {
      return new PublicationReadback(ReadbackState.PARTIAL, null, null, null);
    }

    private static PublicationReadback complete(
        Version version, PublishedReleaseBundleDto bundle, VersionAssetArtifactStateDto artifact) {
      return new PublicationReadback(ReadbackState.COMPLETE, version, bundle, artifact);
    }

    private boolean isAbsent() {
      return state == ReadbackState.ABSENT;
    }

    private boolean isPartial() {
      return state == ReadbackState.PARTIAL;
    }

    private boolean isComplete() {
      return state == ReadbackState.COMPLETE;
    }
  }

  private enum ReadbackState {
    ABSENT,
    PARTIAL,
    COMPLETE
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
