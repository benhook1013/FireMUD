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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
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
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
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

  private VersionPublishCommandServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
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
            recordedParticipantDigestService);
  }

  @Test
  void publishFullVersionUsesTenantScopedVersionSequence() {
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
    Version savedPublished = new Version();
    savedPublished.setId(10L);
    savedPublished.setTenantId("tenant-1");
    savedPublished.setVersionNumber(8);
    savedPublished.setNotes("notes");
    savedPublished.setVersionState(VersionLifecycleState.PUBLISHED);
    savedPublished.setVersionStateEpoch(2L);
    savedPublished.setUpdatedAt(LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft, savedPublished);

    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId("tenant-1");
    attempt.setVersionId(10L);
    attempt.setVersionNumber(8);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setPublishWorkflowId("publish:tenant-1:publish-request:workflow-1");
    attempt.setRequestDigest(
        PublicationDigestRequestBinding.full("tenant-1", "10", "workflow-1").requestDigest());
    when(publishAttemptRepository.findByPublishWorkflowId(
            "publish:tenant-1:publish-request:workflow-1"))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(
            Optional.of(savedDraft),
            Optional.of(savedDraft),
            Optional.of(savedDraft),
            Optional.of(savedDraft),
            Optional.of(savedPublished));

    ExportedAssetManifest exportedManifest =
        new ExportedAssetManifest("abc123", List.of("logo.png", "manifest.json"));
    when(assetExportService.exportAssets("tenant-1", 8)).thenReturn(exportedManifest);
    List<PublishParticipantDigestDto> participantDigests =
        List.of(
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest-1", 1, null, null));
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
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                8,
                "EXPORTED_UNATTESTED",
                1L,
                "abc123",
                "workflow-1",
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png", "manifest.json")));
    when(publishedReleaseBundleService.createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class)))
        .thenReturn(
            new PublishedReleaseBundleDto(
                1L,
                "tenant-1",
                10L,
                8,
                "v1",
                "publish:tenant-1:publish-request:workflow-1",
                "abc123",
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
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                8,
                "PUBLISHED",
                2L,
                "abc123",
                "workflow-1",
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png", "manifest.json")));

    VersionDto dto =
        service.publishFullVersion(
            "tenant-1", "notes", "workflow-1", "publish:tenant-1:publish-request:workflow-1");

    assertEquals(8, dto.versionNumber());
    assertEquals(VersionLifecycleState.PUBLISHED, dto.versionState());
    assertEquals(2L, dto.versionStateEpoch());
    verify(publishAttemptService)
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(assetExportService).exportAssets("tenant-1", 8);
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(any(String.class), any(), any(String.class), any(List.class));

    InOrder publishOrder = inOrder(publishGateService, publishAttemptService, assetExportService);
    publishOrder
        .verify(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));
    publishOrder.verify(assetExportService).exportAssets("tenant-1", 8);
    publishOrder
        .verify(publishAttemptService)
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
  }

  @Test
  void publishFullVersionPropagatesTypedPublishGateFailures() {
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

    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId("tenant-1");
    attempt.setVersionId(10L);
    attempt.setVersionNumber(1);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setPublishWorkflowId("publish:tenant-1:publish-request:workflow-1");
    attempt.setRequestDigest(
        PublicationDigestRequestBinding.full("tenant-1", "10", "workflow-1").requestDigest());
    when(publishAttemptRepository.findByPublishWorkflowId(
            "publish:tenant-1:publish-request:workflow-1"))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(
            List.of(
                new PublishParticipantDigestDto(
                    "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest-1", 1, null, null)));
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
                "recorded digest mismatch"))
        .when(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(any(String.class), any(), any(List.class));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () ->
                service.publishFullVersion(
                    "tenant-1",
                    "notes",
                    "workflow-1",
                    "publish:tenant-1:publish-request:workflow-1"));

    assertEquals(PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, thrown.failureCode());
    verify(publishAttemptService)
        .markFullVersionFailed(
            any(String.class),
            org.mockito.ArgumentMatchers.eq("RECORDED_CONTENT_DIGEST_MISMATCH"),
            org.mockito.ArgumentMatchers.eq("recorded digest mismatch"));
  }

  @Test
  void publishFullVersionGateRejectionDoesNotRecordDigestsOrExportAssets() {
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

    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId("tenant-1");
    attempt.setVersionId(10L);
    attempt.setVersionNumber(1);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setPublishWorkflowId("publish:tenant-1:publish-request:workflow-1");
    attempt.setRequestDigest(
        PublicationDigestRequestBinding.full("tenant-1", "10", "workflow-1").requestDigest());
    when(publishAttemptRepository.findByPublishWorkflowId(
            "publish:tenant-1:publish-request:workflow-1"))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));

    List<PublishParticipantDigestDto> participantDigests =
        List.of(
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest-1", 1, null, null));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests);
    org.mockito.Mockito.doThrow(
            new PublishGateFailureException(
                PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, "gate rejected"))
        .when(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () ->
                service.publishFullVersion(
                    "tenant-1",
                    "notes",
                    "workflow-1",
                    "publish:tenant-1:publish-request:workflow-1"));

    assertEquals(PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, thrown.failureCode());
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void publishFullVersionDeletesExportedAssetsWhenAttestationWriteFails() {
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

    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId("tenant-1");
    attempt.setVersionId(10L);
    attempt.setVersionNumber(1);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setPublishWorkflowId("publish:tenant-1:publish-request:workflow-1");
    attempt.setRequestDigest(
        PublicationDigestRequestBinding.full("tenant-1", "10", "workflow-1").requestDigest());
    when(publishAttemptRepository.findByPublishWorkflowId(
            "publish:tenant-1:publish-request:workflow-1"))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenReturn(new ExportedAssetManifest("abc123", List.of("manifest.json")));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(
            List.of(
                new PublishParticipantDigestDto(
                    "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest-1", 1, null, null)));
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
                1L,
                "abc123",
                "workflow-1",
                null,
                null,
                LocalDateTime.now(),
                List.of("manifest.json")));
    org.mockito.Mockito.doThrow(new IllegalStateException("bundle failed"))
        .when(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.publishFullVersion(
                "tenant-1", "notes", "workflow-1", "publish:tenant-1:publish-request:workflow-1"));

    verify(assetExportService).deleteExportedAssets("tenant-1", 1, List.of("manifest.json"));
  }

  @Test
  void sameRequestReplayRequiresAndReconcilesExactCommittedEvidence() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.PUBLISHED);
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            "manifest-hash",
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
            "manifest-hash",
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(bundle);
    when(versionAssetArtifactService.getState("tenant-1", 10L)).thenReturn(artifact);

    PublishWorkflowSnapshot snapshot =
        service.reconcileFullVersionPublish(
            new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

    assertEquals("SUCCEEDED", snapshot.status());
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(
            "tenant-1", PublishType.FULL_VERSION, workflowId, participantDigests);
  }

  @Test
  void canonicalLegacyFullAttemptBackfillsDigestBeforeRetryValidation() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.SUCCEEDED, 10L, 1, workflowId);
    attempt.setId(101L);
    attempt.setRequestDigest(null);
    Version version = fullVersion(10L, 1, VersionLifecycleState.PUBLISHED);
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishAttemptRepository.backfillFullVersionRequestDigestIfAbsent(
            org.mockito.ArgumentMatchers.eq(101L),
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq(workflowId),
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.eq(
                PublicationDigestRequestBinding.full("tenant-1", "10", "workflow-1")
                    .requestDigest())))
        .thenAnswer(
            invocation -> {
              attempt.setRequestDigest(
                  PublicationDigestRequestBinding.full("tenant-1", "10", "workflow-1")
                      .requestDigest());
              return Optional.of(attempt);
            });

    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            "manifest-hash",
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
            "manifest-hash",
            workflowId,
            null,
            null,
            LocalDateTime.now(),
            List.of("manifest.json"));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(bundle);
    when(versionAssetArtifactService.getState("tenant-1", 10L)).thenReturn(artifact);

    PublishWorkflowSnapshot snapshot =
        service.reconcileFullVersionPublish(
            new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

    assertEquals("SUCCEEDED", snapshot.status());
    verify(publishAttemptRepository)
        .backfillFullVersionRequestDigestIfAbsent(
            101L,
            "tenant-1",
            workflowId,
            10L,
            1,
            PublicationDigestRequestBinding.full("tenant-1", "10", "workflow-1").requestDigest());
    verify(publishGateService, never())
        .collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class));
  }

  @Test
  void legacyFullAttemptWithMismatchedVersionEvidenceIsNotRewritten() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    attempt.setId(101L);
    attempt.setRequestDigest(null);
    Version mismatchedVersion = fullVersion(10L, 2, VersionLifecycleState.DRAFT);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(mismatchedVersion));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

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
  void legacyFullMixedScopeAttemptIsNotRebound() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    attempt.setId(101L);
    attempt.setRequestDigest(null);
    attempt.setBaseVersionId(3L);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

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
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    attempt.setScriptPatchVersion("legacy-patch");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    verify(versionRepository, never()).findByTenantIdAndId(any(String.class), any(Long.class));
    verify(publishedReleaseBundleService, never())
        .getPublishedReleaseBundle(any(String.class), any(Long.class));
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
        .getPublishedReleaseBundle(any(String.class), any(Long.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void postBundleFailureAfterRollbackMarksFailureAndCleansExportedAssets() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    ExportedAssetManifest manifest =
        new ExportedAssetManifest("manifest-hash", List.of("manifest.json"));
    PublishedReleaseBundleDto bundle =
        new PublishedReleaseBundleDto(
            1L,
            "tenant-1",
            10L,
            1,
            "v1",
            workflowId,
            "manifest-hash",
            List.of("manifest.json"),
            participantDigests,
            "generation-revision",
            false,
            null,
            LocalDateTime.now());
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
                1L,
                "manifest-hash",
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
            any(List.class)))
        .thenReturn(bundle);
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 10L)).thenReturn(null);
    when(versionAssetArtifactService.getState("tenant-1", 10L))
        .thenThrow(new IllegalArgumentException("artifact absent"));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              // The production transaction rolls managed entity changes back before readback.
              version.setVersionState(VersionLifecycleState.DRAFT);
              version.setVersionStateEpoch(1L);
              throw new IllegalStateException("version finalization failed");
            })
        .when(versionRepository)
        .save(any(Version.class));

    PublishWorkflowSnapshot snapshot =
        service.reconcileFullVersionPublish(
            new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

    assertEquals("FAILED", snapshot.status());
    verify(publishAttemptService)
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository).delete(any(Version.class));
    verify(assetExportService).deleteExportedAssets("tenant-1", 1, List.of("manifest.json"));
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.Code.class,
      names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void transientParticipantReadLeavesFullAttemptPending(Status.Code statusCode) {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
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
            () ->
                service.reconcileFullVersionPublish(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertTrue(thrown.getMessage().contains("temporarily unavailable"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void ambiguousFinalizationLeavesPendingAttemptAndDoesNotCleanAssets() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    ExportedAssetManifest manifest =
        new ExportedAssetManifest("manifest-hash", List.of("manifest.json"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 10L)).thenReturn(null);
    when(versionAssetArtifactService.getState("tenant-1", 10L))
        .thenThrow(new IllegalArgumentException("artifact absent"));
    org.mockito.Mockito.doThrow(new IllegalStateException("commit outcome unknown"))
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    assertThrows(
        IllegalStateException.class,
        () ->
            service.reconcileFullVersionPublish(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
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
            "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest", 1, null, null));
  }
}
