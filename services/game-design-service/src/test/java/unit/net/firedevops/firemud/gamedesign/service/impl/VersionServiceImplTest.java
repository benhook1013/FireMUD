package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class VersionServiceImplTest {
  @Mock private VersionRepository versionRepository;
  @Mock private GameRepository gameRepository;
  @Mock private AutomationScriptingClient scriptingClient;
  @Mock private AssetExportService assetExportService;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;

  private VersionServiceImpl service;

  @BeforeEach
  void setup() throws Exception {
    MockitoAnnotations.openMocks(this);
    VersionMapper mapper = Mappers.getMapper(VersionMapper.class);
    service =
        new VersionServiceImpl(
            versionRepository,
            gameRepository,
            mapper,
            scriptingClient,
            assetExportService,
            publishedReleaseBundleService);
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

    Version saved = new Version();
    saved.setId(10L);
    saved.setTenantId("tenant-1");
    saved.setVersionNumber(8);
    saved.setNotes("notes");
    when(versionRepository.save(any(Version.class))).thenReturn(saved);
    ExportedAssetManifest exportedManifest =
        new ExportedAssetManifest("abc123", List.of("logo.png", "manifest.json"));
    when(assetExportService.exportAssets("tenant-1", 8)).thenReturn(exportedManifest);
    when(publishedReleaseBundleService.createFullVersionBundle(
            any(VersionDto.class), any(String.class), any(ExportedAssetManifest.class)))
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
                false,
                null,
                java.time.LocalDateTime.now()));

    VersionDto dto = service.publishVersion("tenant-1", "notes");

    assertEquals(8, dto.versionNumber());
    verify(gameRepository).findByTenantIdForUpdate("tenant-1");
    verify(versionRepository).findTopByTenantIdOrderByVersionNumberDesc("tenant-1");
    verify(versionRepository).save(any(Version.class));
    verify(assetExportService).exportAssets("tenant-1", 8);
    verify(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class), any(String.class), any(ExportedAssetManifest.class));
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

    Version saved = new Version();
    saved.setId(11L);
    saved.setTenantId("tenant-1");
    saved.setVersionNumber(8);
    saved.setScriptPatchVersion("patch-2");
    saved.setNotes("notes");
    when(versionRepository.save(any(Version.class))).thenReturn(saved);

    VersionDto dto = service.publishScriptPatchVersion("tenant-1", 3L, "patch-2", "notes");

    assertEquals(8, dto.versionNumber());
    verify(versionRepository).save(any(Version.class));
    verify(scriptingClient).notifyScriptVersionUpdate("tenant-1", "patch-2", java.util.List.of());
  }

  @Test
  void publishVersionDeletesExportedAssetsWhenAttestationWriteFails() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("tenant-1");
    when(gameRepository.findByTenantIdForUpdate("tenant-1")).thenReturn(game);
    when(versionRepository.findTopByTenantIdOrderByVersionNumberDesc("tenant-1"))
        .thenReturn(Optional.empty());

    Version saved = new Version();
    saved.setId(10L);
    saved.setTenantId("tenant-1");
    saved.setVersionNumber(1);
    when(versionRepository.save(any(Version.class))).thenReturn(saved);
    when(assetExportService.exportAssets("tenant-1", 1))
        .thenReturn(new ExportedAssetManifest("abc123", List.of("manifest.json")));
    org.mockito.Mockito.doThrow(new IllegalStateException("bundle failed"))
        .when(publishedReleaseBundleService)
        .createFullVersionBundle(
            any(VersionDto.class), any(String.class), any(ExportedAssetManifest.class));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> service.publishVersion("tenant-1", "notes"));

    verify(assetExportService).deleteExportedAssets("tenant-1", 1);
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
    Version two = new Version();
    two.setId(2L);
    two.setTenantId("tenant-1");
    two.setVersionNumber(2);
    when(versionRepository.findAllByTenantIdOrderByVersionNumberAsc("tenant-1"))
        .thenReturn(List.of(one, two));

    List<VersionDto> versions = service.listVersions("tenant-1");

    assertEquals(2, versions.size());
    assertTrue(versions.get(0).versionNumber() < versions.get(1).versionNumber());
    verify(versionRepository).findAllByTenantIdOrderByVersionNumberAsc("tenant-1");
  }
}
