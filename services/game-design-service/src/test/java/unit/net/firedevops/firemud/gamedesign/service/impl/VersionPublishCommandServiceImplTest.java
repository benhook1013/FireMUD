package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class VersionPublishCommandServiceImplTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String OTHER_MANIFEST_HASH = "sha256:" + "c".repeat(64);
  private static final String LOGO_DIGEST = "sha256:" + "b".repeat(64);
  private static final String OTHER_LOGO_DIGEST = "sha256:" + "d".repeat(64);

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

  private VersionPublishCommandServiceImpl service;

  @Test
  void selectorV2CommittedReadbackReconcilesAndExactlyReplaysWithoutCreatingAnotherRelease()
      throws Exception {
    // Original Account/APPLIED and freeze fields are stipulated component fixtures, not a live
    // authenticated freeze/permission producer or authorization to enable new publication.
    SelectorReadback fixture = selectorReadback();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING,
            10L,
            1,
            fixture.selected().request(),
            fixture.selected().selection());
    stubSelectorReadback(fixture, attempt);
    var first = service.reconcileFullVersionPublish(fixture.selected().request());
    assertEquals("SUCCEEDED", first.status());
    verify(publishAttemptService)
        .markFullVersionSucceeded(fixture.selected().request().publishWorkflowId());
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    var retry = service.reconcileFullVersionPublish(fixture.selected().request());
    assertEquals(first, retry);
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(any(), any(), any(), any(), any());
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(any(), any(), any(), any(), any(), any());
    verify(versionRepository, never()).save(any());
  }

  @Test
  void selectorV2ReadbackRejectsAnotherOriginalSelectedBindingEvenWithSameCommitUuid()
      throws Exception {
    SelectorReadback fixture = selectorReadback();
    var original = fixture.selected().selection().selectedCommit();
    var changed =
        DraftCommitBinding.create(
            original.target(),
            original.requestId(),
            original.commitId(),
            "changed-original-base",
            original.revisions(),
            original.affectedUnits());
    SelectedRequest selected = selectOriginalForReadback(changed, "5");
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    SelectorReadback substituted =
        new SelectorReadback(selected, fixture.evidence(), fixture.bundle(), fixture.version());
    stubSelectorReadback(substituted, attempt);
    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(selected.request()));
    verify(publishAttemptService, never()).markFullVersionSucceeded(any());
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @ParameterizedTest
  @EnumSource(value = SelectorSubstitution.class)
  void selectorV2ReadbackRejectsChangedPublicationRequestOrHistoricalEpoch(
      SelectorSubstitution substitution) throws Exception {
    SelectorReadback fixture = selectorReadback();
    var original = fixture.evidence().request();
    var changedRequest =
        new WorldPublishedStartLocationEvidence.Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.canonicalVersionId(),
            original.intakeRequestId(),
            original.publicationFence(),
            substitution == SelectorSubstitution.REQUEST
                ? "another-request"
                : original.publicationRequestId(),
            original.requestDigest(),
            substitution == SelectorSubstitution.EPOCH ? 6L : original.versionStateEpoch(),
            original.publishWorkflowId(),
            original.appliedCommitId(),
            original.contentDigest(),
            original.digestSchemaVersion(),
            original.worldAffectedTuples());
    var changed =
        new WorldPublishedStartLocationEvidence(
            changedRequest,
            fixture.evidence().selectorReceiptBytes(),
            fixture.evidence().originalAccountBindingBytes(),
            fixture.evidence().appliedResultBytes());
    SelectorReadback substituted =
        new SelectorReadback(
            fixture.selected(),
            changed,
            selectorBundle(fixture.selected(), changed),
            fixture.version());
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING,
            10L,
            1,
            fixture.selected().request(),
            fixture.selected().selection());
    stubSelectorReadback(substituted, attempt);
    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(fixture.selected().request()));
    verify(publishAttemptService, never()).markFullVersionSucceeded(any());
  }

  private enum SelectorSubstitution {
    REQUEST,
    EPOCH
  }

  private SelectorReadback selectorReadback() throws Exception {
    var target =
        new TargetProof(
            UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
            UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
            10L,
            "tenant-1",
            1L,
            "tenant-1",
            "NEW_GAME_ROW");
    var original = PublishedWorldSelectorFixtures.evidence(target);
    var account = DraftAuthorizationFenceBinding.fromStored(original.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(account.gameDesignBinding(), StandardCharsets.UTF_8), account.inputDigest());
    var selected = selectOriginalForReadback(draft, "5");
    var r = original.request();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            r.targetNamespace(),
            r.canonicalTenantId(),
            r.canonicalVersionId(),
            r.intakeRequestId(),
            r.publicationFence(),
            selected.request().publishRequestId(),
            r.requestDigest(),
            5L,
            selected.request().publishWorkflowId(),
            r.appliedCommitId(),
            r.contentDigest(),
            r.digestSchemaVersion(),
            r.worldAffectedTuples());
    var evidence =
        new WorldPublishedStartLocationEvidence(
            request,
            original.selectorReceiptBytes(),
            original.originalAccountBindingBytes(),
            original.appliedResultBytes());
    var version = new Version();
    version.setId(10L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(6L);
    version.setCanonicalTenantId(target.canonicalTenantId());
    version.setCanonicalVersionId(target.canonicalVersionId());
    return new SelectorReadback(selected, evidence, selectorBundle(selected, evidence), version);
  }

  private SelectedRequest selectOriginalForReadback(DraftCommitBinding draft, String epoch) {
    var target = draft.target();
    var intent =
        new PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "workflow-1",
            epoch,
            "notes",
            draft.requestId(),
            draft.commitId(),
            draft.digest());
    var fence =
        new VisibilityFence(
            target,
            draft.requestId(),
            draft.commitId(),
            draft.digest(),
            "[]",
            OffsetDateTime.now(ZoneOffset.UTC));
    var selected =
        AuthoredDraftPublishSelection.capture(
            intent, target, new PublicationEvidence(draft, fence));
    var snapshot = new SelectionSnapshot(selected, OffsetDateTime.now(ZoneOffset.UTC));
    when(authoredSelections.readByPublishRequest(target.canonicalTenantId(), "workflow-1"))
        .thenReturn(Optional.of(snapshot));
    var request =
        new PublishWorkflowRequest(
            "tenant-1", "notes", "workflow-1", selectedWorkflowId("workflow-1"), intent);
    return new SelectedRequest(selected, intent, snapshot, request);
  }

  private PublishedReleaseBundleDto selectorBundle(
      SelectedRequest selected, WorldPublishedStartLocationEvidence evidence) {
    return new PublishedReleaseBundleDto(
        1L,
        "tenant-1",
        10L,
        1,
        "v2",
        selected.request().publishWorkflowId(),
        MANIFEST_HASH,
        List.of(),
        PublishedWorldSelectorFixtures.participants(10L, evidence),
        List.of(),
        "generation-revision",
        false,
        null,
        LocalDateTime.now(),
        evidence.request().canonicalTenantId(),
        evidence.request().canonicalVersionId(),
        "opaque-owner-release",
        1,
        List.of(),
        evidence);
  }

  private void stubSelectorReadback(SelectorReadback fixture, PublishAttempt attempt) {
    var workflow = fixture.selected().request().publishWorkflowId();
    var game = new Game();
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(publishAttemptRepository.findByPublishWorkflowId(workflow))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(fixture.version()));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(fixture.bundle()));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(
            Optional.of(
                new VersionAssetArtifactStateDto(
                    "tenant-1",
                    10L,
                    1,
                    "PUBLISHED",
                    4L,
                    MANIFEST_HASH,
                    workflow,
                    null,
                    null,
                    LocalDateTime.now(),
                    List.of())));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(emptyManifest());
  }

  private record SelectorReadback(
      SelectedRequest selected,
      WorldPublishedStartLocationEvidence evidence,
      PublishedReleaseBundleDto bundle,
      Version version) {}

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    when(publishAttemptRepository.findByPublishWorkflowIdForUpdate(any(String.class)))
        .thenAnswer(
            invocation ->
                publishAttemptRepository.findByPublishWorkflowId(invocation.getArgument(0)));
    when(publishedReleaseBundleService.findPublishedReleaseBundle(
            any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState(any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());
    when(versionRepository.findByTenantIdAndIdForUpdate(any(String.class), any(Long.class)))
        .thenAnswer(
            invocation ->
                versionRepository.findByTenantIdAndId(
                    invocation.getArgument(0), invocation.getArgument(1)));
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
  void finalizeFullVersionUsesSelectedExistingDraftAndFiveMatchingParticipantCommits() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version latest = new Version();
    latest.setId(9L);
    latest.setTenantId("tenant-1");
    latest.setVersionNumber(7);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.of(latest));

    Version savedDraft = new Version();
    savedDraft.setId(10L);
    savedDraft.setTenantId("tenant-1");
    savedDraft.setVersionNumber(8);
    savedDraft.setNotes("notes");
    savedDraft.setVersionState(VersionLifecycleState.DRAFT);
    savedDraft.setVersionStateEpoch(1L);
    savedDraft.setUpdatedAt(LocalDateTime.now());
    setCanonicalSource(savedDraft);
    Version savedPublished = new Version();
    savedPublished.setId(10L);
    savedPublished.setTenantId("tenant-1");
    savedPublished.setVersionNumber(8);
    savedPublished.setNotes("notes");
    savedPublished.setVersionState(VersionLifecycleState.PUBLISHED);
    savedPublished.setVersionStateEpoch(2L);
    savedPublished.setUpdatedAt(LocalDateTime.now());
    setCanonicalSource(savedPublished);
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft, savedPublished);

    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 8, selected.request(), selected.selection());
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(
            Optional.of(savedDraft),
            Optional.of(savedDraft),
            Optional.of(savedDraft),
            Optional.of(savedDraft),
            Optional.of(savedPublished));

    ExportedAssetManifest exportedManifest = logoManifest();
    List<PublishParticipantDigestDto> participantDigests =
        participantDigests(selected.selection(), 10L);
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "10", "version:10", "digest-1", 1));
    when(versionAssetArtifactService.markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class)))
        .thenReturn(
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                8,
                "EXPORTED_UNATTESTED",
                3L,
                MANIFEST_HASH,
                workflowId,
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png")));
    when(publishedReleaseBundleService.createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            org.mockito.ArgumentMatchers.nullable(WorldPublishedStartLocationEvidence.class)))
        .thenReturn(
            new PublishedReleaseBundleDto(
                1L,
                "tenant-1",
                10L,
                8,
                "v1",
                workflowId,
                MANIFEST_HASH,
                List.of("logo.png"),
                participantDigests,
                "genrev-tenant-1-10",
                false,
                null,
                LocalDateTime.now(),
                UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
                UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
                "opaque-owner-issued-release-reference",
                1,
                List.of(logoProof())));
    when(versionAssetArtifactService.markPublished(
            any(String.class),
            any(Long.class),
            any(Long.class),
            any(String.class),
            any(String.class)))
        .thenReturn(
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                8,
                "PUBLISHED",
                4L,
                MANIFEST_HASH,
                workflowId,
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png")));

    PublishWorkflowSnapshot snapshot =
        service.finalizeFullVersion(selected.request(), participantDigests, exportedManifest);

    assertEquals("SUCCEEDED", snapshot.status());
    assertEquals(10L, snapshot.versionId());
    assertEquals(VersionLifecycleState.PUBLISHED, savedDraft.getVersionState());
    assertEquals(2L, savedDraft.getVersionStateEpoch());
    InOrder versionOrder = inOrder(versionRepository);
    versionOrder.verify(versionRepository).findByTenantIdAndIdForUpdate("tenant-1", 10L);
    versionOrder.verify(versionRepository).save(any(Version.class));
    verify(versionRepository, never()).findTopByTenantIdOrderByVersionNumberDesc("tenant-1");
    verify(publishAttemptService, never())
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(any(String.class), any(), any(String.class), any(List.class));

    InOrder publishOrder = inOrder(publishGateService, publishAttemptService);
    publishOrder
        .verify(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));
    publishOrder
        .verify(publishAttemptService)
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
  }

  @Test
  void internalSelectedFinalizationPreservesTypedGateFailureBeforeWrites() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.empty());

    Version savedDraft = new Version();
    savedDraft.setId(10L);
    savedDraft.setTenantId("tenant-1");
    savedDraft.setVersionNumber(1);
    savedDraft.setVersionState(VersionLifecycleState.DRAFT);
    savedDraft.setVersionStateEpoch(1L);
    savedDraft.setUpdatedAt(LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);

    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));
    List<PublishParticipantDigestDto> digests = participantDigests(selected.selection(), 10L);
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"))
        .when(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () -> service.finalizeFullVersion(selected.request(), digests, logoManifest()));

    assertEquals(PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, thrown.failureCode());
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(any(), any(), any(), any(), any());
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(any(), any(), any(), any(), any(), any());
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void internalSelectedFinalizationRejectsOneParticipantOnAnotherCommitBeforeWrites() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.empty());

    Version savedDraft = new Version();
    savedDraft.setId(10L);
    savedDraft.setTenantId("tenant-1");
    savedDraft.setVersionNumber(1);
    savedDraft.setVersionState(VersionLifecycleState.DRAFT);
    savedDraft.setVersionStateEpoch(1L);
    savedDraft.setUpdatedAt(LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);

    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));

    List<PublishParticipantDigestDto> participantDigests =
        new java.util.ArrayList<>(participantDigests(selected.selection(), 10L));
    PublishParticipantDigestDto worldDigest = participantDigests.get(1);
    participantDigests.set(
        1,
        new PublishParticipantDigestDto(
            worldDigest.participantKey(),
            worldDigest.scopeValue(),
            worldDigest.baseVersionId(),
            "99999999-9999-4999-8999-999999999999",
            worldDigest.contentDigest(),
            worldDigest.digestSchemaVersion(),
            worldDigest.abilitySchemaDigest(),
            worldDigest.errorCode(),
            worldDigest.errorMessage()));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () ->
                service.finalizeFullVersion(
                    selected.request(), participantDigests, logoManifest()));

    assertEquals(PublishGateFailureCode.APPLIED_COMMIT_MISMATCH, thrown.failureCode());
    verify(publishGateService, never()).assertGatePassed(any(VersionDto.class), any(List.class));
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(any(), any(), any(), any(), any());
    verify(publishedReleaseBundleService, never())
        .createFullVersionBundle(any(), any(), any(), any(), any(), any());
  }

  @Test
  void internalSelectedFinalizationBundleFailureLeavesAttemptPendingAndRetainsCandidate() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);

    Version savedDraft = new Version();
    savedDraft.setId(10L);
    savedDraft.setTenantId("tenant-1");
    savedDraft.setVersionNumber(1);
    savedDraft.setVersionState(VersionLifecycleState.DRAFT);
    savedDraft.setVersionStateEpoch(1L);
    savedDraft.setUpdatedAt(LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);

    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));
    List<PublishParticipantDigestDto> digests = participantDigests(selected.selection(), 10L);
    // The selected transaction seam receives an already-exported immutable candidate.
    ExportedAssetManifest alreadyExportedCandidate = logoManifest();
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "10", "version:10", "digest-1", 1));
    when(versionAssetArtifactService.markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class)))
        .thenReturn(
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
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
                List.of("logo.png")));
    org.mockito.Mockito.doThrow(new IllegalStateException("bundle failed"))
        .when(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            org.mockito.ArgumentMatchers.nullable(WorldPublishedStartLocationEvidence.class));

    assertThrows(
        IllegalStateException.class,
        () -> service.finalizeFullVersion(selected.request(), digests, alreadyExportedCandidate));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(versionAssetArtifactService)
        .markExportedUnattested(
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class),
            any(ExportedAssetManifest.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
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
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of(),
            driftedParticipantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
            UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
            "opaque-owner-issued-release-reference",
            1,
            List.of());
    VersionAssetArtifactStateDto driftedArtifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "PURGED",
            8L,
            OTHER_MANIFEST_HASH,
            "different-workflow",
            null,
            null,
            LocalDateTime.now(),
            List.of());
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(emptyManifest());
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
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            11L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of(),
            participantDigests(),
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
            UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
            "opaque-owner-issued-release-reference",
            1,
            List.of());
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
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of(),
            participantDigests(),
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
            UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
            "opaque-owner-issued-release-reference",
            null,
            null);
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
  void readbackWithArtifactButNoBundleRemainsPartial() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    VersionAssetArtifactStateDto artifact =
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
            List.of());
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> service.reconcileFullVersionPublish(selected.request()));

    assertTrue(thrown.getMessage().contains("incomplete"));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void selectedPendingAttemptCanReadBackAnAlreadyCommittedTerminalRelease() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.PUBLISHED);
    List<PublishParticipantDigestDto> participantDigests =
        participantDigests(selected.selection(), 10L);
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of(),
            participantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
            UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
            "opaque-owner-issued-release-reference",
            1,
            List.of());
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "PUBLISHED",
            4L,
            MANIFEST_HASH,
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of());
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(emptyManifest());

    PublishWorkflowSnapshot snapshot = service.reconcileFullVersionPublish(selected.request());

    assertEquals("SUCCEEDED", snapshot.status());
    verify(publishGateService)
        .assertGatePassed(
            any(VersionDto.class), org.mockito.ArgumentMatchers.eq(participantDigests));
    verify(recordedParticipantDigestService)
        .assertMatchesRecordedDigests("tenant-1", PublishType.FULL_VERSION, participantDigests);
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(
            "tenant-1", PublishType.FULL_VERSION, workflowId, participantDigests);
    InOrder reconciliationOrder = inOrder(versionRepository, recordedParticipantDigestService);
    reconciliationOrder.verify(versionRepository).findByTenantIdAndIdForUpdate("tenant-1", 10L);
    reconciliationOrder
        .verify(recordedParticipantDigestService)
        .recordVerifiedDigests(
            "tenant-1", PublishType.FULL_VERSION, workflowId, participantDigests);
    verify(publishAttemptService).markFullVersionSucceeded(workflowId);
  }

  @Test
  void legacyPendingAttemptWithoutSelectionIsDeniedWithoutDigestBackfill() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    attempt.setId(101L);
    attempt.setRequestDigest(null);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.reconcileFullVersionPublish(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertTrue(thrown.getMessage().contains("PUBLISH_SELECTION_REQUIRED"));
    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    assertEquals(null, attempt.getRequestDigest());
  }

  @Test
  void legacyPendingMixedScopeAttemptIsDeniedWithoutSelectionBackfill() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    attempt.setId(101L);
    attempt.setRequestDigest(null);
    attempt.setBaseVersionId(3L);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.reconcileFullVersionPublish(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertTrue(thrown.getMessage().contains("PUBLISH_SELECTION_REQUIRED"));
    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
    assertEquals(null, attempt.getRequestDigest());
  }

  @Test
  void fullAttemptValidationRejectsMixedScriptPatchScopeEvenWithDigest() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    attempt.setScriptPatchVersion("legacy-patch");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    assertThrows(
        IllegalStateException.class,
        () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(publishedReleaseBundleService, never())
        .findPublishedReleaseBundle(any(String.class), any(Long.class));
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
  void postBundleFailureAfterRollbackMarksFailureAndRetainsExportedAssets() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    List<PublishParticipantDigestDto> participantDigests =
        participantDigests(selected.selection(), 10L);
    ExportedAssetManifest manifest = logoManifest();
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of("logo.png"),
            participantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
            UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
            "opaque-owner-issued-release-reference",
            1,
            List.of(logoProof()));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
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
                3L,
                MANIFEST_HASH,
                workflowId,
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png")));
    when(publishedReleaseBundleService.createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class),
            org.mockito.ArgumentMatchers.nullable(WorldPublishedStartLocationEvidence.class)))
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

    VersionAssetArtifactStateDto stagedIntent =
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
            LocalDateTime.parse("2026-04-14T12:00:00"),
            List.of());
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenReturn(stagedIntent);
    VersionAssetArtifactStateDto retainedCandidate =
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
            List.of("logo.png"));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(
            Optional.empty(),
            Optional.of(stagedIntent),
            Optional.of(retainedCandidate),
            Optional.of(retainedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(null, logoManifest(), logoManifest());
    PublishWorkflowSnapshot snapshot =
        service.reconcileSelectedPublicationMechanics(selected.request());

    assertEquals("FAILED", snapshot.status());
    verify(publishAttemptService)
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
  }

  @Test
  void exactRetryResumesVerifiedStagedCandidateWithoutRestaging() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    VersionAssetArtifactStateDto stagedCandidate =
        stagedArtifact("tenant-1", 10L, 1, 2L, MANIFEST_HASH, workflowId, List.of("logo.png"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.of(stagedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(logoManifest());
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests(selected.selection(), 10L));
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenThrow(new AssetExportOutcomePendingException("export readback is still pending"));

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    verify(versionAssetArtifactService, never())
        .stageExport(any(String.class), any(Long.class), any(Integer.class), any(String.class));
    verify(assetExportService).exportAssets("tenant-1", 1);
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
  }

  @Test
  void participantGateFailureWithPriorStagedCandidateRemainsPending() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    Game game = new Game();
    game.setTenantId("tenant-1");
    VersionAssetArtifactStateDto stagedCandidate =
        stagedArtifact("tenant-1", 10L, 1, 2L, MANIFEST_HASH, workflowId, List.of("logo.png"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.of(stagedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(logoManifest());
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests(selected.selection(), 10L));
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"))
        .when(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    verify(versionRepository).findByTenantIdAndIdForUpdate("tenant-1", 10L);
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
  }

  @ParameterizedTest
  @MethodSource("invalidStagedRetryEvidence")
  void invalidStagedRetryEvidenceRemainsPendingWithoutExport(
      VersionAssetArtifactStateDto stagedArtifact, ExportedAssetManifest candidate) {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(Optional.of(stagedArtifact));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L)).thenReturn(candidate);

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
  }

  private static Stream<Arguments> invalidStagedRetryEvidence() {
    String workflowId = selectedWorkflowId("workflow-1");
    return Stream.of(
        Arguments.of(
            stagedArtifact("tenant-2", 10L, 1, 2L, MANIFEST_HASH, workflowId, List.of("logo.png")),
            logoManifest()),
        Arguments.of(
            stagedArtifact("tenant-1", 11L, 1, 2L, MANIFEST_HASH, workflowId, List.of("logo.png")),
            logoManifest()),
        Arguments.of(
            stagedArtifact("tenant-1", 10L, 2, 2L, MANIFEST_HASH, workflowId, List.of("logo.png")),
            logoManifest()),
        Arguments.of(
            stagedArtifact(
                "tenant-1", 10L, 1, 2L, MANIFEST_HASH, "other-workflow", List.of("logo.png")),
            logoManifest()),
        Arguments.of(
            stagedArtifact("tenant-1", 10L, 1, 0L, MANIFEST_HASH, workflowId, List.of("logo.png")),
            logoManifest()),
        Arguments.of(
            stagedArtifact("tenant-1", 10L, 1, 2L, MANIFEST_HASH, workflowId, List.of("logo.png")),
            emptyManifest()),
        Arguments.of(
            stagedArtifact("tenant-1", 10L, 1, 2L, MANIFEST_HASH, workflowId, List.of("logo.png")),
            new ExportedAssetManifest(
                MANIFEST_HASH, 2, List.of("logo.png"), List.of(logoProof()))));
  }

  private static VersionAssetArtifactStateDto stagedArtifact(
      String tenantId,
      long versionId,
      int versionNumber,
      long stateEpoch,
      String manifestHash,
      String workflowId,
      List<String> assetKeys) {
    return new VersionAssetArtifactStateDto(
        tenantId,
        versionId,
        versionNumber,
        "STAGED",
        stateEpoch,
        manifestHash,
        workflowId,
        null,
        null,
        LocalDateTime.parse("2026-04-14T12:00:00"),
        assetKeys);
  }

  @Test
  void changedArtifactDigestKeepsProofBearingPublicationPending() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.PUBLISHED);
    List<PublishParticipantDigestDto> digests = participantDigests(selected.selection(), 10L);
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            MANIFEST_HASH,
            List.of("logo.png"),
            digests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now(),
            UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
            UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
            "opaque-owner-issued-release-reference",
            1,
            List.of(logoProof()));
    VersionAssetArtifactStateDto artifact =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            10L,
            1,
            "PUBLISHED",
            4L,
            MANIFEST_HASH,
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of("logo.png"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(
            new ExportedAssetManifest(
                MANIFEST_HASH, 1, List.of("logo.png"), List.of(logoProof(OTHER_LOGO_DIGEST))));

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileFullVersionPublish(selected.request()));

    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never()).markFullVersionSucceeded(any(String.class));
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
  }

  @Test
  void lostExporterAcknowledgementLeavesAttemptPendingForExactReadback() {
    assertExporterFailureLeavesPending(
        new AssetExportOutcomePendingException("manifest write acknowledgement lost"));
  }

  @Test
  void laterExporterConflictLeavesAttemptPendingForEarlierWriteReadback() {
    assertExporterFailureLeavesPending(
        new IllegalStateException("later immutable object key conflict after an earlier write"));
  }

  private void assertExporterFailureLeavesPending(RuntimeException exportFailure) {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests(selected.selection(), 10L));
    when(assetExportService.exportAssets("tenant-1", 1)).thenThrow(exportFailure);
    mockStagedExport(10L, 1, workflowId);

    assertThrows(
        VersionPublishCommandServiceImpl.PendingReconciliationException.class,
        () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void finalizationFailureWithFailedReadbackLeavesAttemptPending() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    ExportedAssetManifest manifest = emptyManifest();
    IllegalStateException operationFailure =
        new IllegalStateException("version finalization failed");
    IllegalStateException readFailure = new IllegalStateException("publication read failed");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests(selected.selection(), 10L));
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty())
        .thenThrow(readFailure);
    org.mockito.Mockito.doThrow(
            new PublishAttemptService.FullVersionTransactionException(operationFailure))
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    mockStagedExport(10L, 1, workflowId);
    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    assertTrue(thrown.getMessage().contains("readback failed"));
    assertEquals(readFailure, thrown.getCause());
    assertEquals(1, readFailure.getSuppressed().length);
    assertEquals(operationFailure, readFailure.getSuppressed()[0]);
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.Code.class,
      names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void transientParticipantReadLeavesFullAttemptPending(Status.Code statusCode) {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenThrow(new StatusRuntimeException(Status.fromCode(statusCode)));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    assertTrue(thrown.getMessage().contains("temporarily unavailable"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void ambiguousFinalizationLeavesPendingAttemptAndDoesNotCleanAssets() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    ExportedAssetManifest manifest = emptyManifest();
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests(selected.selection(), 10L));
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new IllegalStateException("commit outcome unknown"))
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    mockStagedExport(10L, 1, workflowId);
    assertThrows(
        IllegalStateException.class,
        () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void ambiguousFinalizationWithFailedReadbackLeavesAttemptPending() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    ExportedAssetManifest manifest = emptyManifest();
    IllegalStateException ambiguousCommit = new IllegalStateException("commit outcome unknown");
    IllegalStateException readFailure = new IllegalStateException("publication read failed");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests(selected.selection(), 10L));
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty())
        .thenThrow(readFailure);
    org.mockito.Mockito.doThrow(ambiguousCommit)
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    mockStagedExport(10L, 1, workflowId);
    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    assertTrue(thrown.getMessage().contains("readback failed"));
    assertEquals(readFailure, thrown.getCause());
    assertEquals(1, readFailure.getSuppressed().length);
    assertEquals(ambiguousCommit, readFailure.getSuppressed()[0]);
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void failureMarkingCommitUncertaintyLeavesAttemptPendingAndDoesNotCleanAssets() {
    SelectedRequest selected = selectedRequest("tenant-1", 10L, "notes", "workflow-1", "1");
    String workflowId = selected.request().publishWorkflowId();
    PublishAttempt attempt =
        selectedAttempt(
            PublishAttemptStatus.PENDING, 10L, 1, selected.request(), selected.selection());
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    Game game = new Game();
    game.setTenantId("tenant-1");
    ExportedAssetManifest manifest = emptyManifest();
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests(selected.selection(), 10L));
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    java.util.concurrent.atomic.AtomicInteger transactionCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (transactionCalls.getAndIncrement() == 0) {
                throw new PublishAttemptService.FullVersionTransactionException(
                    new IllegalStateException("version finalization failed"));
              }
              ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
              throw new IllegalStateException("failure-marking commit outcome unknown");
            })
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    VersionAssetArtifactStateDto stagedIntent =
        stagedArtifact("tenant-1", 10L, 1, 1L, null, workflowId, List.of());
    VersionAssetArtifactStateDto retainedCandidate =
        stagedArtifact("tenant-1", 10L, 1, 2L, manifest.manifestHash(), workflowId, List.of());
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenReturn(stagedIntent);
    when(versionAssetArtifactService.findState("tenant-1", 10L))
        .thenReturn(
            Optional.empty(),
            Optional.of(stagedIntent),
            Optional.of(retainedCandidate),
            Optional.of(retainedCandidate));
    when(versionAssetArtifactService.getExportCandidate("tenant-1", 10L))
        .thenReturn(null, manifest, manifest);
    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () -> service.reconcileSelectedPublicationMechanics(selected.request()));

    assertTrue(thrown.getMessage().contains("failure marking commit outcome is unknown"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(versionRepository).findByTenantIdAndIdForUpdate("tenant-1", 10L);
    verify(publishAttemptService)
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
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

  /** Creates explicit synthetic selected-commit facts for internal command-mechanics tests only. */
  private SelectedRequest selectedRequest(
      String tenantId,
      long versionId,
      String notes,
      String publishRequestId,
      String expectedVersionStateEpoch) {
    UUID canonicalTenantId = UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda");
    UUID canonicalVersionId = UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8");
    UUID selectedCommitRequestId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    UUID selectedCommitId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    TargetProof target =
        new TargetProof(
            canonicalTenantId,
            canonicalVersionId,
            versionId,
            tenantId,
            1L,
            tenantId,
            "NEW_GAME_ROW");
    List<RevisionPayload> revisions =
        List.of(
            new RevisionPayload(
                "0",
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "synthetic internal publication fixture"),
            new RevisionPayload(
                "1",
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                Owner.WORLD_MANAGEMENT,
                "synthetic internal publication fixture"),
            new RevisionPayload(
                "2",
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                Owner.ENTITY_MANAGEMENT,
                "synthetic internal publication fixture"),
            new RevisionPayload(
                "3",
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                Owner.GAME_LOGIC,
                "synthetic internal publication fixture"),
            new RevisionPayload(
                "4",
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                Owner.AUTOMATION_SCRIPTING,
                "synthetic internal publication fixture"));
    List<AffectedUnit> affectedUnits =
        List.of(
            new AffectedUnit(Owner.GAME_DESIGN_CONTROL_PLANE, "fixture", "gd", "version", "1", "1"),
            new AffectedUnit(Owner.WORLD_MANAGEMENT, "fixture", "world", "version", "1", "1"),
            new AffectedUnit(Owner.ENTITY_MANAGEMENT, "fixture", "entity", "version", "1", "1"),
            new AffectedUnit(Owner.GAME_LOGIC, "fixture", "logic", "version", "1", "1"),
            new AffectedUnit(
                Owner.AUTOMATION_SCRIPTING, "fixture", "automation", "version", "1", "1"));
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            target,
            selectedCommitRequestId,
            selectedCommitId,
            "base-commit",
            revisions,
            affectedUnits);
    PublishIntent intent =
        new PublishIntent(
            canonicalTenantId,
            canonicalVersionId,
            publishRequestId,
            expectedVersionStateEpoch,
            notes,
            selectedCommitRequestId,
            selectedCommitId,
            binding.digest());
    String resultVectorJson =
        "["
            + String.join(
                ",",
                java.util.Arrays.stream(Owner.values())
                    .map(
                        owner ->
                            "{\"commitId\":\""
                                + selectedCommitId
                                + "\",\"digest\":\""
                                + binding.digest()
                                + "\",\"owner\":\""
                                + owner.name()
                                + "\",\"status\":\"APPLIED\"}")
                    .toList())
            + "]";
    VisibilityFence fence =
        new VisibilityFence(
            target,
            selectedCommitRequestId,
            selectedCommitId,
            binding.digest(),
            resultVectorJson,
            OffsetDateTime.now(ZoneOffset.UTC));
    AuthoredDraftPublishSelection selection =
        AuthoredDraftPublishSelection.capture(
            intent, target, new PublicationEvidence(binding, fence));
    SelectionSnapshot snapshot =
        new SelectionSnapshot(selection, OffsetDateTime.now(ZoneOffset.UTC));
    when(authoredSelections.reserve(intent)).thenReturn(snapshot);
    when(authoredSelections.readByPublishRequest(canonicalTenantId, publishRequestId))
        .thenReturn(Optional.of(snapshot));
    PublishWorkflowRequest request =
        new PublishWorkflowRequest(
            tenantId,
            notes,
            publishRequestId,
            TemporalVersionPublishOrchestrator.workflowId(
                canonicalTenantId.toString(), publishRequestId),
            intent);
    return new SelectedRequest(selection, intent, snapshot, request);
  }

  private static String selectedWorkflowId(String publishRequestId) {
    return TemporalVersionPublishOrchestrator.workflowId(
        "67d7b75b-42d1-4ac6-9572-684c5e633cda", publishRequestId);
  }

  private PublishAttempt selectedAttempt(
      PublishAttemptStatus status,
      long versionId,
      int versionNumber,
      PublishWorkflowRequest request,
      AuthoredDraftPublishSelection selection) {
    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId(request.tenantId());
    attempt.setVersionId(versionId);
    attempt.setVersionNumber(versionNumber);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setPublishWorkflowId(request.publishWorkflowId());
    attempt.setStatus(status);
    attempt.setRequestDigest(selection.digest());
    return attempt;
  }

  private List<PublishParticipantDigestDto> participantDigests(
      AuthoredDraftPublishSelection selection, long versionId) {
    String commitId = selection.selectedCommit().commitId().toString();
    String scope = Long.toString(versionId);
    return List.of(
        new PublishParticipantDigestDto(
            "GAME_DESIGN_CONTROL_PLANE",
            scope,
            commitId,
            "sha256:" + "1".repeat(64),
            1,
            null,
            null),
        new PublishParticipantDigestDto(
            "WORLD_MANAGEMENT", scope, commitId, "sha256:" + "2".repeat(64), 1, null, null),
        new PublishParticipantDigestDto(
            "ENTITY_MANAGEMENT", scope, commitId, "sha256:" + "3".repeat(64), 1, null, null),
        new PublishParticipantDigestDto(
            "GAME_LOGIC", scope, commitId, "sha256:" + "4".repeat(64), 1, null, null),
        new PublishParticipantDigestDto(
            "AUTOMATION_SCRIPTING", scope, commitId, "sha256:" + "5".repeat(64), 1, null, null));
  }

  private record SelectedRequest(
      AuthoredDraftPublishSelection selection,
      PublishIntent intent,
      SelectionSnapshot snapshot,
      PublishWorkflowRequest request) {}

  private Version fullVersion(long versionId, int versionNumber, VersionLifecycleState state) {
    Version version = new Version();
    version.setId(versionId);
    version.setTenantId("tenant-1");
    version.setVersionNumber(versionNumber);
    version.setVersionState(state);
    version.setVersionStateEpoch(state == VersionLifecycleState.PUBLISHED ? 2L : 1L);
    version.setUpdatedAt(LocalDateTime.now());
    setCanonicalSource(version);
    return version;
  }

  private void mockStagedExport(long versionId, int versionNumber, String workflowId) {
    VersionAssetArtifactStateDto staged =
        new VersionAssetArtifactStateDto(
            "tenant-1",
            versionId,
            versionNumber,
            "STAGED",
            1L,
            null,
            workflowId,
            null,
            null,
            LocalDateTime.parse("2026-04-14T12:00:00"),
            List.of());
    when(versionAssetArtifactService.stageExport("tenant-1", versionId, versionNumber, workflowId))
        .thenReturn(staged);
    when(versionAssetArtifactService.findState("tenant-1", versionId))
        .thenReturn(Optional.empty(), Optional.of(staged), Optional.empty());
  }

  private static void setCanonicalSource(Version version) {
    version.setCanonicalTenantId(UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"));
    version.setCanonicalVersionId(UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"));
    version.setIdentitySourceGameRowId(1L);
    version.setIdentitySourceGameTenantKey(version.getTenantId());
    version.setIdentitySourceProvenanceKind("NEW_GAME_ROW");
  }

  private static ExportedAssetManifest emptyManifest() {
    return new ExportedAssetManifest(MANIFEST_HASH, 1, List.of(), List.of());
  }

  private static ExportedAssetManifest logoManifest() {
    return new ExportedAssetManifest(MANIFEST_HASH, 1, List.of("logo.png"), List.of(logoProof()));
  }

  private static PublishedArtifactDigest logoProof() {
    return logoProof(LOGO_DIGEST);
  }

  private static PublishedArtifactDigest logoProof(String digest) {
    return new PublishedArtifactDigest(
        "logo.png",
        "BINARY",
        "artifacts/sha256/" + digest.substring("sha256:".length()),
        digest,
        "image/png",
        1);
  }

  private List<PublishParticipantDigestDto> participantDigests() {
    return List.of(
        new PublishParticipantDigestDto(
            "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest", 1, null, null));
  }
}
