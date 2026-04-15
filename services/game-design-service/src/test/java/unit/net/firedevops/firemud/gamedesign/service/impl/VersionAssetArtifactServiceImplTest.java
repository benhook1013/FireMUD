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
import net.firedevops.firemud.gamedesign.entity.VersionAssetPurgeWorkflow;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionAssetPurgeWorkflowStatus;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPurgeWorkflowRepository;
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
  @Mock private VersionAssetPurgeWorkflowRepository purgeWorkflowRepository;
  @Mock private VersionRepository versionRepository;
  @Mock private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Mock private AssetExportService assetExportService;
  @Mock private PublishedReleaseBundleService publishedReleaseBundleService;

  private VersionAssetArtifactServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new VersionAssetArtifactServiceImpl(
            repository,
            purgeWorkflowRepository,
            versionRepository,
            publishedReleaseBundleRepository,
            assetExportService,
            publishedReleaseBundleService,
            new tools.jackson.databind.ObjectMapper());
  }

  @Test
  void markExportedUnattestedCreatesArtifactStateRow() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());
    when(repository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var state =
        service.markExportedUnattested(
            "tenant-1", 7L, "workflow-1", new ExportedAssetManifest("hash-1", List.of("a", "b")));

    assertEquals("EXPORTED_UNATTESTED", state.artifactState());
    assertEquals(1L, state.stateEpoch());
    assertEquals("hash-1", state.manifestHash());
    assertEquals(List.of("a", "b"), state.exportedManifestAssetKeys());
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
    version.setVersionState(VersionLifecycleState.RETIRED);
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
                "genrev-1",
                false,
                null,
                LocalDateTime.now()));
    when(assetExportService.exportAssets("tenant-1", 8))
        .thenReturn(new ExportedAssetManifest("different", List.of("manifest.json")));

    assertThrows(
        IllegalStateException.class,
        () -> service.repairPublishedVersionAssets("tenant-1", 7L, 3L, "repair-1"));
  }

  @Test
  void beginAndFinalizePurgeUsesExactExportedKeyProof() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    artifact.setManifestHash("hash-1");
    artifact.setExportedManifestAssetKeysJson("[\"logo.png\",\"manifest.json\"]");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(repository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(purgeWorkflowRepository.save(any(VersionAssetPurgeWorkflow.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    version.setVersionState(VersionLifecycleState.RETIRED);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));

    var started = service.beginPurgeVersionAssets("tenant-1", 7L, 5L);

    VersionAssetPurgeWorkflow workflow = new VersionAssetPurgeWorkflow();
    workflow.setTenantId("tenant-1");
    workflow.setVersionId(7L);
    workflow.setPurgeWorkflowId(started.purgeWorkflowId());
    workflow.setWorkflowStatus(VersionAssetPurgeWorkflowStatus.IN_PROGRESS);
    workflow.setStartedFromStateEpoch(5L);
    workflow.setRequestedAt(LocalDateTime.now());
    workflow.setUpdatedAt(LocalDateTime.now());
    when(purgeWorkflowRepository.findByTenantIdAndVersionIdAndPurgeWorkflowId(
            "tenant-1", 7L, started.purgeWorkflowId()))
        .thenReturn(Optional.of(workflow));

    var finished =
        service.finalizePurgeVersionAssets("tenant-1", 7L, started.purgeWorkflowId(), 6L);

    assertEquals(VersionAssetPurgeWorkflowStatus.SUCCEEDED.name(), finished.workflowStatus());
    org.mockito.Mockito.verify(assetExportService)
        .deleteExportedAssets("tenant-1", 8, List.of("logo.png", "manifest.json"));
  }

  @Test
  void canDeleteFailsClosedWhenVersionIsNotRetired() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));

    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(false, eligibility.deletable());
    assertEquals("VERSION_STATE_NOT_RETIRED", eligibility.failureCode());
  }
}
