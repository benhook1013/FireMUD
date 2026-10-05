package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportOutcomePendingException;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublicationFailureClassifier;
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
  private final AuthoredDraftPublishSelectionRepository authoredSelections;

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
      RecordedParticipantDigestService recordedParticipantDigestService,
      AuthoredDraftPublishSelectionRepository authoredSelections) {
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
    this.authoredSelections = authoredSelections;
  }

  /** Internal plumbing only: a durable selection does not establish creator authorization. */
  PublishWorkflowRequest reserveSelection(PublishIntent intent) {
    try {
      return publishAttemptService.executeFullVersionTransaction(
          () -> {
            AuthoredDraftPublishSelection selection =
                authoredSelections.reserve(intent).selection();
            return new PublishWorkflowRequest(
                selection.target().gameDesignVersionTenantKey(),
                intent.notes(),
                intent.publishRequestId(),
                TemporalVersionPublishOrchestrator.workflowId(
                    intent.canonicalTenantId().toString(), intent.publishRequestId()),
                selection.intent());
          });
    } catch (PublishAttemptService.FullVersionTransactionException exception) {
      throw exception.causeException();
    }
  }

  VersionDto publishFullVersion(PublishWorkflowRequest request) {
    PublishWorkflowSnapshot snapshot = reconcileFullVersionPublish(request);
    if (!snapshot.isSucceeded()) {
      throw publishFailure(snapshot.failureCode(), snapshot.failureMessage());
    }
    return versionMapper.toDto(requireTenantVersion(request.tenantId(), snapshot.versionId()));
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
    request = request.recoverMissingPublishRequestId();
    validateRequestIdentity(request);
    if (request.intent() != null) {
      requireSelectedIntent(request);
    }
    logger.info(
        "Reconciling full-version publish workflow tenant={} workflowId={}",
        request.tenantId(),
        request.publishWorkflowId());
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()).orElse(null);
    if (attempt == null) {
      requireSelectedIntent(request);
      attempt = reserveDraftAttempt(request);
    }
    if (attempt.getStatus() == PublishAttemptStatus.SUCCEEDED) {
      validateTerminalFullVersionAttempt(attempt, request);
      return replaySucceededAttempt(request, attempt);
    }
    if (attempt.getStatus() == PublishAttemptStatus.FAILED) {
      // Failed legacy attempts may retain only terminal evidence after their draft was deleted.
      // Validate their stable scope before entering the draft-dependent compatibility backfill.
      validateTerminalFullVersionAttempt(attempt, request);
      return new PublishWorkflowSnapshot(
          attempt.getVersionId() == null ? 0L : attempt.getVersionId(),
          attempt.getVersionNumber(),
          request.publishWorkflowId(),
          "FAILED",
          emptyIfNull(attempt.getFailureCode()),
          emptyIfNull(attempt.getFailureMessage()));
    }
    requireSelectedIntent(request);
    validateFullVersionAttempt(attempt, request);

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
    requireSelectedDraftEpoch(request, version);
    requireCanonicalOwnerPublicationCarrier(request);
    return reconcileSelectedPublicationMechanics(request);
  }

  /**
   * Internal selected-attempt recovery mechanics, not a publication authorization entrypoint.
   *
   * <p>The workflow entrypoint must establish its owner-carrier boundary before reaching this
   * component. Direct fixture invocation stipulates that missing boundary and proves only the
   * existing export, transaction and exact-readback mechanics; it never enables public writes.
   */
  PublishWorkflowSnapshot reconcileSelectedPublicationMechanics(PublishWorkflowRequest request) {
    validateRequestIdentity(request);
    requireSelectedIntent(request);
    PublishAttempt attempt =
        publishAttemptRepository
            .findByPublishWorkflowId(request.publishWorkflowId())
            .orElseThrow(() -> pendingReconciliation("selected publish attempt is absent"));
    validateFullVersionAttempt(attempt, request);
    Version version = requireAttemptVersion(attempt, request);
    PublicationReadback existingPublication = readPublication(request, attempt);
    if (existingPublication.isComplete()) {
      return reconcileCommittedAttempt(request, attempt);
    }
    if (existingPublication.isPartial()) {
      throw pendingReconciliation("selected publication evidence is incomplete");
    }
    if (attempt.getStatus() != PublishAttemptStatus.PENDING
        || version.getVersionState() != VersionLifecycleState.DRAFT) {
      throw pendingReconciliation("selected publication is not the pending Draft operation");
    }
    requireSelectedDraftEpoch(request, version);

    VersionDto dto;
    List<PublishParticipantDigestDto> participantDigests;
    try {
      dto = versionMapper.toDto(version);
      participantDigests =
          publishGateService.collectFullVersionParticipantDigests(
              dto, request.publishRequestId(), request.publishWorkflowId());
    } catch (RuntimeException ex) {
      if (PublicationFailureClassifier.isRetryableParticipantDependencyFailure(ex)) {
        throw pendingReconciliation(
            "participant digest dependency is temporarily unavailable; retry exact publish request",
            ex);
      }
      return failDefinitively(request, attempt, version, null, ex);
    }
    try {
      assertSelectedCommit(request, participantDigests);
      publishGateService.assertGatePassed(dto, participantDigests);
    } catch (RuntimeException ex) {
      if (PublicationFailureClassifier.isRetryableParticipantDependencyFailure(ex)) {
        throw pendingReconciliation(
            "participant digest dependency is temporarily unavailable; retry exact publish request",
            ex);
      }
      return failDefinitively(request, attempt, version, null, ex);
    }
    try {
      recordedParticipantDigestService.assertMatchesRecordedDigests(
          dto.tenantId(), PublishType.FULL_VERSION, participantDigests);
    } catch (RuntimeException ex) {
      return failDefinitively(request, attempt, version, null, ex);
    }
    if (existingPublication.isAbsent()) {
      try {
        VersionAssetArtifactStateDto staged =
            versionAssetArtifactService.stageExport(
                request.tenantId(), dto.id(), dto.versionNumber(), request.publishWorkflowId());
        VersionAssetArtifactStateDto stagedReadback =
            versionAssetArtifactService
                .findState(request.tenantId(), dto.id())
                .orElseThrow(() -> pendingReconciliation("asset export intent readback is absent"));
        if (staged == null || !staged.equals(stagedReadback)) {
          throw pendingReconciliation("asset export intent readback does not match its commit");
        }
        verifyStagedIntent(request, attempt, version, stagedReadback);
      } catch (RuntimeException ex) {
        throw pendingReconciliation("asset export intent commit requires exact readback", ex);
      }
    }
    ExportedAssetManifest exportedManifest;
    try {
      exportedManifest = assetExportService.exportAssets(request.tenantId(), dto.versionNumber());
    } catch (AssetExportOutcomePendingException ex) {
      throw pendingReconciliation(
          "asset export outcome remains unresolved; retry exact request", ex);
    } catch (RuntimeException ex) {
      // Export may have committed candidate evidence or earlier immutable objects before failing.
      // A deterministic later conflict does not prove that every prior write is resolved.
      throw pendingReconciliation("asset export requires exact candidate/write reconciliation", ex);
    }

    PublishWorkflowRequest effectiveRequest = request;
    try {
      return publishAttemptService.executeFullVersionTransaction(
          () -> finalizeFullVersion(effectiveRequest, participantDigests, exportedManifest));
    } catch (PublishAttemptService.FullVersionTransactionException ex) {
      RuntimeException operationFailure = ex.causeException();
      if (operationFailure instanceof PendingReconciliationException) {
        throw operationFailure;
      }
      PublicationReadback readback;
      try {
        readback = readPublication(request, attempt);
      } catch (RuntimeException readFailure) {
        readFailure.addSuppressed(operationFailure);
        throw pendingReconciliation(
            "full-version finalization readback failed; reconciliation is required", readFailure);
      }
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
      PublicationReadback readback;
      try {
        readback = readPublication(request, attempt);
      } catch (RuntimeException readFailure) {
        readFailure.addSuppressed(ambiguousCommit);
        throw pendingReconciliation(
            "full-version finalization readback failed; reconciliation is required", readFailure);
      }
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
    AuthoredDraftPublishSelection selection = requireSelectedIntent(request);
    Optional<PublishAttempt> existingAttempt =
        publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId());
    if (existingAttempt.isPresent()) {
      validateFullVersionAttempt(existingAttempt.get(), request);
      return existingAttempt.get();
    }
    Version saved =
        versionRepository
            .findByTenantIdAndIdForUpdate(
                selection.target().gameDesignVersionTenantKey(),
                selection.target().gameDesignVersionRowId())
            .orElseThrow(() -> new IllegalStateException("Selected authored Version is absent"));
    if (saved.isScriptOnly()
        || saved.getVersionState() != VersionLifecycleState.DRAFT
        || !Objects.equals(
            saved.getVersionStateEpoch(),
            Long.parseLong(selection.intent().expectedVersionStateEpoch()))) {
      throw new IllegalStateException(
          "Selected authored Version is no longer the exact reserved Draft");
    }
    publishAttemptService.createFullVersionAttempt(
        versionMapper.toDto(saved), request.publishWorkflowId(), selection.digest());
    return publishAttemptRepository
        .findByPublishWorkflowId(request.publishWorkflowId())
        .orElseThrow(() -> new IllegalStateException("publish attempt not found"));
  }

  /** Transaction-owned internal mechanic; fixture invocation does not prove owner authorization. */
  PublishWorkflowSnapshot finalizeFullVersion(
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
      PublicationReadback readback = readPublication(request, attempt);
      if (!readback.isComplete()) {
        throw pendingReconciliation(
            "succeeded full-version attempt lacks exact committed release evidence");
      }
      assertCommittedBundleMayMarkSuccess(request, readback);
      return succeededSnapshot(attempt);
    }
    if (attempt.getStatus() != PublishAttemptStatus.PENDING) {
      throw pendingReconciliation("full-version attempt is no longer pending");
    }

    Version version = requireAttemptVersionForUpdate(attempt, request);
    PublicationReadback existingPublication = readPublication(request, attempt);
    if (existingPublication.isComplete()) {
      assertCommittedBundleMayMarkSuccess(request, existingPublication);
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
    requireSelectedDraftEpoch(request, version);
    assertSelectedCommit(request, participantDigests);
    publishGateService.assertGatePassed(dto, participantDigests);
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
    version.setVersionStateEpoch(Math.addExact(version.getVersionStateEpoch(), 1L));
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

  private PublishWorkflowSnapshot replaySucceededAttempt(
      PublishWorkflowRequest request, PublishAttempt attempt) {
    PublishedReleaseBundleDto bundle;
    try {
      bundle = readPublishedReleaseBundle(request.tenantId(), attempt.getVersionId());
    } catch (RuntimeException ex) {
      throw pendingReconciliation(
          "succeeded full-version attempt release bundle read is uncertain", ex);
    }
    if (bundle == null) {
      throw pendingReconciliation(
          "succeeded full-version attempt lacks its committed release bundle");
    }
    try {
      requireExactCommittedBundleIdentity(request, attempt, bundle);
      if (request.intent() != null) {
        assertCommittedBundleMayMarkSuccess(request, readPublication(request, attempt));
      }
    } catch (RuntimeException ex) {
      throw pendingReconciliation(
          "succeeded full-version attempt release bundle identity does not match", ex);
    }
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
            requireAttemptVersionForUpdate(current, request);
            PublicationReadback currentReadback = readPublication(request, current);
            if (!currentReadback.isComplete()) {
              throw pendingReconciliation(
                  "published release evidence is incomplete; readback/reconciliation is required");
            }
            if (!Objects.equals(bundle, currentReadback.bundle())) {
              throw pendingReconciliation("published release bundle changed during reconciliation");
            }
            assertCommittedBundleMayMarkSuccess(request, currentReadback);
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

  /**
   * Revalidates durable publication evidence before it can bless an attempt as successful.
   *
   * <p>Readback proves that the bundle and artifact are structurally tied to this attempt, but it
   * does not by itself prove that the participant matrix, typed scope, digest schema/content, and
   * recorded baseline still satisfy the canonical publication gate. Reconciliation must apply the
   * same local contracts as the initial publication path and fail closed for malformed or legacy
   * evidence.
   */
  private void assertCommittedBundleMayMarkSuccess(
      PublishWorkflowRequest request, PublicationReadback readback) {
    if (!readback.isComplete() || readback.version() == null || readback.bundle() == null) {
      throw pendingReconciliation(
          "published release evidence is incomplete; readback/reconciliation is required");
    }
    VersionDto versionDto = versionMapper.toDto(readback.version());
    PublishedReleaseBundleContract.requireSupportedSchemaForRead(readback.bundle());
    assertSelectedCommit(request, readback.bundle().participantDigests());
    publishGateService.assertGatePassed(versionDto, readback.bundle().participantDigests());
    recordedParticipantDigestService.assertMatchesRecordedDigests(
        request.tenantId(), PublishType.FULL_VERSION, readback.bundle().participantDigests());
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
            Version currentVersion = requireAttemptVersionForUpdate(current, request);
            PublicationReadback readback = readPublication(request, current);
            if (!readback.isAbsent()) {
              if (!readback.isStagedNoRelease()) {
                throw pendingReconciliation(
                    "publication failure has committed or partial release evidence; reconciliation is required");
              }
              if (exportedManifest == null
                  || !Objects.equals(readback.candidate(), exportedManifest)) {
                throw pendingReconciliation(
                    "staged asset writes remain unresolved; exact export readback is required");
              }
            }
            if (exportedManifest != null) {
              if (!readback.isStagedNoRelease()) {
                throw pendingReconciliation(
                    "exported asset manifest lacks an exact staged intent readback");
              }
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
            // Approved launch remap sets may reference this failed candidate, so retain the row.
            return Boolean.TRUE;
          });
    } catch (PublishAttemptService.FullVersionTransactionException ex) {
      throw pendingReconciliation(
          "full-version failure marking commit outcome is unknown; readback/reconciliation is required",
          ex.causeException());
    } catch (RuntimeException ambiguousFailure) {
      throw pendingReconciliation(
          "full-version failure marking commit outcome is unknown; readback/reconciliation is required",
          ambiguousFailure);
    }
    // Immutable candidate keys can be shared with another release. Retain them for the owner
    // abandonment/reachability workflow; a failed publication is never deletion authority.
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
            request.intent() == null
                ? request.tenantId()
                : request.intent().canonicalTenantId().toString(),
            request.publishRequestId());
    if (!Objects.equals(expectedWorkflowId, request.publishWorkflowId())) {
      throw new IllegalArgumentException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: publish workflow does not match request identity");
    }
  }

  private void validateFullVersionAttempt(PublishAttempt attempt, PublishWorkflowRequest request) {
    validateFullVersionAttemptIdentity(attempt, request);
    AuthoredDraftPublishSelection selection = requireSelectedIntent(request);
    if (!Objects.equals(selection.target().gameDesignVersionRowId(), attempt.getVersionId())
        || !Objects.equals(selection.digest(), attempt.getRequestDigest())) {
      throw new IllegalStateException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: full-version request digest does not match request");
    }
  }

  /**
   * Validates terminal full-version replay using only durable attempt identity and, when present,
   * the request digest. Legacy terminal rows intentionally have no request digest and may no longer
   * have a draft version to backfill, so they must not enter the draft-dependent path.
   */
  private void validateTerminalFullVersionAttempt(
      PublishAttempt attempt, PublishWorkflowRequest request) {
    if (request.intent() != null) {
      validateFullVersionAttempt(attempt, request);
      return;
    }
    validateFullVersionAttemptIdentity(attempt, request);
    if (attempt.getRequestDigest() == null) {
      // Legacy terminal attempts predate the digest column. Their canonical workflow identity,
      // persisted full-version scope, and exact release-bundle identity are the replay binding.
      return;
    }
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(
            request.tenantId(), String.valueOf(attempt.getVersionId()), request.publishRequestId());
    if (!Objects.equals(binding.requestDigest(), attempt.getRequestDigest())) {
      throw new IllegalStateException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: full-version request digest does not match request");
    }
  }

  private void validateFullVersionAttemptIdentity(
      PublishAttempt attempt, PublishWorkflowRequest request) {
    if (attempt == null
        || !Objects.equals(attempt.getTenantId(), request.tenantId())
        || !Objects.equals(attempt.getPublishWorkflowId(), request.publishWorkflowId())
        || attempt.getPublishType() != PublishType.FULL_VERSION
        || attempt.getBaseVersionId() != null
        || attempt.getScriptPatchVersion() != null
        || attempt.getVersionId() == null
        || attempt.getVersionNumber() <= 0) {
      throw new IllegalStateException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: full-version attempt evidence does not match request");
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

  private Version requireAttemptVersionForUpdate(
      PublishAttempt attempt, PublishWorkflowRequest request) {
    validateFullVersionAttempt(attempt, request);
    Version version =
        versionRepository
            .findByTenantIdAndIdForUpdate(request.tenantId(), attempt.getVersionId())
            .orElseThrow(() -> new IllegalArgumentException("version not found"));
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
        readPublishedReleaseBundle(request.tenantId(), attempt.getVersionId());
    VersionAssetArtifactStateDto artifact =
        readVersionAssetArtifactState(request.tenantId(), attempt.getVersionId());
    if (bundle == null
        && artifact == null
        && version.get().getVersionState() == VersionLifecycleState.DRAFT) {
      return PublicationReadback.absent(version.get());
    }
    if (bundle == null
        && version.get().getVersionState() == VersionLifecycleState.DRAFT
        && artifact != null) {
      try {
        ExportedAssetManifest candidate =
            verifyStagedIntent(request, attempt, version.get(), artifact);
        return PublicationReadback.stagedNoRelease(version.get(), artifact, candidate);
      } catch (RuntimeException ex) {
        return PublicationReadback.partial();
      }
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

  private ExportedAssetManifest verifyStagedIntent(
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      Version version,
      VersionAssetArtifactStateDto artifact) {
    if (!Objects.equals(version.getTenantId(), request.tenantId())
        || !Objects.equals(version.getId(), attempt.getVersionId())
        || version.getVersionNumber() != attempt.getVersionNumber()
        || version.getVersionState() != VersionLifecycleState.DRAFT
        || version.getVersionStateEpoch() == null
        || version.getVersionStateEpoch() <= 0
        || version.isScriptOnly()
        || !Objects.equals(artifact.tenantId(), request.tenantId())
        || !Objects.equals(artifact.versionId(), attempt.getVersionId())
        || artifact.exportedVersionNumber() != attempt.getVersionNumber()
        || !"STAGED".equals(artifact.artifactState())
        || artifact.stateEpoch() <= 0
        || !Objects.equals(artifact.lastWorkflowId(), request.publishWorkflowId())) {
      throw new IllegalStateException("PUBLISH_ATTEMPT_STAGED_ARTIFACT_SCOPE_MISMATCH");
    }

    ExportedAssetManifest candidate =
        versionAssetArtifactService.getExportCandidate(request.tenantId(), attempt.getVersionId());
    if (artifact.manifestHash() == null) {
      if (!artifact.exportedManifestAssetKeys().isEmpty() || candidate != null) {
        throw new IllegalStateException("PUBLISH_ATTEMPT_STAGED_CANDIDATE_MISMATCH");
      }
      return null;
    }
    if (candidate == null
        || candidate.manifestSchemaVersion() != 1
        || !Objects.equals(candidate.manifestHash(), artifact.manifestHash())
        || !Objects.equals(
            candidate.requiredManifestAssetKeys(), artifact.exportedManifestAssetKeys())) {
      throw new IllegalStateException("PUBLISH_ATTEMPT_STAGED_CANDIDATE_MISMATCH");
    }
    return candidate;
  }

  private void requireExactBundleEvidence(
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      Version version,
      PublishedReleaseBundleDto bundle) {
    requireExactCommittedBundleIdentity(request, attempt, bundle);
    if (!Objects.equals(version.getTenantId(), bundle.tenantId())
        || version.getVersionNumber() != bundle.versionNumber()
        || version.isScriptOnly()) {
      throw new IllegalStateException("PUBLISH_ATTEMPT_BUNDLE_SCOPE_MISMATCH");
    }
  }

  private void requireExactCommittedBundleIdentity(
      PublishWorkflowRequest request, PublishAttempt attempt, PublishedReleaseBundleDto bundle) {
    if (!Objects.equals(bundle.tenantId(), request.tenantId())
        || !Objects.equals(bundle.versionId(), attempt.getVersionId())
        || bundle.versionNumber() != attempt.getVersionNumber()
        || !Objects.equals(bundle.publishWorkflowId(), request.publishWorkflowId())
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
    // Retained terminal history is not backfilled into a complete asset attestation. For a new
    // proof-bearing bundle, recovery must compare every original artifact/schema field too.
    if (bundle.manifestSchemaVersion() != null) {
      ExportedAssetManifest expected =
          new ExportedAssetManifest(
              bundle.manifestHash(),
              bundle.manifestSchemaVersion(),
              bundle.requiredManifestAssetKeys(),
              bundle.artifactDigests());
      if (!Objects.equals(
          expected,
          versionAssetArtifactService.getExportCandidate(
              request.tenantId(), attempt.getVersionId()))) {
        throw new IllegalStateException("PUBLISH_ATTEMPT_ARTIFACT_SCOPE_MISMATCH");
      }
    }
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

  private VersionAssetArtifactStateDto readVersionAssetArtifactState(
      String tenantId, long versionId) {
    return versionAssetArtifactService.findState(tenantId, versionId).orElse(null);
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

  static final class PendingReconciliationException extends IllegalStateException {
    PendingReconciliationException(String message) {
      super(message);
    }

    PendingReconciliationException(String message, RuntimeException cause) {
      super(message, cause);
    }
  }

  private record PublicationReadback(
      ReadbackState state,
      Version version,
      PublishedReleaseBundleDto bundle,
      VersionAssetArtifactStateDto artifact,
      ExportedAssetManifest candidate) {
    private static PublicationReadback absent(Version version) {
      return new PublicationReadback(ReadbackState.ABSENT, version, null, null, null);
    }

    private static PublicationReadback partial() {
      return new PublicationReadback(ReadbackState.PARTIAL, null, null, null, null);
    }

    private static PublicationReadback stagedNoRelease(
        Version version, VersionAssetArtifactStateDto artifact, ExportedAssetManifest candidate) {
      return new PublicationReadback(
          ReadbackState.STAGED_NO_RELEASE, version, null, artifact, candidate);
    }

    private static PublicationReadback complete(
        Version version, PublishedReleaseBundleDto bundle, VersionAssetArtifactStateDto artifact) {
      return new PublicationReadback(ReadbackState.COMPLETE, version, bundle, artifact, null);
    }

    private boolean isAbsent() {
      return state == ReadbackState.ABSENT;
    }

    private boolean isPartial() {
      return state == ReadbackState.PARTIAL;
    }

    private boolean isStagedNoRelease() {
      return state == ReadbackState.STAGED_NO_RELEASE;
    }

    private boolean isComplete() {
      return state == ReadbackState.COMPLETE;
    }
  }

  private enum ReadbackState {
    ABSENT,
    STAGED_NO_RELEASE,
    PARTIAL,
    COMPLETE
  }

  private AuthoredDraftPublishSelection requireSelectedIntent(PublishWorkflowRequest request) {
    if (request.intent() == null) {
      throw new IllegalStateException(
          "PUBLISH_SELECTION_REQUIRED: retained terminal readback is the only unselected operation");
    }
    AuthoredDraftPublishSelection selection =
        authoredSelections
            .readByPublishRequest(request.intent().canonicalTenantId(), request.publishRequestId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "PUBLISH_SELECTION_REQUIRED: exact durable selection is absent"))
            .selection();
    if (!selection.intent().equals(request.intent())
        || !selection.target().gameDesignVersionTenantKey().equals(request.tenantId())
        || !selection.intent().notes().equals(request.notes())
        || !selection.intent().publishRequestId().equals(request.publishRequestId())) {
      throw new IllegalStateException(
          "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: exact original selection intent differs");
    }
    return selection;
  }

  private void requireCanonicalOwnerPublicationCarrier(PublishWorkflowRequest request) {
    if (request.intent() != null) {
      // Existing digest clients carry private tenant keys and numeric Version selectors. They
      // cannot authenticate/freeze/materialize this selected canonical UUID commit. Do not
      // rewrite its workflow identity to fit that incomplete owner boundary.
      throw new IllegalStateException(
          "PUBLISH_OWNER_CARRIER_UNAVAILABLE: canonical selected-commit owner freeze and materialization are required");
    }
  }

  private void requireSelectedDraftEpoch(PublishWorkflowRequest request, Version version) {
    AuthoredDraftPublishSelection selection = requireSelectedIntent(request);
    if (!Objects.equals(
        version.getVersionStateEpoch(),
        Long.parseLong(selection.intent().expectedVersionStateEpoch()))) {
      throw new IllegalStateException(
          "VERSION_STATE_EPOCH_STALE: Draft differs from its exact original publication selection");
    }
  }

  private void assertSelectedCommit(
      PublishWorkflowRequest request, List<PublishParticipantDigestDto> digests) {
    if (request.intent() == null) {
      return; // Historical terminal readback does not invent missing selection evidence.
    }
    AuthoredDraftPublishSelection selection = requireSelectedIntent(request);
    if (digests == null
        || digests.stream()
            .anyMatch(
                digest ->
                    digest == null
                        || !selection
                            .selectedCommit()
                            .commitId()
                            .toString()
                            .equals(digest.appliedCommitId()))) {
      throw new PublishGateFailureException(
          PublishGateFailureCode.APPLIED_COMMIT_MISMATCH,
          "Every participant must report the exact selected authored Draft commit");
    }
  }

  private PublishedReleaseBundleDto readPublishedReleaseBundle(String tenantId, long versionId) {
    return publishedReleaseBundleService
        .findPublishedReleaseBundle(tenantId, versionId)
        .orElse(null);
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

  private Version requireTenantVersion(String tenantId, long versionId) {
    return versionRepository
        .findByTenantIdAndId(tenantId, versionId)
        .orElseThrow(() -> new IllegalArgumentException("version not found"));
  }

  private String emptyIfNull(String value) {
    return value == null ? "" : value;
  }
}
