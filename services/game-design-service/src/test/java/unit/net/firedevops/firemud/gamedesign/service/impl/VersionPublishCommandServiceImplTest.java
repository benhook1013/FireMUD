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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

  private VersionPublishCommandServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    when(publishedReleaseBundleService.findPublishedReleaseBundle(
            any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState(any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());
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
            recordedParticipantDigestService);
  }

  private PublishWorkflowSnapshot mechanicsHarness(PublishWorkflowRequest request) {
    try {
      Method mechanics =
          VersionPublishCommandServiceImpl.class.getDeclaredMethod(
              "reconcileFullVersionPublishMechanics", PublishWorkflowRequest.class);
      mechanics.setAccessible(true);
      return (PublishWorkflowSnapshot) mechanics.invoke(service, request);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      if (cause instanceof Error error) throw error;
      throw new AssertionError("full-version publication mechanics failed", cause);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("private publication mechanics harness is unavailable", exception);
    }
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
    verify(publishAttemptRepository, never())
        .backfillFullVersionRequestDigestIfAbsent(
            any(Long.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Integer.class),
            any(String.class));
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
  void privateMechanicsReservesVersionUsingTenantScopedSequence() {
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

    ExportedAssetManifest exportedManifest = exportedManifest(List.of("logo.png", "manifest.json"));
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
                MANIFEST_HASH,
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
            publishedReleaseBundleDto(
                1L,
                "tenant-1",
                10L,
                8,
                "v1",
                "publish:tenant-1:publish-request:workflow-1",
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
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
                "tenant-1",
                10L,
                8,
                "PUBLISHED",
                2L,
                MANIFEST_HASH,
                "workflow-1",
                null,
                null,
                LocalDateTime.now(),
                List.of("logo.png", "manifest.json")));

    PublishWorkflowSnapshot snapshot =
        mechanicsHarness(
            new PublishWorkflowRequest(
                "tenant-1", "notes", "workflow-1", "publish:tenant-1:publish-request:workflow-1"));

    assertEquals("SUCCEEDED", snapshot.status());
    assertEquals(10L, snapshot.versionId());
    assertEquals(8, snapshot.versionNumber());
    verify(publishAttemptService)
        .createFullVersionAttempt(any(VersionDto.class), any(String.class), any(String.class));
    verify(assetExportService).exportAssets("tenant-1", 8);
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(any(String.class), any(), any(String.class), any(List.class));

    InOrder publishOrder =
        inOrder(
            publishGateService,
            recordedParticipantDigestService,
            versionAssetArtifactService,
            assetExportService,
            publishAttemptService);
    publishOrder
        .verify(publishGateService)
        .assertGatePassed(any(VersionDto.class), any(List.class));
    publishOrder
        .verify(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(any(String.class), any(PublishType.class), any(List.class));
    publishOrder
        .verify(versionAssetArtifactService)
        .stageExport("tenant-1", 10L, 8, "publish:tenant-1:publish-request:workflow-1");
    publishOrder.verify(assetExportService).exportAssets("tenant-1", 8);
    publishOrder
        .verify(publishAttemptService)
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
  }

  @Test
  void privateMechanicsRecordsTypedPublishGateFailures() {
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

    PublishWorkflowSnapshot snapshot =
        mechanicsHarness(
            new PublishWorkflowRequest(
                "tenant-1", "notes", "workflow-1", "publish:tenant-1:publish-request:workflow-1"));

    assertEquals("FAILED", snapshot.status());
    assertEquals(
        PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH.name(), snapshot.failureCode());
    verify(publishAttemptService)
        .markFullVersionFailed(
            any(String.class),
            org.mockito.ArgumentMatchers.eq("RECORDED_CONTENT_DIGEST_MISMATCH"),
            org.mockito.ArgumentMatchers.eq("recorded digest mismatch"));
  }

  @Test
  void privateMechanicsGateRejectionDoesNotRecordDigestsOrExportAssets() {
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

    PublishWorkflowSnapshot snapshot =
        mechanicsHarness(
            new PublishWorkflowRequest(
                "tenant-1", "notes", "workflow-1", "publish:tenant-1:publish-request:workflow-1"));

    assertEquals("FAILED", snapshot.status());
    assertEquals(
        PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH.name(), snapshot.failureCode());
    verify(publishAttemptService, never())
        .recordFullVersionParticipantDigests(any(String.class), any(List.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void privateMechanicsDeletesExportedAssetsWhenAttestationWriteFails() {
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
        .thenReturn(exportedManifest(List.of("manifest.json")));
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
                MANIFEST_HASH,
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

    PublishWorkflowSnapshot snapshot =
        mechanicsHarness(
            new PublishWorkflowRequest(
                "tenant-1", "notes", "workflow-1", "publish:tenant-1:publish-request:workflow-1"));

    assertEquals("FAILED", snapshot.status());

    verify(assetExportService).deleteExportedAssets("tenant-1", 1, List.of("manifest.json"));
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
  void uncertainAssetExportRemainsPendingForExactRetry() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenThrow(new AssetExportOutcomePendingException("exact object readback is unavailable"));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () ->
                mechanicsHarness(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertTrue(thrown.getMessage().contains("asset export outcome is pending"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void uncertainAssetStagingRemainsPendingBeforeAnyExternalExportOrFinalization() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(versionAssetArtifactService.stageExport("tenant-1", 10L, 1, workflowId))
        .thenThrow(new IllegalStateException("stage commit outcome is unknown"));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () ->
                mechanicsHarness(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertTrue(thrown.getMessage().contains("asset export staging outcome is pending"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(versionAssetArtifactService).stageExport("tenant-1", 10L, 1, workflowId);
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
            any(List.class));
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
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
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
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () ->
                mechanicsHarness(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertTrue(thrown.getMessage().contains("incomplete"));
    verify(publishAttemptService, never()).executeFullVersionTransaction(any());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never()).exportAssets(any(String.class), any(Integer.class));
  }

  @Test
  void privateMechanicsReconcilesPendingAttemptThroughCurrentGates() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.PUBLISHED);
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
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.of(bundle));
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.of(artifact));

    PublishWorkflowSnapshot snapshot =
        mechanicsHarness(new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

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
            mechanicsHarness(
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
            mechanicsHarness(
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
            mechanicsHarness(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

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
  void privateMechanicsPostBundleFailureMarksFailureAndCleansExportedAssets() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    Game game = new Game();
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    List<PublishParticipantDigestDto> participantDigests = participantDigests();
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
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
            any(List.class)))
        .thenReturn(bundle);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.empty());
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
        mechanicsHarness(new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId));

    assertEquals("FAILED", snapshot.status());
    verify(publishAttemptService)
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService).deleteExportedAssets("tenant-1", 1, List.of("manifest.json"));
  }

  @Test
  void finalizationFailureWithFailedReadbackLeavesAttemptPending() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    IllegalStateException operationFailure =
        new IllegalStateException("version finalization failed");
    IllegalStateException readFailure = new IllegalStateException("publication read failed");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
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
            () ->
                mechanicsHarness(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

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
                mechanicsHarness(
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
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new IllegalStateException("commit outcome unknown"))
        .when(publishAttemptService)
        .executeFullVersionTransaction(any());

    assertThrows(
        IllegalStateException.class,
        () ->
            mechanicsHarness(
                new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
    verify(publishAttemptService, never())
        .markFullVersionFailed(any(String.class), any(String.class), any(String.class));
    verify(versionRepository, never()).delete(any(Version.class));
    verify(assetExportService, never())
        .deleteExportedAssets(any(String.class), any(Integer.class), any(List.class));
  }

  @Test
  void ambiguousFinalizationWithFailedReadbackLeavesAttemptPending() {
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    IllegalStateException ambiguousCommit = new IllegalStateException("commit outcome unknown");
    IllegalStateException readFailure = new IllegalStateException("publication read failed");
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
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
            () ->
                mechanicsHarness(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

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
    String workflowId = "publish:tenant-1:publish-request:workflow-1";
    PublishAttempt attempt = fullAttempt(PublishAttemptStatus.PENDING, 10L, 1, workflowId);
    Version version = fullVersion(10L, 1, VersionLifecycleState.DRAFT);
    Game game = new Game();
    game.setTenantId("tenant-1");
    ExportedAssetManifest manifest = exportedManifest(List.of("manifest.json"));
    when(publishAttemptRepository.findByPublishWorkflowId(workflowId))
        .thenReturn(Optional.of(attempt));
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L)).thenReturn(Optional.of(version));
    when(publishGateService.collectFullVersionParticipantDigests(
            any(VersionDto.class), any(String.class), any(String.class)))
        .thenReturn(participantDigests());
    when(assetExportService.exportAssets("tenant-1", 1)).thenReturn(manifest);
    when(publishedReleaseBundleService.findPublishedReleaseBundle("tenant-1", 10L))
        .thenReturn(Optional.empty());
    when(versionAssetArtifactService.findState("tenant-1", 10L)).thenReturn(Optional.empty());
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

    VersionPublishCommandServiceImpl.PendingReconciliationException thrown =
        assertThrows(
            VersionPublishCommandServiceImpl.PendingReconciliationException.class,
            () ->
                mechanicsHarness(
                    new PublishWorkflowRequest("tenant-1", "notes", "workflow-1", workflowId)));

    assertTrue(thrown.getMessage().contains("failure marking commit outcome is unknown"));
    assertEquals(PublishAttemptStatus.PENDING, attempt.getStatus());
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
