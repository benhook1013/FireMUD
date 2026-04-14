package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class VersionAssetArtifactServiceImplTest {
  @Mock private VersionAssetArtifactRepository repository;
  @Mock private VersionRepository versionRepository;
  @Mock private AssetExportService assetExportService;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;

  private VersionAssetArtifactServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new VersionAssetArtifactServiceImpl(
            repository, versionRepository, assetExportService, publishedReleaseBundleService);
  }

  @Test
  void markExportedUnattestedCreatesArtifactStateRow() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    when(repository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var state = service.markExportedUnattested("tenant-1", 7L, "workflow-1", "hash-1");

    assertEquals("EXPORTED_UNATTESTED", state.artifactState());
    assertEquals(1L, state.stateEpoch());
    assertEquals("hash-1", state.manifestHash());
  }

  @Test
  void repairFailsClosedWhenManifestHashDrifts() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(3L);
    artifact.setManifestHash("attested");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(repository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(
            new PublishedReleaseBundleDto(
                1L,
                "tenant-1",
                7L,
                8,
                "v1",
                "workflow-1",
                "attested",
                List.of("manifest.json"),
                List.of(),
                false,
                null,
                LocalDateTime.now()));
    when(assetExportService.exportAssets("tenant-1", 8))
        .thenReturn(new ExportedAssetManifest("different", List.of("manifest.json")));

    assertThrows(
        IllegalStateException.class,
        () -> service.repairPublishedVersionAssets("tenant-1", 7L, 3L, "repair-1"));
  }
}
