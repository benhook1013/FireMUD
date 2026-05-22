package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
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
import org.mapstruct.factory.Mappers;
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
    when(publishAttemptRepository.findByPublishWorkflowId(
            "publish:tenant-1:publish-request:workflow-1"))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft), Optional.of(savedPublished));

    ExportedAssetManifest exportedManifest =
        new ExportedAssetManifest("abc123", List.of("logo.png", "manifest.json"));
    when(assetExportService.exportAssets("tenant-1", 8)).thenReturn(exportedManifest);
    List<PublishParticipantDigestDto> participantDigests =
        List.of(
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest-1", 1, null, null));
    when(publishGateService.collectFullVersionParticipantDigests(any(VersionDto.class)))
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
            "tenant-1", "notes", "publish:tenant-1:publish-request:workflow-1");

    assertEquals(8, dto.versionNumber());
    assertEquals(VersionLifecycleState.PUBLISHED, dto.versionState());
    assertEquals(2L, dto.versionStateEpoch());
    verify(publishAttemptService)
        .createAttempt(
            any(VersionDto.class),
            org.mockito.ArgumentMatchers.eq(PublishType.FULL_VERSION),
            any(String.class));
    verify(assetExportService).exportAssets("tenant-1", 8);
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(any(String.class), any(), any(String.class), any(List.class));
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
    when(publishAttemptRepository.findByPublishWorkflowId(
            "publish:tenant-1:publish-request:workflow-1"))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));
    when(publishGateService.collectFullVersionParticipantDigests(any(VersionDto.class)))
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
                    "tenant-1", "notes", "publish:tenant-1:publish-request:workflow-1"));

    assertEquals(PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, thrown.failureCode());
    verify(publishAttemptService)
        .markFailed(
            any(String.class),
            org.mockito.ArgumentMatchers.eq("RECORDED_CONTENT_DIGEST_MISMATCH"),
            org.mockito.ArgumentMatchers.eq("recorded digest mismatch"));
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
    when(publishAttemptRepository.findByPublishWorkflowId(
            "publish:tenant-1:publish-request:workflow-1"))
        .thenReturn(Optional.empty(), Optional.of(attempt));
    when(versionRepository.findByTenantIdAndId("tenant-1", 10L))
        .thenReturn(Optional.of(savedDraft));
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenReturn(new ExportedAssetManifest("abc123", List.of("manifest.json")));
    when(publishGateService.collectFullVersionParticipantDigests(any(VersionDto.class)))
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
                "tenant-1", "notes", "publish:tenant-1:publish-request:workflow-1"));

    verify(assetExportService).deleteExportedAssets("tenant-1", 1, List.of("manifest.json"));
  }
}
