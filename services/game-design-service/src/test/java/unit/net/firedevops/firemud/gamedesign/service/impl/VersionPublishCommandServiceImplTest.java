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
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
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
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
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
import org.junit.jupiter.params.provider.ValueSource;
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

  @Mock
  private net.firedevops.firemud.gamedesign.publication.GameDesignWorldPublicationTerminalProducer
      worldProducer;

  @Mock
  private org.springframework.beans.factory.ObjectProvider<
          net.firedevops.firemud.gamedesign.publication.GameDesignWorldPublicationTerminalProducer>
      worldProducerProvider;

  private final AtomicBoolean ownerCommitActive = new AtomicBoolean();
  private VersionPublishCommandServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    when(worldProducerProvider.getIfAvailable()).thenReturn(worldProducer);
    when(worldProducer.reconcile(
            any(String.class), any(String.class), any(Long.class), any(String.class), any()))
        .thenAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  ownerCommitActive.get(), "World handoff ran before owner commit returned");
              return null;
            });
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
              ownerCommitActive.set(true);
              try {
                return ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
              } catch (RuntimeException ex) {
                throw new PublishAttemptService.FullVersionTransactionException(ex);
              } finally {
                ownerCommitActive.set(false);
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
            authoredSelections,
            worldProducerProvider);
  }

  @Test
  void freshLegacyFullVersionRequestIsDeniedByCanonicalPublicationGuard() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";

    VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException freshDenial =
        assertThrows(
            VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
            () ->
                service.reconcileFullVersionPublish(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));
    assertEquals(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.ERROR_CODE,
        freshDenial.errorCode());
    assertEquals(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.ERROR_CODE
            + ": "
            + VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException
                .SAFE_MESSAGE,
        freshDenial.getMessage());
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
    verify(publishGateService, never()).collectSelectedFullVersionParticipantDigests(
        any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class));
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

  @ParameterizedTest
  @EnumSource(
      value = VersionLifecycleState.class,
      names = {"PUBLISHED", "ACTIVE", "RETIRED"})
  void selectedSucceededReplayRevalidatesExactTerminalEvidenceWithoutWriting(
      VersionLifecycleState lifecycleState) throws Exception {
    SelectedSucceededReplayFixture fixture = selectedSucceededReplayFixture(lifecycleState);

    PublishWorkflowSnapshot snapshot = service.reconcileFullVersionPublish(fixture.request());

    assertEquals("SUCCEEDED", snapshot.status());
    verify(worldProducer)
        .reconcile(
            "tenant-1",
            fixture.request().publishWorkflowId(),
            snapshot.versionId(),
            fixture.attempt().getRequestDigest(),
            net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome
                .PUBLISHED);
    verify(publishAttemptRepository).requirePublishedOperation(fixture.attempt());
    verify(publishGateService)
        .assertGatePassed(
            any(VersionDto.class), org.mockito.ArgumentMatchers.eq(fixture.participantDigests()));
    verify(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(
            "tenant-1", PublishType.FULL_VERSION, fixture.participantDigests());
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    assertNoPublicationWrites();
  }

  @Test
  void selectedSucceededReplayRemainsPendingWithoutProducerOrAfterLostWorldReply()
      throws Exception {
    var fixture = selectedSucceededReplayFixture(VersionLifecycleState.PUBLISHED);
    when(worldProducerProvider.getIfAvailable()).thenReturn(null);
    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(fixture.request()));
    when(worldProducerProvider.getIfAvailable()).thenReturn(worldProducer);
    when(worldProducer.reconcile(
            any(String.class), any(String.class), any(Long.class), any(String.class), any()))
        .thenThrow(new IllegalStateException("lost World response"));
    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(fixture.request()));
    verify(publishAttemptRepository, never())
        .sealPublication(any(), org.mockito.ArgumentMatchers.anyBoolean());
    verify(publishAttemptService, never()).markFullVersionFailed(any(), any(), any());
  }

  @Test
  void selectedSucceededReplayRejectsMismatchedCommittedBundle() throws Exception {
    SelectedSucceededReplayFixture fixture =
        selectedSucceededReplayFixture(VersionLifecycleState.ACTIVE);
    String changedWorkflowId = fixture.request().publishWorkflowId() + ":changed";
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(withWorkflowId(fixture.bundle(), changedWorkflowId)));

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(fixture.request()));

    assertNoPublicationWrites();
  }

  @Test
  void selectedSucceededReplayRejectsMismatchedCommittedArtifact() throws Exception {
    SelectedSucceededReplayFixture fixture =
        selectedSucceededReplayFixture(VersionLifecycleState.RETIRED);
    VersionAssetArtifactStateDto artifact = fixture.artifact();
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(
            Optional.of(
                new VersionAssetArtifactStateDto(
                    artifact.tenantId(),
                    artifact.versionId(),
                    artifact.exportedVersionNumber(),
                    "PURGED",
                    artifact.stateEpoch(),
                    artifact.manifestHash(),
                    artifact.lastWorkflowId(),
                    artifact.lastErrorCode(),
                    artifact.lastErrorMessage(),
                    artifact.updatedAt(),
                    artifact.exportedManifestAssetKeys())));

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(fixture.request()));

    assertNoPublicationWrites();
  }

  @Test
  void selectedSucceededReplayRejectsPublicationGateMismatch() throws Exception {
    SelectedSucceededReplayFixture fixture =
        selectedSucceededReplayFixture(VersionLifecycleState.ACTIVE);
    org.mockito.Mockito.doThrow(
            new IllegalStateException("ISOLATED retained publication gate mismatch"))
        .when(publishGateService)
        .assertGatePassed(
            any(VersionDto.class), org.mockito.ArgumentMatchers.eq(fixture.participantDigests()));

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(fixture.request()));

    verify(publishGateService)
        .assertGatePassed(
            any(VersionDto.class), org.mockito.ArgumentMatchers.eq(fixture.participantDigests()));
    assertNoPublicationWrites();
  }

  @Test
  void selectedSucceededReplayRejectsRecordedDigestMismatch() throws Exception {
    SelectedSucceededReplayFixture fixture =
        selectedSucceededReplayFixture(VersionLifecycleState.RETIRED);
    org.mockito.Mockito.doThrow(
            new IllegalStateException("ISOLATED retained participant digest mismatch"))
        .when(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(
            "tenant-1", PublishType.FULL_VERSION, fixture.participantDigests());

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(fixture.request()));

    verify(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(
            "tenant-1", PublishType.FULL_VERSION, fixture.participantDigests());
    assertNoPublicationWrites();
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
    verify(worldProducer)
        .reconcile(
            "tenant-1",
            request.publishWorkflowId(),
            attempt.getVersionId(),
            reservation.selection().digest(),
            net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome
                .NO_PUBLICATION);
    verify(publishAttemptRepository).requireNoPublicationOperation(attempt);
    org.mockito.Mockito.doThrow(
            new IllegalStateException("PUBLICATION_OPERATION_SEALED_OR_CHANGED"))
        .when(publishAttemptRepository)
        .requireNoPublicationOperation(attempt);
    assertThrows(IllegalStateException.class, () -> service.reconcileFullVersionPublish(request));
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
  void selectedStagedArtifactWithMismatchedWorkflowRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            1L,
            null,
            "other-workflow",
            null,
            null,
            LocalDateTime.now(),
            List.of());

    assertSelectedStagedArtifactRemainsPartial(fixture, artifact);
  }

  @Test
  void selectedStagedArtifactWithMismatchedScopeRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            2,
            "STAGED",
            1L,
            null,
            fixture.request().publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of());

    assertSelectedStagedArtifactRemainsPartial(fixture, artifact);
  }

  @Test
  void selectedStagedArtifactForPublishedVersionRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    fixture.version().setVersionState(VersionLifecycleState.PUBLISHED);
    fixture.version().setVersionStateEpoch(2L);
    VersionAssetArtifactStateDto artifact = stagedArtifact(fixture.request(), fixture.version());

    assertSelectedStagedArtifactRemainsPartial(fixture, artifact);
  }

  @Test
  void selectedStagedArtifactWithMismatchedExportCandidateRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            1L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L)).thenReturn(null);

    assertSelectedStagedArtifactRemainsPartial(fixture, artifact);
    verify(versionAssetArtifactService).getExportCandidate("tenant-1", 10L);
  }

  @Test
  void selectedStagedArtifactWithExactPersistedCandidateRetriesWithoutTreatingItAsPublished() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    PublishAttempt attempt = fixture.attempt();
    VersionAssetArtifactStateDto stagedCandidate =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            2L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    ExportedAssetManifest candidate = exportedManifest(List.of("manifest.json"));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.of(stagedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L)).thenReturn(candidate);
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenThrow(new AssetExportOutcomePendingException("exact export readback is unavailable"));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("asset export outcome remains unresolved"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(versionAssetArtifactService).getExportCandidate("tenant-1", 10L);
    verify(assetExportService).exportAssets("tenant-1", 1);
    verify(versionAssetArtifactService, never())
        .stageExport(any(String.class), any(Long.class), any(Integer.class), any(String.class));
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
            same(capturedWorldPublicationEvidence));
    verify(publishAttemptService, never()).markFullVersionSucceeded(any(String.class));
    verify(publishAttemptRepository, never())
        .sealPublication(any(PublishAttempt.class), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void selectedStagedCandidateRetryAttestsTheExactFullManifestBeforeSuccess() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    PublishAttempt attempt = fixture.attempt();
    Version version = fixture.version();
    String workflowId = request.publishWorkflowId();
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    ExportedAssetManifest candidate = exportedManifest(List.of("manifest.json"));
    VersionAssetArtifactStateDto stagedCandidate =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            version.getId(),
            version.getVersionNumber(),
            "STAGED",
            2L,
            candidate.manifestHash(),
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            candidate.requiredManifestAssetKeys());
    VersionAssetArtifactStateDto exportedArtifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            version.getId(),
            version.getVersionNumber(),
            "EXPORTED_UNATTESTED",
            3L,
            candidate.manifestHash(),
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            candidate.requiredManifestAssetKeys());
    VersionAssetArtifactStateDto publishedArtifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            version.getId(),
            version.getVersionNumber(),
            "PUBLISHED",
            4L,
            candidate.manifestHash(),
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            candidate.requiredManifestAssetKeys());
    PublishedReleaseBundleDto bundle =
        publishedReleaseBundleDto(
            1L,
            "tenant-1",
            version.getId(),
            version.getVersionNumber(),
            "v1",
            workflowId,
            candidate.manifestHash(),
            candidate.requiredManifestAssetKeys(),
            participantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now());
    when(versionAssetArtifactService.findState("tenant-1", version.getId()))
        .thenReturn(Optional.of(stagedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", version.getId()))
        .thenReturn(candidate);
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(participantDigests);
    when(assetExportService.exportAssets("tenant-1", version.getVersionNumber()))
        .thenReturn(candidate);
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "10", "version:10", "digest", 1));
    when(versionAssetArtifactService.markExportedUnattested(
            "tenant-1", version.getId(), version.getVersionNumber(), workflowId, candidate))
        .thenReturn(exportedArtifact);
    when(publishedReleaseBundleService.createFullVersionBundle(
            any(VersionDto.class),
            org.mockito.ArgumentMatchers.eq(workflowId),
            org.mockito.ArgumentMatchers.eq(candidate),
            any(String.class),
            org.mockito.ArgumentMatchers.eq(participantDigests),
            same(capturedWorldPublicationEvidence)))
        .thenReturn(bundle);
    when(versionAssetArtifactService.markPublished(
            "tenant-1",
            version.getId(),
            exportedArtifact.stateEpoch(),
            workflowId,
            candidate.manifestHash()))
        .thenReturn(publishedArtifact);

    PublishWorkflowSnapshot snapshot =
        reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request);

    assertEquals("SUCCEEDED", snapshot.status(), snapshot.toString());
    verify(versionAssetArtifactService, org.mockito.Mockito.times(2))
        .getExportCandidate("tenant-1", version.getId());
    verify(versionAssetArtifactService, never())
        .stageExport(any(String.class), any(Long.class), any(Integer.class), any(String.class));
    verify(versionAssetArtifactService)
        .markExportedUnattested(
            "tenant-1", version.getId(), version.getVersionNumber(), workflowId, candidate);
    verify(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            org.mockito.ArgumentMatchers.eq(workflowId),
            org.mockito.ArgumentMatchers.eq(candidate),
            any(String.class),
            org.mockito.ArgumentMatchers.eq(participantDigests),
            same(capturedWorldPublicationEvidence));
    verify(publishAttemptService).markFullVersionSucceeded(workflowId);
    verify(publishAttemptRepository).sealPublication(attempt, true);
    var terminalOrder = inOrder(publishAttemptRepository, worldProducer);
    terminalOrder.verify(publishAttemptRepository).sealPublication(attempt, true);
    terminalOrder
        .verify(worldProducer)
        .reconcile(
            "tenant-1",
            request.publishWorkflowId(),
            snapshot.versionId(),
            attempt.getRequestDigest(),
            net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome
                .PUBLISHED);
  }

  @Test
  void selectedStagedArtifactWithMismatchedPersistedCandidateKeysRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            2L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(exportedManifest(List.of("different.json")));

    assertSelectedStagedArtifactRemainsPartial(fixture, artifact);
    verify(versionAssetArtifactService).getExportCandidate("tenant-1", 10L);
  }

  @Test
  void selectedStagedArtifactWithMismatchedPersistedCandidateHashRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            2L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(
            new ExportedAssetManifest(
                "sha256:" + "c".repeat(64),
                1,
                List.of("manifest.json"),
                artifactDigests(List.of("manifest.json"))));

    assertSelectedStagedArtifactRemainsPartial(fixture, artifact);
    verify(versionAssetArtifactService).getExportCandidate("tenant-1", 10L);
  }

  @Test
  void selectedStagedArtifactWithUncertainPersistedCandidateRemainsPartial() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            2L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenThrow(new IllegalStateException("ASSET_EXPORT_CANDIDATE_UNAVAILABLE"));

    assertSelectedStagedArtifactRemainsPartial(fixture, artifact);
    verify(versionAssetArtifactService).getExportCandidate("tenant-1", 10L);
  }

  private void assertSelectedStagedArtifactRemainsPartial(
      SelectedPublishFixture fixture, VersionAssetArtifactStateDto artifact) {
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(fixture.request()));

    assertTrue(thrown.getMessage().contains("incomplete"));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
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
    var terminalOrder = inOrder(publishAttemptRepository, worldProducer);
    terminalOrder.verify(publishAttemptRepository).sealPublication(attempt, true);
    terminalOrder
        .verify(worldProducer)
        .reconcile(
            "tenant-1",
            request.publishWorkflowId(),
            snapshot.versionId(),
            attempt.getRequestDigest(),
            net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome
                .PUBLISHED);
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
  void preExportFailureMarkingCommitUncertaintyLeavesAttemptPendingAndDoesNotCleanAssets() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(participantDigests());
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"))
        .when(publishGateService)
        .assertSelectedGatePassed(any(VersionDto.class), any(List.class));
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
    org.mockito.Mockito.verifyNoInteractions(worldProducer);
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void failureMarkingReadbackFailureKeepsPendingContractAndOriginalFailure() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    IllegalStateException operationFailure = new IllegalStateException("failure mark uncertain");
    IllegalStateException readFailure = new IllegalStateException("publication read failed");
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(participantDigests());
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"))
        .when(publishGateService)
        .assertSelectedGatePassed(any(VersionDto.class), any(List.class));
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

    assertTrue(thrown.getMessage().contains("failure marking readback failed"));
    assertEquals(readFailure, thrown.getCause());
    assertEquals(1, readFailure.getSuppressed().length);
    assertEquals(operationFailure, readFailure.getSuppressed()[0]);
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void freshFullVersionPublishIsDeniedBeforeAnyMutation() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";

    VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException freshDenial =
        assertThrows(
            VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
            () ->
                service.reconcileFullVersionPublish(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));
    assertEquals(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.ERROR_CODE,
        freshDenial.errorCode());
    assertEquals(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.ERROR_CODE
            + ": "
            + VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException
                .SAFE_MESSAGE,
        freshDenial.getMessage());
    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () -> service.publishFullVersion("tenant-1", "notes", "workflow-1", workflowId));

    verify(publishAttemptRepository, org.mockito.Mockito.times(2))
        .findByPublishWorkflowId(workflowId);
    verify(gameRepository, never()).findByTenantIdForUpdate(any(String.class));
    verify(versionRepository, never()).save(any(Version.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(publishGateService, never()).collectSelectedFullVersionParticipantDigests(
        any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(versionAssetArtifactService, never())
        .stageExport(any(String.class), any(Long.class), any(Integer.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void pendingFullVersionPublishIsDeniedBeforeAnyMutationOrReadbackWork() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId)));

    assertThrows(
        VersionPublishCommandServiceImpl.FullVersionPublicationUnavailableException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    verify(publishAttemptRepository).findByPublishWorkflowId(workflowId);
    verify(gameRepository, never()).findByTenantIdForUpdate(any(String.class));
    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(versionRepository, never()).save(any(Version.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(publishedReleaseBundleService, never())
        .findPublishedReleaseBundle(any(String.class), any(Long.class));
    verify(versionAssetArtifactService, never()).findState(any(String.class), any(Long.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void privateMechanicsUsesDurablySelectedVersionWithoutSequenceReservation() {
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
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    var selectedBinding = org.mockito.ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(publishGateService).collectSelectedFullVersionParticipantDigests(
        any(VersionDto.class), selectedBinding.capture(),
        org.mockito.ArgumentMatchers.eq(request.publishWorkflowId()));
    var expectedBinding = PublicationDigestRequestBinding.full(
        fixture.reservation().selection().intent().canonicalTenantId().toString(),
        version.getId().toString(), request.publishRequestId());
    assertEquals(expectedBinding.tenantId(), selectedBinding.getValue().tenantId());
    assertEquals(expectedBinding.versionId(), selectedBinding.getValue().versionId());
    assertEquals(expectedBinding.scopeKind(), selectedBinding.getValue().scopeKind());
    assertEquals(expectedBinding.requestDigest(), selectedBinding.getValue().requestDigest());
    assertEquals(request.publishWorkflowId(), selectedBinding.getValue().derivedWorkflowIdentity());
    verify(publishGateService, never()).collectFullVersionParticipantDigests(
        any(VersionDto.class), any(String.class), any(String.class));
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));

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
    var terminalOrder = inOrder(publishAttemptRepository, worldProducer);
    terminalOrder.verify(publishAttemptRepository).sealPublication(attempt, true);
    terminalOrder
        .verify(worldProducer)
        .reconcile(
            "tenant-1",
            request.publishWorkflowId(),
            snapshot.versionId(),
            attempt.getRequestDigest(),
            net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome
                .PUBLISHED);
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
        .assertSelectedGatePassed(
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
  void privateMechanicsRecordsTypedPublishGateFailures() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
    verify(worldProducer)
        .reconcile(
            "tenant-1",
            fixture.request().publishWorkflowId(),
            snapshot.versionId(),
            fixture.attempt().getRequestDigest(),
            net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome
                .NO_PUBLICATION);
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void privateMechanicsGateRejectionDoesNotRecordDigestsOrExportAssets() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(participantDigests);
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, "gate rejected"))
        .when(publishGateService)
        .assertSelectedGatePassed(any(VersionDto.class), any(List.class));

    PublishWorkflowSnapshot snapshot =
        reflectiveUnitHarnessReconcileSelectedPublicationMechanics(fixture.request());

    assertEquals("FAILED", snapshot.status());
    assertEquals(
        PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH.name(), snapshot.failureCode());
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishAttemptRepository).sealPublication(fixture.attempt(), false);
    verify(worldProducer)
        .reconcile(
            "tenant-1",
            fixture.request().publishWorkflowId(),
            snapshot.versionId(),
            fixture.attempt().getRequestDigest(),
            net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome
                .NO_PUBLICATION);
  }

  @Test
  void freshSelectedPublishRejectsLegacyOwnerVectorBeforeGateOrAssetEffects() throws Exception {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    var fullVector = participantDigests();
    var legacySchemas = List.of(3, 2, 1, 5, 2);
    List<PublishParticipantDigestDto> legacyVector =
        java.util.stream.IntStream.range(0, fullVector.size())
            .mapToObj(
                index -> {
                  var participant = fullVector.get(index);
                  return new PublishParticipantDigestDto(
                      participant.participantKey(),
                      participant.scopeValue(),
                      participant.baseVersionId(),
                      participant.appliedCommitId(),
                      participant.contentDigest(),
                      legacySchemas.get(index),
                      participant.abilitySchemaDigest(),
                      participant.errorCode(),
                      participant.errorMessage());
                })
            .toList();
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(legacyVector);

    PublishWorkflowSnapshot snapshot =
        reflectiveUnitHarnessReconcileSelectedPublicationMechanics(fixture.request());

    assertEquals("FAILED", snapshot.status());
    verify(publishGateService, never()).assertSelectedGatePassed(any(VersionDto.class), any(List.class));
    verify(versionAssetArtifactService, never())
        .stageExport(any(String.class), any(Long.class), any(Integer.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void freshSelectedPublishRefusesEachNoncanonicalParticipantSchemaBeforeEffects(int index)
      throws Exception {
    // These unit carriers are controlled; the actual contract validator must reject every downgrade.
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    var vector = new java.util.ArrayList<>(participantDigests());
    var original = vector.get(index);
    vector.set(index, new PublishParticipantDigestDto(
        original.participantKey(), original.scopeValue(), original.baseVersionId(),
        original.appliedCommitId(), original.contentDigest(), original.digestSchemaVersion() + 1,
        original.abilitySchemaDigest(), original.errorCode(), original.errorMessage()));
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
        any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(vector);

    var result = reflectiveUnitHarnessReconcileSelectedPublicationMechanics(fixture.request());

    assertEquals("FAILED", result.status());
    verify(publishGateService, never()).assertSelectedGatePassed(any(VersionDto.class), any(List.class));
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));
    verify(versionAssetArtifactService, never()).stageExport(
        any(String.class), any(Long.class), any(Integer.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void privateMechanicsPreservesSharedAssetsWhenAttestationWriteIsUncertain() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    VersionAssetArtifactStateDto staged = stagedArtifact(request, fixture.version());
    VersionAssetArtifactStateDto stagedCandidate =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            2L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(
            Optional.empty(),
            Optional.of(staged),
            Optional.of(stagedCandidate),
            Optional.of(stagedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(null, manifest, manifest);
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, request.publishWorkflowId()))
        .thenReturn(staged);
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(participantDigests());
    org.mockito.Mockito.doThrow(new IllegalStateException("attestation write outcome is unknown"))
        .when(versionAssetArtifactService)
        .markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(
        thrown.getMessage().contains("exported candidate requires exact retry/reconciliation"));
    assertEquals("attestation write outcome is unknown", thrown.getCause().getMessage());
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    verify(assetExportService).exportAssets("tenant-1", 1);
    verify(versionAssetArtifactService)
        .markExportedUnattested(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.eq(request.publishWorkflowId()),
            same(manifest));
    verify(versionAssetArtifactService, org.mockito.Mockito.times(4))
        .getExportCandidate("tenant-1", 10L);
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(publishAttemptRepository, never())
        .sealPublication(any(PublishAttempt.class), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void privateMechanicsReconcilesPendingAttemptThroughCurrentGates() throws Exception {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    SelectionSnapshot seedReservation = fixture.reservation();
    AuthoredDraftPublishSelection seedSelection = seedReservation.selection();
    DraftCommitBinding seedCommit = seedSelection.selectedCommit();
    DraftCommitBinding expandedCommit =
        PublishedWorldSelectorFixtures.withIsolatedFreshWorldGraph(seedCommit);
    List<RevisionPayload> graphRevisions = new java.util.ArrayList<>();
    for (RevisionPayload revision : expandedCommit.revisions()) {
      if (seedCommit.revisions().contains(revision)) continue;
      graphRevisions.add(
          new RevisionPayload(
              Integer.toString(graphRevisions.size()),
              revision.revisionId(),
              revision.owner(),
              revision.payload()));
    }
    List<AffectedUnit> graphAffectedUnits =
        expandedCommit.affectedUnits().stream()
            .filter(unit -> !seedCommit.affectedUnits().contains(unit))
            .toList();
    DraftCommitBinding typedCommit =
        DraftCommitBinding.create(
            seedCommit.target(),
            seedCommit.requestId(),
            seedCommit.commitId(),
            seedCommit.baseCommitId(),
            graphRevisions,
            graphAffectedUnits);
    PublishIntent seedIntent = seedSelection.intent();
    PublishIntent typedIntent =
        new PublishIntent(
            seedIntent.canonicalTenantId(),
            seedIntent.canonicalVersionId(),
            seedIntent.publishRequestId(),
            seedIntent.expectedVersionStateEpoch(),
            seedIntent.notes(),
            seedIntent.selectedCommitRequestId(),
            seedIntent.selectedCommitId(),
            typedCommit.digest());
    VisibilityFence typedFence =
        new VisibilityFence(
            seedSelection.target(),
            seedSelection.fenceRequestId(),
            seedSelection.fenceCommitId(),
            typedCommit.digest(),
            seedSelection.fenceResultVectorJson(),
            OffsetDateTime.parse(seedSelection.fenceCreatedAt()));
    SelectionSnapshot reservation =
        new SelectionSnapshot(
            AuthoredDraftPublishSelection.capture(
                typedIntent,
                seedSelection.target(),
                new PublicationEvidence(typedCommit, typedFence)),
            seedReservation.createdAt());
    PublishWorkflowRequest request = selectedRequest(reservation);
    PublishAttempt attempt = fixture.attempt();
    attempt.setRequestDigest(reservation.selection().digest());
    Version version = fixture.version();
    fixture = new SelectedPublishFixture(reservation, request, attempt, version);
    when(authoredSelections.readByPublishRequest(
            typedIntent.canonicalTenantId(), request.publishRequestId()))
        .thenReturn(Optional.of(reservation));
    String workflowId = request.publishWorkflowId();
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(2L);
    version.setCanonicalTenantId(CANONICAL_TENANT_ID);
    version.setCanonicalVersionId(CANONICAL_VERSION_ID);
    // This fixture stipulates historical Account/APPLIED bytes; it proves no live owner authority.
    WorldPublishedStartLocationEvidence fixtureEvidence =
        PublishedWorldSelectorFixtures.evidence(
            fixture.reservation().selection().target(),
            fixture.reservation().selection().selectedCommit());
    WorldPublishedStartLocationEvidence.Request fixtureRequest = fixtureEvidence.request();
    WorldPublishedStartLocationEvidence selectedEvidence =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                fixtureRequest.targetNamespace(),
                fixtureRequest.canonicalTenantId(),
                fixtureRequest.canonicalVersionId(),
                fixtureRequest.intakeRequestId(),
                fixtureRequest.publicationFence(),
                request.publishRequestId(),
                fixtureRequest.requestDigest(),
                Long.parseLong(request.intent().expectedVersionStateEpoch()),
                workflowId,
                fixtureRequest.appliedCommitId(),
                fixtureRequest.contentDigest(),
                fixtureRequest.digestSchemaVersion(),
                fixtureRequest.worldAffectedTuples()),
            fixtureEvidence.selectorReceiptBytes(),
            fixtureEvidence.originalAccountBindingBytes(),
            fixtureEvidence.appliedResultBytes());
    List<PublishParticipantDigestDto> participantDigests =
        PublishedWorldSelectorFixtures.participants(
            10L, selectedEvidence, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v2",
            workflowId,
            MANIFEST_HASH,
            List.of("manifest.json"),
            participantDigests,
            List.of(),
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            "opaque-release-reference-from-owner",
            1,
            artifactDigests(List.of("manifest.json")),
            selectedEvidence);
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
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L)).thenReturn(manifest);

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
  }

  @Test
  void candidateRecordedBeforeConflictReadbackKeepsAttemptPendingForReconciliation() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    String workflowId = request.publishWorkflowId();
    PublishAttempt attempt = fixture.attempt();
    Version version = fixture.version();
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    VersionAssetArtifactStateDto emptyStagedArtifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            1L,
            null,
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of());
    VersionAssetArtifactStateDto candidateStagedArtifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
            2L,
            MANIFEST_HASH,
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    Game game = new Game();
    game.setTenantId("tenant-1");
    AtomicBoolean possibleObjectWrite = new AtomicBoolean();
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.of(emptyStagedArtifact), Optional.of(candidateStagedArtifact));
    org.mockito.Mockito.doReturn(null, manifest)
        .when(versionAssetArtifactService)
        .getExportCandidate("tenant-1", 10L);
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
        .thenReturn(participantDigests());
    org.mockito.Mockito.doAnswer(
            ignored -> {
              // The exporter can fail after writing one or more immutable objects.
              possibleObjectWrite.set(true);
              throw new IllegalStateException(
                  "IMMUTABLE_OBJECT_CONFLICT: existing object differs from the candidate bytes");
            })
        .when(assetExportService)
        .exportAssets("tenant-1", 1);

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> reflectiveUnitHarnessReconcileSelectedPublicationMechanics(request));

    assertTrue(thrown.getMessage().contains("candidate/write reconciliation"));
    assertTrue(possibleObjectWrite.get());
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(assetExportService).exportAssets("tenant-1", 1);
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
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void postExportFinalizationFailureWithExactCandidateRemainsPendingWithoutCleanup() {
    SelectedPublishFixture fixture = selectedPublishFixture(1);
    PublishWorkflowRequest request = fixture.request();
    Version version = fixture.version();
    String workflowId = request.publishWorkflowId();
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    VersionAssetArtifactStateDto staged = stagedArtifact(request, version);
    VersionAssetArtifactStateDto stagedCandidate =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "STAGED",
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
            Optional.empty(),
            Optional.of(staged),
            Optional.of(stagedCandidate),
            Optional.of(stagedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(null, manifest, manifest);
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenReturn(staged);
    when(publishGateService.collectSelectedFullVersionParticipantDigests(
            any(VersionDto.class), any(PublicationDigestRequestBinding.class), any(String.class)))
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
                3L,
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

    assertTrue(
        thrown.getMessage().contains("exported candidate requires exact retry/reconciliation"));
    assertEquals(PublishAttemptStatus.PENDING, fixture.attempt().getStatus());
    assertEquals(VersionLifecycleState.DRAFT, version.getVersionState());
    assertEquals(1L, version.getVersionStateEpoch());
    verify(assetExportService).exportAssets("tenant-1", 1);
    verify(versionAssetArtifactService)
        .markExportedUnattested(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.eq(workflowId),
            same(manifest));
    verify(versionAssetArtifactService, org.mockito.Mockito.times(4))
        .getExportCandidate("tenant-1", 10L);
    verify(versionRepository).save(version);
    verify(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            org.mockito.ArgumentMatchers.eq(workflowId),
            same(manifest),
            any(String.class),
            org.mockito.ArgumentMatchers.eq(participantDigests),
            same(capturedWorldPublicationEvidence));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(publishAttemptRepository, never())
        .sealPublication(same(fixture.attempt()), org.mockito.ArgumentMatchers.anyBoolean());
    verify(versionAssetArtifactService, never())
        .markFailed(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
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
    // This fixture selects an existing intent for the private conditional mechanics seam; it
    // carries no Account authorization or authenticated World publication freeze.
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

  private SelectedSucceededReplayFixture selectedSucceededReplayFixture(
      VersionLifecycleState lifecycleState) throws Exception {
    TargetProof target =
        new TargetProof(
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            10L,
            "tenant-1",
            1L,
            "tenant-1",
            "NEW_GAME_ROW");
    // Stipulated original upstream bytes, not live Account or World producer proof. This closed
    // operation carries one typed World graph, reviewed base, selection, and publication request.
    var operation =
        IsolatedPublicationOperationFixtures.fresh(
            target, "genesis:66666666-6666-4666-8666-666666666666");
    var selected = operation.account().input().selection();
    SelectionSnapshot reservation =
        new SelectionSnapshot(
            AuthoredDraftPublishSelection.fromStored(selected.canonicalJson(), selected.digest()),
            OffsetDateTime.parse("2026-10-08T00:00:00Z"));
    PublishWorkflowRequest request = selectedRequest(reservation);
    PublishAttempt attempt =
        fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, request.publishWorkflowId());
    attempt.setRequestDigest(reservation.selection().digest());
    Version version = fullVersion(10L, 1, lifecycleState);
    version.setVersionStateEpoch(
        switch (lifecycleState) {
          case PUBLISHED -> 6L;
          case ACTIVE -> 7L;
          case RETIRED -> 8L;
          default ->
              throw new IllegalArgumentException("terminal replay fixture requires a release");
        });
    version.setCanonicalTenantId(CANONICAL_TENANT_ID);
    version.setCanonicalVersionId(CANONICAL_VERSION_ID);

    // The retained v2 selector payload must bind to the exact selected World commit and original
    // publication request, regardless of the Version's later legal lifecycle state.
    WorldPublishedStartLocationEvidence selectedEvidence = operation.world();
    List<PublishParticipantDigestDto> participantDigests =
        PublishedWorldSelectorFixtures.participants(
            10L, selectedEvidence, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v2",
            request.publishWorkflowId(),
            MANIFEST_HASH,
            List.of("manifest.json"),
            participantDigests,
            List.of(),
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            "opaque-release-reference-from-owner",
            1,
            artifactDigests(List.of("manifest.json")),
            selectedEvidence);
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "PUBLISHED",
            2L,
            MANIFEST_HASH,
            request.publishWorkflowId(),
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));

    when(authoredSelections.readByPublishRequest(
            request.intent().canonicalTenantId(), request.publishRequestId()))
        .thenReturn(Optional.of(reservation));
    when(publishAttemptRepository.findByPublishWorkflowId(request.publishWorkflowId()))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L)).thenReturn(manifest);
    return new SelectedSucceededReplayFixture(
        request, attempt, bundle, participantDigests, artifact);
  }

  private void assertNoPublicationWrites() {
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(versionAssetArtifactService, never())
        .stageExport(any(String.class), any(Long.class), any(Integer.class), any(String.class));
    verify(versionAssetArtifactService, never())
        .markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class));
    verify(versionAssetArtifactService, never())
        .markPublished(
            any(String.class),
            any(Long.class),
            any(Long.class),
            any(String.class),
            any(String.class));
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            any(WorldPublishedStartLocationEvidence.class));
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
    verify(publishAttemptService, never()).markFullVersionSucceeded(any(String.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(publishAttemptRepository, never())
        .sealPublication(any(PublishAttempt.class), org.mockito.ArgumentMatchers.anyBoolean());
    verify(publishAttemptRepository, never()).save(any(PublishAttempt.class));
    verify(versionRepository, never()).save(any(Version.class));
    verify(gameRepository, never()).findByTenantIdForUpdate(any(String.class));
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(recordedParticipantDigestService, never())
        .recordVerifiedDigests(
            any(String.class), any(PublishType.class), any(String.class), any(List.class));
    verify(authoredSelections, never()).reserve(any(PublishIntent.class));
  }

  private static PublishedReleaseBundleDto withWorkflowId(
      PublishedReleaseBundleDto bundle, String workflowId) {
    return new PublishedReleaseBundleDto(
        bundle.id(),
        bundle.tenantId(),
        bundle.versionId(),
        bundle.versionNumber(),
        bundle.attestationSchemaVersion(),
        workflowId,
        bundle.manifestHash(),
        bundle.requiredManifestAssetKeys(),
        bundle.participantDigests(),
        bundle.commandDefinitions(),
        bundle.generationConfigRevision(),
        bundle.scriptOnly(),
        bundle.scriptPatchVersion(),
        bundle.publishedAt(),
        bundle.canonicalTenantId(),
        bundle.canonicalVersionId(),
        bundle.publishedReleaseBundleRef(),
        bundle.manifestSchemaVersion(),
        bundle.artifactDigests(),
        bundle.worldPublishedStartLocationEvidence());
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
    String appliedCommitId = selectedIntent().selectedCommitId().toString();
    return List.of(
        new PublishParticipantDigestDto(
            "WORLD_MANAGEMENT", "10", null, appliedCommitId, "a".repeat(64), 4, null, null, null),
        new PublishParticipantDigestDto(
            "ENTITY_MANAGEMENT", "10", null, appliedCommitId, "b".repeat(64), 3, null, null, null),
        new PublishParticipantDigestDto(
            "GAME_LOGIC",
            "10",
            null,
            appliedCommitId,
            "c".repeat(64),
            1,
            "sha256:" + "d".repeat(64),
            null,
            null),
        new PublishParticipantDigestDto(
            "AUTOMATION_SCRIPTING", "10", null, appliedCommitId, "e".repeat(64), 6, null, null, null),
        new PublishParticipantDigestDto(
            "GAME_DESIGN_CONTROL_PLANE",
            "10",
            null,
            appliedCommitId,
            "f".repeat(64),
            2,
            null,
            null,
            null));
  }

  private record SelectedPublishFixture(
      SelectionSnapshot reservation,
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      Version version) {}

  private record SelectedSucceededReplayFixture(
      PublishWorkflowRequest request,
      PublishAttempt attempt,
      PublishedReleaseBundleDto bundle,
      List<PublishParticipantDigestDto> participantDigests,
      VersionAssetArtifactStateDto artifact) {}

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
