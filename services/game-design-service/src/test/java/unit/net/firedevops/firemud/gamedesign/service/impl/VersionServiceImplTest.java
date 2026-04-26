package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedPluginVersionDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedPluginVersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.ParsedPluginBundle;
import net.firedevops.firemud.gamedesign.service.PluginBundleIntakeService;
import net.firedevops.firemud.gamedesign.service.PluginBundleStorageService;
import net.firedevops.firemud.gamedesign.service.PluginDistributionManifest;
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
import org.springframework.data.domain.Pageable;

class VersionServiceImplTest {
  @Mock private VersionRepository versionRepository;
  @Mock private GameRepository gameRepository;
  @Mock private PublishedPluginVersionRepository publishedPluginVersionRepository;
  @Mock private AutomationScriptingClient scriptingClient;
  @Mock private AssetExportService assetExportService;
  @Mock private PublishAttemptService publishAttemptService;
  @Mock private PublishGateService publishGateService;
  @Mock private ControlPlaneDigestService controlPlaneDigestService;
  @Mock private VersionAssetArtifactService versionAssetArtifactService;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;
  @Mock private RecordedParticipantDigestService recordedParticipantDigestService;
  @Mock private PluginBundleIntakeService pluginBundleIntakeService;
  @Mock private PluginBundleStorageService pluginBundleStorageService;

  private VersionServiceImpl service;

  @BeforeEach
  void setup() throws Exception {
    MockitoAnnotations.openMocks(this);
    VersionMapper mapper = Mappers.getMapper(VersionMapper.class);
    service =
        new VersionServiceImpl(
            versionRepository,
            gameRepository,
            publishedPluginVersionRepository,
            mapper,
            scriptingClient,
            assetExportService,
            publishAttemptService,
            publishGateService,
            controlPlaneDigestService,
            versionAssetArtifactService,
            publishedReleaseBundleService,
            recordedParticipantDigestService,
            pluginBundleIntakeService,
            pluginBundleStorageService);
  }

  @Test
  void publishVersionUsesTenantScopedVersionSequence() throws Exception {
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
    savedDraft.setUpdatedAt(java.time.LocalDateTime.now());
    Version savedPublished = new Version();
    savedPublished.setId(10L);
    savedPublished.setTenantId("tenant-1");
    savedPublished.setVersionNumber(8);
    savedPublished.setNotes("notes");
    savedPublished.setVersionState(VersionLifecycleState.PUBLISHED);
    savedPublished.setVersionStateEpoch(2L);
    savedPublished.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft, savedPublished);
    ExportedAssetManifest exportedManifest =
        new ExportedAssetManifest("abc123", List.of("logo.png", "manifest.json"));
    when(assetExportService.exportAssets("tenant-1", 8)).thenReturn(exportedManifest);
    List<PublishParticipantDigestDto> participantDigests =
        List.of(
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest-1", 1, null, null));
    when(publishGateService.collectFullVersionParticipantDigests(any(VersionDto.class)))
        .thenReturn(participantDigests);
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
                java.time.LocalDateTime.now(),
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
                "workflow-1",
                "abc123",
                List.of("logo.png", "manifest.json"),
                participantDigests,
                "genrev-tenant-1-10",
                false,
                null,
                java.time.LocalDateTime.now()));
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
                java.time.LocalDateTime.now(),
                List.of("logo.png", "manifest.json")));

    VersionDto dto = service.publishVersion("tenant-1", "notes");

    assertEquals(8, dto.versionNumber());
    assertEquals(VersionLifecycleState.PUBLISHED, dto.versionState());
    assertEquals(2L, dto.versionStateEpoch());
    verify(gameRepository).findByTenantIdForUpdate("tenant-1");
    verify(versionRepository).findTopByTenantIdOrderByVersionNumberDesc("tenant-1");
    verify(versionRepository, times(2)).save(any(Version.class));
    verify(assetExportService).exportAssets("tenant-1", 8);
    verify(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class));
    verify(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(any(String.class), any(), any(List.class));
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(any(String.class), any(), any(String.class), any(List.class));
  }

  @Test
  void publishScriptPatchVersionNotifiesAfterPersistingVersion() throws Exception {
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
    savedDraft.setId(11L);
    savedDraft.setTenantId("tenant-1");
    savedDraft.setVersionNumber(8);
    savedDraft.setScriptPatchVersion("patch-2");
    savedDraft.setNotes("notes");
    savedDraft.setVersionState(VersionLifecycleState.DRAFT);
    savedDraft.setVersionStateEpoch(1L);
    savedDraft.setUpdatedAt(java.time.LocalDateTime.now());
    Version savedPublished = new Version();
    savedPublished.setId(11L);
    savedPublished.setTenantId("tenant-1");
    savedPublished.setVersionNumber(8);
    savedPublished.setScriptPatchVersion("patch-2");
    savedPublished.setNotes("notes");
    savedPublished.setVersionState(VersionLifecycleState.PUBLISHED);
    savedPublished.setVersionStateEpoch(2L);
    savedPublished.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft, savedPublished);
    when(publishGateService.collectScriptPatchParticipantDigests(any(VersionDto.class)))
        .thenReturn(
            List.of(
                new PublishParticipantDigestDto(
                    "AUTOMATION_SCRIPTING",
                    "patch-2",
                    "script-patch:patch-2",
                    "digest-1",
                    1,
                    null,
                    null),
                new PublishParticipantDigestDto(
                    "GAME_DESIGN_CONTROL_PLANE",
                    "patch-2",
                    "script-patch:patch-2",
                    "digest-2",
                    1,
                    null,
                    null)));

    VersionDto dto = service.publishScriptPatchVersion("tenant-1", 3L, "patch-2", "notes");

    assertEquals(8, dto.versionNumber());
    assertEquals(VersionLifecycleState.PUBLISHED, dto.versionState());
    verify(versionRepository, times(2)).save(any(Version.class));
    verify(scriptingClient).notifyScriptVersionUpdate("tenant-1", "patch-2", java.util.List.of());
    verify(recordedParticipantDigestService)
        .recordVerifiedDigests(any(String.class), any(), any(String.class), any(List.class));
  }

  @Test
  void publishVersionPropagatesTypedPublishGateFailures() {
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
    savedDraft.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);
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
        org.junit.jupiter.api.Assertions.assertThrows(
            PublishGateFailureException.class, () -> service.publishVersion("tenant-1", "notes"));

    assertEquals(PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, thrown.failureCode());
    verify(publishAttemptService)
        .markFailed(
            any(String.class),
            org.mockito.ArgumentMatchers.eq("RECORDED_CONTENT_DIGEST_MISMATCH"),
            org.mockito.ArgumentMatchers.eq("recorded digest mismatch"));
  }

  @Test
  void publishVersionDeletesExportedAssetsWhenAttestationWriteFails() {
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
    savedDraft.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.save(any(Version.class))).thenReturn(savedDraft);
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenReturn(new ExportedAssetManifest("abc123", List.of("manifest.json")));
    when(publishGateService.collectFullVersionParticipantDigests(any(VersionDto.class)))
        .thenReturn(
            List.of(
                new PublishParticipantDigestDto(
                    "GAME_DESIGN_CONTROL_PLANE", "10", "version:10", "digest-1", 1, null, null)));
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
                java.time.LocalDateTime.now(),
                List.of("manifest.json")));
    org.mockito.Mockito.doThrow(new IllegalStateException("bundle failed"))
        .when(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class),
            any(String.class),
            any(ExportedAssetManifest.class),
            any(String.class),
            any(List.class));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> service.publishVersion("tenant-1", "notes"));

    verify(assetExportService).deleteExportedAssets("tenant-1", 1, List.of("manifest.json"));
  }

  @Test
  void publishPluginVersionRequiresPublishedBaseVersionAbilityDigestMatch() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    PublishedPluginVersion uploaded = uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1");
    uploaded.setAbilitySchemaDigest("digest-requested");
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploaded));
    when(pluginBundleStorageService.loadPluginBundle("tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(new byte[] {1, 2, 3});
    when(pluginBundleIntakeService.parseAndVerify(any()))
        .thenReturn(parsedPluginBundle("plugin-1", "plugin-v1", 7L, "digest-requested"));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(publishedReleaseBundle("tenant-1", 7L, "digest-live"));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-requested",
                    "bundle-1",
                    1,
                    null,
                    null,
                    "signer-1",
                    false,
                    "ALLOWED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("VALIDATION_FAILED_DESIGN"));
  }

  @Test
  void publishPluginVersionRejectsRevokedSignerMetadata() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1")));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-live",
                    "bundle-1",
                    1,
                    null,
                    null,
                    "signer-1",
                    true,
                    "ALLOWED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("uploaded plugin bundle metadata"));
  }

  @Test
  void publishPluginVersionRejectsBlockedComponentPolicy() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1")));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-live",
                    "bundle-1",
                    1,
                    null,
                    null,
                    "signer-1",
                    false,
                    "BLOCKED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("blocked component policy"));
  }

  @Test
  void publishPluginVersionRejectsPublishRequestThatDoesNotMatchUploadedBundleMetadata() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(publishedPluginVersionRepository.findByTenantIdAndPluginIdAndPluginVersionId(
            "tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(Optional.of(uploadedPluginVersion("tenant-1", "plugin-1", "plugin-v1")));
    when(pluginBundleStorageService.loadPluginBundle("tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(new byte[] {1, 2, 3});
    when(pluginBundleIntakeService.parseAndVerify(any()))
        .thenReturn(parsedPluginBundle("plugin-1", "plugin-v1", 7L, "digest-live"));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(publishedReleaseBundle("tenant-1", 7L, "digest-live"));
    when(pluginBundleStorageService.exportPluginAssets(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            any(ParsedPluginBundle.class),
            org.mockito.ArgumentMatchers.eq("signer-1"),
            org.mockito.ArgumentMatchers.eq("bundle-1")))
        .thenReturn(new PluginDistributionManifest("", ""));

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.publishPluginVersion(
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    "digest-live",
                    "bundle-1",
                    1,
                    "",
                    "",
                    "signer-2",
                    false,
                    "ALLOWED",
                    "notes"));

    assertTrue(thrown.getMessage().contains("uploaded plugin bundle metadata"));
  }

  @Test
  void getDesignControlPlaneDigestUsesStoredVersionScope() {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(2L);
    version.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.findByTenantIdAndId("tenant-1", 7L)).thenReturn(Optional.of(version));
    when(controlPlaneDigestService.getDigestForVersion(any(VersionDto.class)))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "7", "version:7", "digest-1", 1));

    DesignControlPlaneDigestDto dto = service.getDesignControlPlaneDigest("tenant-1", 7L);

    assertEquals("digest-1", dto.contentDigest());
  }

  @Test
  void listPublishedPluginVersionsUsesRepositoryFiltersAndLimitClamp() {
    PublishedPluginVersion newer = new PublishedPluginVersion();
    newer.setId(22L);
    newer.setTenantId("tenant-1");
    newer.setPluginId("plugin-1");
    newer.setPluginVersionId("plugin-v2");
    newer.setBaseVersionId(7L);
    newer.setPublicationState(VersionLifecycleState.PUBLISHED);
    newer.setAbilitySchemaDigest("ability-2");
    newer.setBundleDigest("bundle-2");
    newer.setManifestSchemaVersion(1);
    newer.setSignerKeyId("signer-2");
    newer.setSignerRevoked(false);
    newer.setComponentPolicyDecision("REPORT_ONLY");
    newer.setLastChangedAt(LocalDateTime.parse("2026-04-22T10:00:00"));

    when(publishedPluginVersionRepository.listPublishedPluginVersions(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("plugin-1"),
            org.mockito.ArgumentMatchers.eq(VersionLifecycleState.PUBLISHED),
            org.mockito.ArgumentMatchers.eq(LocalDateTime.parse("2026-04-20T10:00:00")),
            isNull(),
            any(Pageable.class)))
        .thenReturn(List.of(newer));

    List<PublishedPluginVersionDto> results =
        service.listPublishedPluginVersions(
            "tenant-1",
            "plugin-1",
            VersionLifecycleState.PUBLISHED,
            LocalDateTime.parse("2026-04-20T10:00:00"),
            null,
            500);

    assertEquals(1, results.size());
    assertEquals("plugin-v2", results.get(0).pluginVersionId());
    org.mockito.ArgumentCaptor<Pageable> pageableCaptor =
        org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(publishedPluginVersionRepository)
        .listPublishedPluginVersions(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("plugin-1"),
            org.mockito.ArgumentMatchers.eq(VersionLifecycleState.PUBLISHED),
            org.mockito.ArgumentMatchers.eq(LocalDateTime.parse("2026-04-20T10:00:00")),
            isNull(),
            pageableCaptor.capture());
    assertEquals(200, pageableCaptor.getValue().getPageSize());
  }

  @Test
  void listPublishedPluginVersionsRejectsInvertedTimeWindow() {
    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                service.listPublishedPluginVersions(
                    "tenant-1",
                    "",
                    null,
                    LocalDateTime.parse("2026-04-22T10:00:00"),
                    LocalDateTime.parse("2026-04-20T10:00:00"),
                    10));

    assertTrue(thrown.getMessage().contains("changedAfter"));
  }

  @Test
  void listVersionsUsesTenantScopedOrdering() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantId("tenant-1")).thenReturn(game);

    Version one = new Version();
    one.setId(1L);
    one.setTenantId("tenant-1");
    one.setVersionNumber(1);
    one.setVersionState(VersionLifecycleState.PUBLISHED);
    one.setVersionStateEpoch(2L);
    one.setUpdatedAt(java.time.LocalDateTime.now());
    Version two = new Version();
    two.setId(2L);
    two.setTenantId("tenant-1");
    two.setVersionNumber(2);
    two.setVersionState(VersionLifecycleState.PUBLISHED);
    two.setVersionStateEpoch(2L);
    two.setUpdatedAt(java.time.LocalDateTime.now());
    when(versionRepository.findAllByTenantIdOrderByVersionNumberAsc("tenant-1"))
        .thenReturn(List.of(one, two));

    List<VersionDto> versions = service.listVersions("tenant-1");

    assertEquals(2, versions.size());
    assertTrue(versions.get(0).versionNumber() < versions.get(1).versionNumber());
    verify(versionRepository).findAllByTenantIdOrderByVersionNumberAsc("tenant-1");
  }

  private PublishedReleaseBundleDto publishedReleaseBundle(
      String tenantId, long versionId, String automationDigest) {
    return new PublishedReleaseBundleDto(
        1L,
        tenantId,
        versionId,
        7,
        "v1",
        "workflow-1",
        "manifest-1",
        List.of("manifest.json"),
        List.of(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                String.valueOf(versionId),
                "version:" + versionId,
                automationDigest,
                1,
                null,
                null)),
        "genrev-1",
        false,
        null,
        LocalDateTime.parse("2026-04-26T10:00:00"));
  }

  private PublishedPluginVersion uploadedPluginVersion(
      String tenantId, String pluginId, String pluginVersionId) {
    PublishedPluginVersion entity = new PublishedPluginVersion();
    entity.setId(15L);
    entity.setTenantId(tenantId);
    entity.setPluginId(pluginId);
    entity.setPluginVersionId(pluginVersionId);
    entity.setBaseVersionId(7L);
    entity.setPublicationState(VersionLifecycleState.SIGNATURE_VERIFIED);
    entity.setAbilitySchemaDigest("digest-live");
    entity.setBundleDigest("bundle-1");
    entity.setManifestSchemaVersion(1);
    entity.setDistributionManifestHash("");
    entity.setDistributionManifestPath("");
    entity.setSignerKeyId("signer-1");
    entity.setSignerRevoked(false);
    entity.setComponentPolicyDecision("UNSPECIFIED");
    entity.setNotes("notes");
    entity.setStatusReason("");
    entity.setLastChangedAt(LocalDateTime.parse("2026-04-26T10:00:00"));
    return entity;
  }

  private ParsedPluginBundle parsedPluginBundle(
      String pluginId, String pluginVersionId, long baseVersionId, String abilitySchemaDigest) {
    return new ParsedPluginBundle(
        pluginId,
        pluginVersionId,
        baseVersionId,
        abilitySchemaDigest,
        "bundle-1",
        1,
        "signer-1",
        List.of(),
        java.util.Map.of("plugin-manifest.json", new byte[] {1}));
  }
}
