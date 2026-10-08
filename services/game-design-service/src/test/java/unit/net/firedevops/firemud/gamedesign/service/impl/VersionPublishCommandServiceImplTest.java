package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository.SelectionSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.PublicationEvidence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.VisibilityFence;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
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
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.RecordedParticipantDigestService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class VersionPublishCommandServiceImplTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String ARTIFACT_DIGEST = "sha256:" + "b".repeat(64);
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("82345678-1234-4234-8234-123456789abc");
  @Mock private VersionRepository versionRepository;
  @Mock private GameRepository gameRepository;
  @Mock private PublishAttemptRepository publishAttemptRepository;
  @Mock private AssetExportService assetExportService;
  @Mock private PublishAttemptService publishAttemptService;
  @Mock private PublishGateService publishGateService;
  @Mock private ControlPlaneDigestService controlPlaneDigestService;
  @Mock private VersionAssetArtifactService versionAssetArtifactService;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;
  @Mock private RecordedParticipantDigestService recordedParticipantDigestService;
  @Mock private AuthoredDraftPublishSelectionRepository authoredSelections;
  @Mock private WorldPublishedStartLocationEvidence capturedWorldPublicationEvidence;

  private VersionPublishCommandServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    when(publishedReleaseBundleService.findPublishedReleaseBundle(
            any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState(any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());
    when(publishAttemptRepository.requirePublicationPending(any(PublishAttempt.class)))
        .thenReturn(capturedWorldPublicationEvidence);
    when(versionAssetArtifactService.stageExport(
            any(String.class), any(Long.class), any(Integer.class), any(String.class)))
        .thenAnswer(
            invocation ->
                new VersionAssetArtifactStateDto(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    "STAGED",
                    1L,
                    null,
                    invocation.getArgument(3),
                    null,
                    null,
                    LocalDateTime.now(),
                    List.of()));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              try {
                return ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
              } catch (RuntimeException ex) {
                throw new PublishAttemptService.FullVersionTransactionException(ex);
              }
            })
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());
    VersionMapper mapper = Mappers.getMapper(VersionMapper.class);
    service =
        new VersionPublishCommandServiceImpl(
            versionRepository,
            gameRepository,
            publishAttemptRepository,
            mapper,
            assetExportService,
            publishAttemptService,
            publishGateService,
            controlPlaneDigestService,
            versionAssetArtifactService,
            publishedReleaseBundleService,
            recordedParticipantDigestService,
            authoredSelections);
  }

  @Test
  void freshLegacyFullVersionRequestIsDeniedByCanonicalPublicationGuard() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";

    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () -> service.publishFullVersion("tenant-1", "notes", "workflow-1", workflowId));
    verify(authoredSelections, never()).reserve(any(PublishIntent.class));
    verify(publishAttemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
    verify(versionRepository, never()).save(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
  }

  @Test
  void selectedPublishRequestRequiresDurableOriginalSelection() {
    PublishIntent intent = selectedIntent();
    String workflowId =
        TemporalVersionPublishOrchestrator.workflowId(
            intent.canonicalTenantId().toString(), intent.publishRequestId());
    PublishWorkflowRequest request =
        new PublishWorkflowRequest(
            "tenant-1", intent.notes(), intent.publishRequestId(), workflowId, intent);

    when(authoredSelections.readByPublishRequest(
            intent.canonicalTenantId(), intent.publishRequestId()))
        .thenReturn(Optional.empty());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.reconcileFullVersionPublish(request));

    assertTrue(thrown.getMessage().startsWith("PUBLISH_SELECTION_REQUIRED"));
    verify(authoredSelections, never()).reserve(any(PublishIntent.class));
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
    verify(publishAttemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(versionRepository, never()).save(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void legacyPendingAttemptIsDeniedBeforeReadbackOrMutation() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    attempt.setRequestDigest(null);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
    verify(authoredSelections, never()).reserve(any(PublishIntent.class));
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(versionRepository, never()).save(any(Version.class));
  }

  @Test
  void freshSelectedRequestIsDeniedBeforeReservationOrPublicationMutation() {
    SelectionSnapshot reservation = selectedReservation();
    PublishWorkflowRequest request = selectedRequest(reservation);
    when(authoredSelections.readByPublishRequest(
            request.intent().canonicalTenantId(), request.publishRequestId()))
        .thenReturn(Optional.of(reservation));
    when(publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()))
        .thenReturn(Optional.empty());

    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () -> service.reconcileFullVersionPublish(request));
    verify(authoredSelections, never()).reserve(any(PublishIntent.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
    verify(versionRepository, never()).save(any(Version.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void pendingSelectedRequestIsDeniedBeforePublicationMutation() {
    SelectionSnapshot reservation = selectedReservation();
    PublishWorkflowRequest request = selectedRequest(reservation);
    PublishAttempt attempt = selectedAttempt(reservation, request, 1);
    when(authoredSelections.readByPublishRequest(
            request.intent().canonicalTenantId(), request.publishRequestId()))
        .thenReturn(Optional.of(reservation));
    when(publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()))
        .thenReturn(Optional.of(attempt));

    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () -> service.reconcileFullVersionPublish(request));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never()).markFullVersionSucceeded(any(String.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(publishAttemptRepository, never())
        .sealPublication(any(PublishAttempt.class), org.mockito.ArgumentMatchers.anyBoolean());
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
    verify(authoredSelections, never()).reserve(any(PublishIntent.class));
    verify(versionRepository, never()).save(any(Version.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void legacyFreshRequestDoesNotExportOrDeleteCandidateAssets() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";

    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () -> service.publishFullVersion("tenant-1", "notes", "workflow-1", workflowId));

    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
    verify(publishAttemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
    verify(versionRepository, never()).save(any(Version.class));
  }

  @Test
  void succeededReplayUsesStoredBundleAfterLifecycleDriftWithoutWritesOrGates() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.RETIRED);
    List<PublishParticipantDigestDto> driftedParticipantDigests =
        List.of(
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE",
                "wrong-version",
                "wrong-scope",
                "changed",
                99,
                null,
                null));
    PublishedReleaseBundleDto bundle =
        publishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of("manifest.json"),
            driftedParticipantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now());
    VersionAssetArtifactStateDto driftedArtifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "PURGED",
            8L,
            "different-manifest-hash",
            "different-workflow",
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.of(driftedArtifact));

    PublishWorkflowSnapshot snapshot =
        service.reconcileFullVersionPublish(
            new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

    assertEquals("SUCCEEDED", snapshot.status());
    verify(publishedReleaseBundleService).findPublishedReleaseBundle("tenant-1", 10L);
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(versionAssetArtifactService, never()).findState(any(String.class), any(Long.class));
    verify(gameRepository, never()).findByTenantIdForUpdate(any(String.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(recordedParticipantDigestService, never())
        .recordVerifiedDigests(
            any(String.class), any(PublishType.class), any(String.class), any(List.class));
    verify(recordedParticipantDigestService, never())
        .assertMatchesRecordedDigests(any(String.class), any(PublishType.class), any(List.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never()).markFullVersionSucceeded(any(String.class));
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
  }

  @Test
  void succeededReplayRejectsMismatchedCommittedBundleIdentity() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    PublishedReleaseBundleDto bundle =
        publishedReleaseBundleDto(
            1L,
            "tenant-1",
            11L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of("manifest.json"),
            participantDigests(),
            "generation-revision",
            false,
            null,
            LocalDateTime.now());
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(versionAssetArtifactService, never()).findState(any(String.class), any(Long.class));
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));
    verify(recordedParticipantDigestService, never())
        .recordVerifiedDigests(
            any(String.class), any(PublishType.class), any(String.class), any(List.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
  }

  @Test
  void succeededReplayRejectsAbsentCommittedBundle() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(versionAssetArtifactService, never()).findState(any(String.class), any(Long.class));
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));
    verify(recordedParticipantDigestService, never())
        .recordVerifiedDigests(
            any(String.class), any(PublishType.class), any(String.class), any(List.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
  }

  @Test
  void succeededReplayFailsClosedWhenBundleReadIsUncertain() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenThrow(new IllegalStateException("bundle read unavailable"));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(versionAssetArtifactService, never()).findState(any(String.class), any(Long.class));
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));
    verify(recordedParticipantDigestService, never())
        .recordVerifiedDigests(
            any(String.class), any(PublishType.class), any(String.class), any(List.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
  }

  @Test
  void legacySucceededAttemptReplaysWithoutBackfillOrMutableReads() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    attempt.setRequestDigest(null);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    PublishedReleaseBundleDto bundle =
        publishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of("manifest.json"),
            participantDigests(),
            "generation-revision",
            false,
            null,
            LocalDateTime.now());
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));

    PublishWorkflowSnapshot snapshot =
        service.reconcileFullVersionPublish(
            new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

    assertEquals("SUCCEEDED", snapshot.status());
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(versionAssetArtifactService, never()).findState(any(String.class), any(Long.class));
    verify(gameRepository, never()).findByTenantIdForUpdate(any(String.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));
    verify(recordedParticipantDigestService, never())
        .recordVerifiedDigests(
            any(String.class), any(PublishType.class), any(String.class), any(List.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
  }

  @Test
  void legacyFailedAttemptReplaysAfterItsDraftWasDeleted() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.FAILED, 10L, 1, workflowId);
    attempt.setRequestDigest(null);
    attempt.setFailureCode("PUBLISH_FAILED");
    attempt.setFailureMessage("legacy publish failure");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    PublishWorkflowSnapshot snapshot =
        service.reconcileFullVersionPublish(
            new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

    assertEquals("FAILED", snapshot.status());
    assertEquals("PUBLISH_FAILED", snapshot.failureCode());
    assertEquals("legacy publish failure", snapshot.failureMessage());
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(gameRepository, never()).findByTenantIdForUpdate(any(String.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
  }

  @Test
  void selectedFailedReplayRequiresTheExactNoPublicationSeal() {
    SelectionSnapshot reservation = selectedReservation();
    PublishWorkflowRequest request = selectedRequest(reservation);
    PublishAttempt attempt = selectedAttempt(reservation, request, 1);
    attempt.setStatus(PublishAttemptStatus.FAILED);
    when(authoredSelections.readByPublishRequest(
            request.intent().canonicalTenantId(), request.publishRequestId()))
        .thenReturn(Optional.of(reservation));
    when(publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()))
        .thenReturn(Optional.of(attempt));

    assertEquals("FAILED", service.reconcileFullVersionPublish(request).status());
    verify(publishAttemptRepository).requireNoPublicationOperation(attempt);
    org.mockito.Mockito.doThrow(new IllegalStateException("PUBLICATION_OPERATION_SEALED_OR_CHANGED"))
        .when(publishAttemptRepository)
        .requireNoPublicationOperation(attempt);
    assertThrows(
        IllegalStateException.class, () -> service.reconcileFullVersionPublish(request));
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
  }

  @Test
  void nonCanonicalLegacyFullWorkflowFailsBeforeCompatibilityRewrite() {
    PublishAttempt attempt =
        fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, "legacy-random-workflow");
    attempt.setId(101L);
    attempt.setRequestDigest(null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest(
                    "tenant-1", "notes", "workflow-1", "legacy-random-workflow")));

    verify(publishAttemptRepository, never()).findByPublishWorkflowId(any(String.class));
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
  }

  @Test
  void missingLegacyPublishRequestIdIsRecoveredFromCanonicalWorkflowId() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.FAILED, 10L, 1, workflowId);
    attempt.setRequestDigest(null);
    attempt.setFailureCode("PUBLISH_FAILED");
    attempt.setFailureMessage("legacy publish failure");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    PublishWorkflowSnapshot snapshot =
        service.reconcileFullVersionPublish(
            new PublishWorkflowRequest("tenant-1", "notes", null, workflowId));

    assertEquals("FAILED", snapshot.status());
    assertEquals("PUBLISH_FAILED", snapshot.failureCode());
    verify(publishAttemptRepository).findByPublishWorkflowId(workflowId);
  }

  @Test
  void missingLegacyPublishRequestIdDoesNotCrossTenantWorkflowPrefix() {
    String workflowId = "publish:tenant-2:publish-request:workflow-1";

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", null, workflowId)));

    verify(publishAttemptRepository, never()).findByPublishWorkflowId(any(String.class));
  }

  @Test
  void mismatchedRequestDigestCannotReplaySucceededAttempt() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    attempt.setRequestDigest("0".repeat(64));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    verify(publishedReleaseBundleService, never())
        .findPublishedReleaseBundle(any(String.class), any(Long.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void reflectiveUnitHarnessReconcilesSelectedExistingVersionWithCapturedOperation() {
    SelectedPublishFixture fixture = selectedPublishFixture(8);
    Version version = fixture.version();
    PublishWorkflowRequest request = fixture.request();
    PublishAttempt attempt = fixture.attempt();
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    ExportedAssetManifest exportedManifest = exportedManifest(List.of("logo.png", "manifest.json"));
    VersionAssetArtifactStateDto staged = stagedArtifact(request, version);
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.empty(), Optional.of(staged), Optional.of(staged));
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 8, request.publishWorkflowId()))
        .thenReturn(staged);
    when(assetExportService.exportAssets("tenant-1", 8)).thenReturn(exportedManifest);
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests);
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "10", "version:10", "digest-1", 1));
    when(versionAssetArtifactService.markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class)))
        .thenReturn(
            new VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                8,
                "EXPORTED_UNATTESTED",
                2L,
                MANIFEST_HASH,
                request.publishWorkflowId(),
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png", "manifest.json")));
    when(publishedReleaseBundleService.createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            same(capturedWorldPublicationEvidence)))
        .thenReturn(
            publishedReleaseBundleDto(
                1L,
                "tenant-1",
                10L,
                8,
                "v1",
                request.publishWorkflowId(),
                MANIFEST_HASH,
                List.of("logo.png", "manifest.json"),
                participantDigests,
                "genrev-tenant-1-10",
                false,
                null,
                LocalDateTime.now()));
    when(versionAssetArtifactService.markPublished(
            any(String.class),
            any(Long.class),
            any(Long.class),
            any(String.class),
            any(String.class)))
        .thenReturn(
            new VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                8,
                "PUBLISHED",
                3L,
                MANIFEST_HASH,
                request.publishWorkflowId(),
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png", "manifest.json")));

    PublishWorkflowSnapshot snapshot =
        reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request);

    assertEquals("SUCCEEDED", snapshot.status());
    assertEquals(10L, snapshot.versionId());
    assertEquals(VersionLifecycleState.PUBLISHED, version.getVersionState());
    assertEquals(2L, version.getVersionStateEpoch());
    verify(versionRepository, never()).findTopByTenantIdOrderByVersionNumberDesc("tenant-1");
    verify(versionRepository).save(version);
    verify(publishAttemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(publishAttemptService)
        .recordFullVersionParticipantDigests(request.publishWorkflowId(), participantDigests);
    verify(publishAttemptRepository).requirePublicationPending(attempt);
    verify(publishAttemptRepository).sealPublication(attempt, true);
    verify(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            same(capturedWorldPublicationEvidence));
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class));

    InOrder publishOrder =
        inOrder(
            publishGateService,
            recordedParticipantDigestService,
            versionAssetArtifactService,
            assetExportService,
            publishedReleaseBundleService,
            publishAttemptService,
            publishAttemptRepository);
    publishOrder
        .verify(publishGateService)
        .assertGatePassed(
            any(VersionDto.class), org.mockito.ArgumentMatchers.eq(participantDigests));
    publishOrder
        .verify(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq(PublishType.FULL_VERSION),
            org.mockito.ArgumentMatchers.eq(participantDigests));
    publishOrder
        .verify(versionAssetArtifactService)
        .stageExport("tenant-1", 10L, 8, request.publishWorkflowId());
    publishOrder.verify(assetExportService).exportAssets("tenant-1", 8);
    publishOrder
        .verify(publishAttemptService)
        .recordFullVersionParticipantDigests(
            org.mockito.ArgumentMatchers.eq(request.publishWorkflowId()),
            org.mockito.ArgumentMatchers.eq(participantDigests));
    publishOrder
        .verify(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            same(capturedWorldPublicationEvidence));
    publishOrder.verify(publishAttemptRepository).sealPublication(attempt, true);
  }

  @Test
  void publishFullVersionPropagatesTypedPublishGateFailures() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"))
        .when(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(any(String.class), any(), any(List.class));

    PublishWorkflowSnapshot snapshot =
        reflectiveUnitHarnessReconcileSelectedPublicationMechanics(fixture.request());

    assertEquals("FAILED", snapshot.status());
    assertEquals(
        PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH.name(), snapshot.failureCode());
    verify(publishAttemptService)
        .markFullVersionFailed(
            org.mockito.ArgumentMatchers.eq(fixture.request().publishWorkflowId()),
            org.mockito.ArgumentMatchers.eq("RECORDED_CONTENT_DIGEST_MISMATCH"),
            org.mockito.ArgumentMatchers.eq("recorded digest mismatch"));
    verify(publishAttemptRepository).sealPublication(fixture.attempt(), false);
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void publishFullVersionGateRejectionDoesNotRecordDigestsOrExportAssets() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests);
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, "gate rejected"))
        .when(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));

    PublishWorkflowSnapshot snapshot =
        reflectiveUnitHarnessReconcileSelectedPublicationMechanics(fixture.request());

    assertEquals("FAILED", snapshot.status());
    assertEquals(
        PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH.name(), snapshot.failureCode());
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishAttemptRepository).sealPublication(fixture.attempt(), false);
  }

  @Test
  void bundleFailureAfterExternalExportRemainsPendingWithoutDeletingSharedAssets() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    VersionAssetArtifactStateDto staged = stagedArtifact(request, fixture.version());
    VersionAssetArtifactStateDto exportedState =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "EXPORTED_UNATTESTED",
            2L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(
            Optional.empty(), Optional.of(staged), Optional.of(staged), Optional.of(exportedState));
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, request.publishWorkflowId()))
        .thenReturn(staged);
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "10", "version:10", "digest", 1));
    when(versionAssetArtifactService.markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class)))
        .thenReturn(exportedState);
    org.mockito.Mockito.doThrow(new IllegalStateException("bundle commit outcome is unknown"))
        .when(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            same(capturedWorldPublicationEvidence));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("incomplete evidence"));
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(assetExportService).exportAssets("tenant-1", 1);
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(publishAttemptRepository, never())
        .sealPublication(any(PublishAttempt.class), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.Code.class,
      names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void transientParticipantReadLeavesSelectedFullAttemptPending(Status.Code statusCode) {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenThrow(new StatusRuntimeException(Status.fromCode(statusCode)));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(fixture.request()));

    assertTrue(thrown.getMessage().contains("temporarily unavailable"));
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(publishAttemptRepository, never())
        .sealPublication(any(PublishAttempt.class), org.mockito.ArgumentMatchers.anyBoolean());
    verify(versionAssetArtifactService, never())
        .stageExport(any(String.class), any(Long.class), any(Integer.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void uncertainAssetExportRemainsPendingForExactRetry() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    VersionAssetArtifactStateDto staged = stagedArtifact(request, fixture.version());
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.empty(), Optional.of(staged));
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, request.publishWorkflowId()))
        .thenReturn(staged);
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenThrow(new AssetExportOutcomePendingException("exact object readback is unavailable"));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("asset export outcome remains unresolved"));
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void uncertainAssetStagingRemainsPendingBeforeAnyExternalExportOrFinalization() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, request.publishWorkflowId()))
        .thenThrow(new IllegalStateException("stage commit outcome is unknown"));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("asset export intent commit requires exact readback"));
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(versionAssetArtifactService)
        .stageExport("tenant-1", 10L, 1, request.publishWorkflowId());
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(versionAssetArtifactService, never())
        .markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class));
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            any(WorldPublishedStartLocationEvidence.class));
    verify(versionAssetArtifactService, never())
        .markFailed(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(String.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void readbackWithArtifactButNoBundleRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    String workflowId = request.publishWorkflowId();
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "EXPORTED_UNATTESTED",
            1L,
            MANIFEST_HASH,
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("incomplete"));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void pendingAttemptStillReconcilesCompleteReadbackThroughCurrentGates() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    String workflowId = request.publishWorkflowId();
    PublishAttempt attempt = fixture.attempt();
    Version version = fixture.version();
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(2L);
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    PublishedReleaseBundleDto bundle =
        publishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of("manifest.json"),
            participantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now());
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "PUBLISHED",
            2L,
            MANIFEST_HASH,
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(
            new ExportedAssetManifest(
                MANIFEST_HASH,
                1,
                List.of("manifest.json"),
                artifactDigests(List.of("manifest.json"))));
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));

    PublishWorkflowSnapshot snapshot =
        reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request);

    assertEquals("SUCCEEDED", snapshot.status());
    verify(publishGateService)
        .assertGatePassed(
            any(VersionDto.class), org.mockito.ArgumentMatchers.eq(participantDigests));
    verify(recordedParticipantDigestService)
        .assertMatchesRecordedDigests("tenant-1", PublishType.FULL_VERSION, participantDigests);
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(
            "tenant-1", PublishType.FULL_VERSION, workflowId, participantDigests);
    verify(publishAttemptService).markFullVersionSucceeded(workflowId);
    verify(publishAttemptRepository).sealPublication(attempt, true);
  }

  @Test
  void selectedAttemptWithMismatchedVersionEvidenceIsNotRewritten() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    PublishAttempt attempt = fixture.attempt();
    Version mismatchedVersion = fullVersion(10L, 2, VersionLifecycleState.DRAFT);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(mismatchedVersion));

    assertThrows(
        IllegalStateException.class,
        () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    assertEquals(fixture.reservation().selection().digest(), attempt.getRequestDigest());
  }

  @Test
  void selectedFullAttemptWithMixedScopeIsNotRebound() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    PublishAttempt attempt = fixture.attempt();
    attempt.setBaseVersionId(3L);

    assertThrows(
        IllegalStateException.class,
        () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    assertEquals(fixture.reservation().selection().digest(), attempt.getRequestDigest());
  }

  @Test
  void fullAttemptValidationRejectsMixedScriptPatchScopeEvenWithDigest() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    PublishAttempt attempt = fixture.attempt();
    attempt.setScriptPatchVersion("legacy-patch");

    assertThrows(
        IllegalStateException.class,
        () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(publishedReleaseBundleService, never())
        .findPublishedReleaseBundle(any(String.class), any(Long.class));
  }

  @Test
  void postBundleFailureAfterRollbackLeavesAttemptPendingWithoutCleaningAssets() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    Version version = fixture.version();
    String workflowId = request.publishWorkflowId();
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    VersionAssetArtifactStateDto staged = stagedArtifact(request, version);
    VersionAssetArtifactStateDto exportedState =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "EXPORTED_UNATTESTED",
            2L,
            MANIFEST_HASH,
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    PublishedReleaseBundleDto bundle =
        publishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of("manifest.json"),
            participantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now());
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(
            Optional.empty(), Optional.of(staged), Optional.of(staged), Optional.of(exportedState));
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenReturn(staged);
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests);
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "10", "version:10", "digest", 1));
    when(versionAssetArtifactService.markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class)))
        .thenReturn(
            new VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                1,
                "EXPORTED_UNATTESTED",
                1L,
                MANIFEST_HASH,
                workflowId,
                null,
                null,
                LocalDateTime.now(),
                List.of("manifest.json")));
    when(publishedReleaseBundleService.createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            same(capturedWorldPublicationEvidence)))
        .thenReturn(bundle);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              // The production transaction rolls managed entity changes back before readback.
              version.setVersionState(VersionLifecycleState.DRAFT);
              version.setVersionStateEpoch(1L);
              throw new IllegalStateException("version finalization failed");
            })
        .when(versionRepository)
        .save(any(Version.class));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("incomplete evidence"));
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void finalizationFailureWithFailedReadbackLeavesAttemptPending() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    String workflowId = request.publishWorkflowId();
    VersionAssetArtifactStateDto staged = stagedArtifact(request, fixture.version());
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.empty(), Optional.of(staged));
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenReturn(staged);
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    IllegalStateException operationFailure =
        new IllegalStateException("version finalization failed");
    IllegalStateException readFailure = new IllegalStateException("publication read failed");
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty())
        .thenThrow(readFailure);
    org.mockito.Mockito.doThrow(
            new PublishAttemptService.FullVersionTransactionException(operationFailure))
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("readback failed"));
    assertEquals(readFailure, thrown.getCause());
    assertEquals(1, readFailure.getSuppressed().length);
    assertEquals(operationFailure, readFailure.getSuppressed()[0]);
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void ambiguousFinalizationLeavesPendingAttemptAndDoesNotCleanAssets() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    String workflowId = request.publishWorkflowId();
    VersionAssetArtifactStateDto staged = stagedArtifact(request, fixture.version());
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.empty(), Optional.of(staged), Optional.of(staged));
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenReturn(staged);
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new IllegalStateException("commit outcome unknown"))
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    assertThrows(
        IllegalStateException.class,
        () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void ambiguousFinalizationWithFailedReadbackLeavesAttemptPending() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    String workflowId = request.publishWorkflowId();
    VersionAssetArtifactStateDto staged = stagedArtifact(request, fixture.version());
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.empty(), Optional.of(staged));
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenReturn(staged);
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    IllegalStateException ambiguousCommit = new IllegalStateException("commit outcome unknown");
    IllegalStateException readFailure = new IllegalStateException("publication read failed");
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty())
        .thenThrow(readFailure);
    org.mockito.Mockito.doThrow(ambiguousCommit)
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("readback failed"));
    assertEquals(readFailure, thrown.getCause());
    assertEquals(1, readFailure.getSuppressed().length);
    assertEquals(ambiguousCommit, readFailure.getSuppressed()[0]);
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void failureMarkingCommitUncertaintyLeavesAttemptPendingAndDoesNotCleanAssets() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"))
        .when(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
              throw new IllegalStateException("failure-marking commit outcome unknown");
            })
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("failure marking commit outcome is unknown"));
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService)
        .markFullVersionFailed(
            request.publishWorkflowId(),
            "RECORDED_CONTENT_DIGEST_MISMATCH",
            "recorded digest mismatch");
    verify(publishAttemptRepository).sealPublication(fixture.attempt(), false);
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  private PublishAttempt fullAttempt(
      PublishAttemptStatus status, long versionId, int versionNumber, String workflowId) {
    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId("tenant-1");
    attempt.setVersionId(versionId);
    attempt.setVersionNumber(versionNumber);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setPublishWorkflowId(workflowId);
    attempt.setStatus(status);
    attempt.setRequestDigest(
        PublicationDigestRequestBinding.full("tenant-1", String.valueOf(versionId), "workflow-1")
            .requestDigest());
    return attempt;
  }

  private PublishIntent selectedIntent() {
    return selectedReservation().selection().intent();
  }

  private SelectionSnapshot selectedReservation() {
    UUID requestId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    UUID commitId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    TargetProof target =
        new TargetProof(
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            10L,
            "tenant-1",
            1L,
            "tenant-1",
            "NEW_GAME_ROW");
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            target,
            requestId,
            commitId,
            "base-commit-1",
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.fromString("55555555-5555-4555-8555-555555555555"),
                    Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "1")));
    PublishIntent intent =
        new PublishIntent(
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            "workflow-1",
            "1",
            "notes",
            requestId,
            commitId,
            binding.digest());
    VisibilityFence fence =
        new VisibilityFence(
            target,
            requestId,
            commitId,
            binding.digest(),
            "[]",
            OffsetDateTime.parse("2026-10-08T00:00:00Z"));
    AuthoredDraftPublishSelection selection =
        AuthoredDraftPublishSelection.capture(
            intent, target, new PublicationEvidence(binding, fence));
    // This is an existing synchronized source selection fixture only. It carries no Account
    // authorization or authenticated World publication freeze.
    return new SelectionSnapshot(selection, OffsetDateTime.parse("2026-10-08T00:00:00Z"));
  }

  private PublishWorkflowRequest selectedRequest(SelectionSnapshot reservation) {
    PublishIntent intent = reservation.selection().intent();
    return new PublishWorkflowRequest(
        "tenant-1",
        intent.notes(),
        intent.publishRequestId(),
        TemporalVersionPublishOrchestrator.workflowId(
            intent.canonicalTenantId().toString(), intent.publishRequestId()),
        intent);
  }

  private PublishAttempt selectedAttempt(
      SelectionSnapshot reservation, PublishWorkflowRequest request, int versionNumber) {
    PublishAttempt attempt =
        fullAttempt(
            PublishAttemptStatus.PENDING,
            reservation.selection().target().gameDesignVersionRowId(),
            versionNumber,
            request.publishWorkflowId());
    attempt.setRequestDigest(reservation.selection().digest());
    return attempt;
  }

  private SelectedPublishFixture selectedPublishFixture(int versionNumber) {
    SelectionSnapshot reservation = selectedReservation();
    PublishWorkflowRequest request = selectedRequest(reservation);
    PublishAttempt attempt = selectedAttempt(reservation, request, versionNumber);
    Version version = fullVersion(10L, versionNumber, VersionLifecycleState.DRAFT);
    version.setNotes("notes");
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(authoredSelections.readByPublishRequest(
            request.intent().canonicalTenantId(), request.publishRequestId()))
        .thenReturn(Optional.of(reservation));
    when(publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()))
        .thenReturn(Optional.of(attempt));
    when(publishAttemptRepository.findByPublishWorkflowIdForUpdate(request.publishWorkflowId()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(versionRepository.findByTenantIdAndIdForUpdate("tenant-1", 10L))
        .thenReturn(Optional.of(version));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    return new SelectedPublishFixture(reservation, request, attempt, version);
  }

  private VersionAssetArtifactStateDto stagedArtifact(
      PublishWorkflowRequest request, Version version) {
    return new VersionAssetArtifactStateDto(
        "tenant-1",
        version.getId(),
        version.getVersionNumber(),
        "STAGED",
        1L,
        null,
        request.publishWorkflowId(),
        null,
        null,
        LocalDateTime.now(),
        List.of());
  }

  private PublishWorkflowSnapshot reflectiveUnitHarnessReconcileSelectedPublicationMechanics(
      PublishWorkflowRequest request) {
    try {
      Method mechanics =
          VersionPublishCommandServiceImpl.class.getDeclaredMethod(
              "reconcileSelectedPublicationMechanics", PublishWorkflowRequest.class);
      mechanics.setAccessible(true);
      return (PublishWorkflowSnapshot) mechanics.invoke(service, request);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      if (cause instanceof Error error) throw error;
      throw new AssertionError("selected publication mechanics failed", cause);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("selected publication mechanics harness is unavailable", exception);
    }
  }

  private Version fullVersion(long versionId, int versionNumber, VersionLifecycleState state) {
    Version version = new Version();
    version.setId(versionId);
    version.setTenantId("tenant-1");
    version.setVersionNumber(versionNumber);
    version.setVersionState(state);
    version.setVersionStateEpoch(state == VersionLifecycleState.PUBLISHED ? 2L : 1L);
    version.setUpdatedAt(LocalDateTime.now());
    return version;
  }

  private List<PublishParticipantDigestDto> participantDigests() {
    return List.of(
        new PublishParticipantDigestDto(
            "GAME_DESIGN_CONTROL_PLANE",
            "10",
            selectedIntent().selectedCommitId().toString(),
            "digest",
            1,
            null,
            null));
  }

  private record SelectedPublishFixture(
      SelectionSnapshot reservation,
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      Version version) {}

  private static ExportedAssetManifest exportedManifest(List<String> usageKeys) {
    return new ExportedAssetManifest(MANIFEST_HASH, 1, usageKeys, artifactDigests(usageKeys));
  }

  private static List<PublishedArtifactDigest> artifactDigests(List<String> usageKeys) {
    return usageKeys.stream()
        .map(
            usageKey ->
                new PublishedArtifactDigest(
                    usageKey,
                    "BINARY",
                    "artifacts/sha256/" + ARTIFACT_DIGEST.substring("sha256:".length()),
                    ARTIFACT_DIGEST,
                    "manifest.json".equals(usageKey) ? "application/json" : "image/png",
                    1))
        .toList();
  }

  private static PublishedReleaseBundleDto publishedReleaseBundleDto(
      Long id,
      String tenantId,
      Long versionId,
      int versionNumber,
      String attestationSchemaVersion,
      String publishWorkflowId,
      String manifestHash,
      List<String> requiredManifestAssetKeys,
      List<PublishParticipantDigestDto> participantDigests,
      String generationConfigRevision,
      boolean scriptOnly,
      String scriptPatchVersion,
      LocalDateTime publishedAt) {
    return new PublishedReleaseBundleDto(
        id,
        tenantId,
        versionId,
        versionNumber,
        attestationSchemaVersion,
        publishWorkflowId,
        manifestHash,
        requiredManifestAssetKeys,
        participantDigests,
        List.of(),
        generationConfigRevision,
        scriptOnly,
        scriptPatchVersion,
        publishedAt,
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        "opaque-release-reference-from-owner",
        1,
        artifactDigests(requiredManifestAssetKeys));
  }
}
