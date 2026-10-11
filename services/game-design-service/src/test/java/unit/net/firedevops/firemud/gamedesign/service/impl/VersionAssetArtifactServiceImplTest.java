package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.entity.VersionAssetPurgeWorkflow;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionAssetPurgeWorkflowStatus;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPurgeWorkflowRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class VersionAssetArtifactServiceImplTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String LOGO_DIGEST = "sha256:" + "b".repeat(64);

  @Mock private VersionAssetArtifactRepository repository;
  @Mock private VersionAssetPurgeWorkflowRepository purgeWorkflowRepository;
  @Mock private VersionRepository versionRepository;
  @Mock private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Mock private LaunchDescriptorRepository launchDescriptorRepository;
  @Mock private VersionTemplateRemapSetRepository remapSetRepository;
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
            launchDescriptorRepository,
            remapSetRepository,
            assetExportService,
            publishedReleaseBundleService,
            new tools.jackson.databind.ObjectMapper());
  }

  @Test
  void markExportedUnattestedAdvancesPersistedCandidateAndExactRetryDoesNotChurn() {
    VersionAssetArtifact staged = new VersionAssetArtifact();
    staged.setTenantId("tenant-1");
    staged.setVersionId(7L);
    staged.setExportedVersionNumber(8);
    staged.setArtifactState(VersionAssetArtifactState.STAGED);
    staged.setStateEpoch(2L);
    staged.setLastWorkflowId("workflow-1");
    setArtifactCandidate(staged, MANIFEST_HASH, List.of());
    when(repository.findByTenantIdAndVersionIdForUpdate("tenant-1", 7L))
        .thenReturn(Optional.of(staged));
    when(repository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var state =
        service.markExportedUnattested(
            "tenant-1",
            7L,
            8,
            "workflow-1",
            new ExportedAssetManifest(MANIFEST_HASH, 1, List.of(), List.of()));
    var retry =
        service.markExportedUnattested(
            "tenant-1",
            7L,
            8,
            "workflow-1",
            new ExportedAssetManifest(MANIFEST_HASH, 1, List.of(), List.of()));

    assertEquals("EXPORTED_UNATTESTED", state.artifactState());
    assertEquals(state, retry);
    assertEquals(8, state.exportedVersionNumber());
    assertEquals(3L, state.stateEpoch());
    assertEquals(MANIFEST_HASH, state.manifestHash());
    assertEquals(List.of(), state.exportedManifestAssetKeys());
    verify(repository, times(1)).save(any(VersionAssetArtifact.class));
  }

  @Test
  void optionalReadDistinguishesMissingArtifactWhileRequiredReadStillFails() {
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.empty());

    assertEquals(Optional.empty(), service.findState("tenant-1", 7L));
    assertThrows(IllegalArgumentException.class, () -> service.getState("tenant-1", 7L));
  }

  @Test
  void getExportCandidateReturnsNullForExactInitialStagedIntent() {
    VersionAssetArtifact intent = initialStagedIntent();
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(intent));

    assertNull(service.getExportCandidate("tenant-1", 7L));
  }

  @Test
  void getExportCandidateReturnsRecordedCompleteCandidate() {
    VersionAssetArtifact artifact = initialStagedIntent();
    setArtifactCandidate(artifact, MANIFEST_HASH, List.of(logoProof(LOGO_DIGEST)));
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));

    assertEquals(
        new ExportedAssetManifest(
            MANIFEST_HASH, 1, List.of("logo.png"), List.of(logoProof(LOGO_DIGEST))),
        service.getExportCandidate("tenant-1", 7L));
  }

  @Test
  void getExportCandidateRejectsEachPartialCandidateField() {
    for (int partialField = 0; partialField < 4; partialField++) {
      VersionAssetArtifact partial = initialStagedIntent();
      switch (partialField) {
        case 0 -> partial.setManifestSchemaVersion(1);
        case 1 -> partial.setArtifactDigestsJson("[]");
        case 2 -> partial.setPublishedObjectProofsJson("[]");
        case 3 -> partial.setCandidateSnapshotVersionId(7L);
        default -> throw new IllegalStateException("unexpected partial field");
      }
      when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(partial));

      assertThrows(IllegalStateException.class, () -> service.getExportCandidate("tenant-1", 7L));
    }
  }

  @Test
  void getExportCandidateRejectsNonemptyOrMalformedInitialKeys() {
    for (String keysJson : List.of("[\"logo.png\"]", "not-json", " ")) {
      VersionAssetArtifact intent = initialStagedIntent();
      intent.setExportedManifestAssetKeysJson(keysJson);
      when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(intent));

      assertThrows(IllegalStateException.class, () -> service.getExportCandidate("tenant-1", 7L));
    }
  }

  @Test
  void getExportCandidateDoesNotTreatOtherStatesAsInitialIntent() {
    VersionAssetArtifact artifact = initialStagedIntent();
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));

    for (VersionAssetArtifactState state : VersionAssetArtifactState.values()) {
      if (state == VersionAssetArtifactState.STAGED) {
        continue;
      }
      artifact.setArtifactState(state);
      assertThrows(IllegalStateException.class, () -> service.getExportCandidate("tenant-1", 7L));
    }
  }

  @Test
  void getExportCandidateRejectsWrongOwnerScopeAndMissingArtifact() {
    VersionAssetArtifact wrongOwner = initialStagedIntent();
    wrongOwner.setTenantId("tenant-2");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(wrongOwner));
    assertThrows(IllegalStateException.class, () -> service.getExportCandidate("tenant-1", 7L));

    VersionAssetArtifact wrongVersion = initialStagedIntent();
    when(repository.findByTenantIdAndVersionId("tenant-1", 8L))
        .thenReturn(Optional.of(wrongVersion));
    assertThrows(IllegalStateException.class, () -> service.getExportCandidate("tenant-1", 8L));

    when(repository.findByTenantIdAndVersionId("tenant-1", 9L)).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.getExportCandidate("tenant-1", 9L));
  }

  @Test
  void repairFailsClosedWhenManifestHashDrifts() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(4L);
    artifact.setManifestHash(MANIFEST_HASH);
    setArtifactCandidate(artifact, MANIFEST_HASH, List.of());
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(repository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionNumber(8);
    version.setVersionState(VersionLifecycleState.RETIRED);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));
    when(launchDescriptorRepository.existsByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndSourceVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndTargetVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);
    when(publishedReleaseBundleService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(
            new PublishedReleaseBundleDto(
                1L,
                "tenant-1",
                7L,
                8,
                "v1",
                "workflow-1",
                MANIFEST_HASH,
                List.of(),
                List.of(),
                "genrev-1",
                false,
                null,
                LocalDateTime.now(),
                UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
                UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
                "opaque-owner-issued-release-reference",
                1,
                List.of()));
    when(assetExportService.repairPublishedAssets(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq(8),
            any(PublishedReleaseBundleDto.class)))
        .thenThrow(
            new IllegalStateException(
                "REPAIR_ATTESTATION_MISMATCH: repair could not reproduce the attested manifest hash"));

    assertThrows(
        IllegalStateException.class,
        () -> service.repairPublishedVersionAssets("tenant-1", 7L, 4L, "repair-1"));
  }

  @Test
  void repairFailsClosedWhenAttestedArtifactDigestDrifts() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(4L);
    artifact.setManifestHash(MANIFEST_HASH);
    setArtifactCandidate(artifact, MANIFEST_HASH, List.of(logoProof(LOGO_DIGEST)));
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
                MANIFEST_HASH,
                List.of("logo.png"),
                List.of(),
                "genrev-1",
                false,
                null,
                LocalDateTime.now(),
                UUID.fromString("67d7b75b-42d1-4ac6-9572-684c5e633cda"),
                UUID.fromString("c472ebd1-56d8-49df-b8fa-85963dd940f8"),
                "opaque-owner-issued-release-reference",
                1,
                List.of(logoProof(LOGO_DIGEST))));
    when(assetExportService.repairPublishedAssets(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq(8),
            any(PublishedReleaseBundleDto.class)))
        .thenThrow(
            new IllegalStateException(
                "REPAIR_ATTESTATION_MISMATCH: repair could not reproduce the attested artifact proof"));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> service.repairPublishedVersionAssets("tenant-1", 7L, 4L, "repair-1"));

    assertEquals("REPAIR_ATTESTATION_MISMATCH", thrown.getMessage());
  }

  @Test
  void beginAndFinalizePurgeUsesExactExportedKeyProof() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setExportedVersionNumber(8);
    artifact.setStateEpoch(5L);
    artifact.setManifestHash(MANIFEST_HASH);
    artifact.setExportedManifestAssetKeysJson("[\"logo.png\"]");
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
        .deleteExportedAssets("tenant-1", 8, List.of("logo.png"));
  }

  @Test
  void finalizePurgeUsesFrozenExportVersionNumberWithRetiredVersion() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(8);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    artifact.setExportedManifestAssetKeysJson("[\"logo.png\"]");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(repository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(purgeWorkflowRepository.save(any(VersionAssetPurgeWorkflow.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Version retiredVersion = new Version();
    retiredVersion.setId(7L);
    retiredVersion.setTenantId("tenant-1");
    retiredVersion.setVersionNumber(8);
    retiredVersion.setVersionState(VersionLifecycleState.RETIRED);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(retiredVersion));
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("tenant-1", 7L))
        .thenReturn(Optional.empty());
    when(launchDescriptorRepository.existsByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndSourceVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndTargetVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);

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

    service.finalizePurgeVersionAssets("tenant-1", 7L, started.purgeWorkflowId(), 6L);

    org.mockito.Mockito.verify(assetExportService)
        .deleteExportedAssets("tenant-1", 8, List.of("logo.png"));
  }

  @Test
  void beginPurgeFailsClosedWhenVersionAuthorityIsAbsent() {
    VersionAssetArtifact artifact = tombstonedExportedArtifact();
    artifact.setExportedManifestAssetKeysJson("[\"logo.png\"]");
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(versionRepository.findById(7L)).thenReturn(Optional.empty());
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("tenant-1", 7L))
        .thenReturn(Optional.empty());
    when(launchDescriptorRepository.existsByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndSourceVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndTargetVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.beginPurgeVersionAssets("tenant-1", 7L, 5L));

    assertEquals("VERSION_AUTHORITY_NOT_FOUND", thrown.getMessage());
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
        .save(any(VersionAssetArtifact.class));
    org.mockito.Mockito.verify(purgeWorkflowRepository, org.mockito.Mockito.never())
        .save(any(VersionAssetPurgeWorkflow.class));
  }

  @Test
  void canDeleteFailsClosedWhenVersionIsNotRetired() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(8);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));

    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));
    when(launchDescriptorRepository.existsByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndSourceVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndTargetVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(false, eligibility.deletable());
    assertEquals("VERSION_STATE_NOT_RETIRED", eligibility.failureCode());
  }

  @Test
  void canDeleteFailsClosedWhenVersionAuthorityIsAbsent() {
    VersionAssetArtifact artifact = tombstonedExportedArtifact();
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(versionRepository.findById(7L)).thenReturn(Optional.empty());

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(false, eligibility.deletable());
    assertEquals("VERSION_AUTHORITY_NOT_FOUND", eligibility.failureCode());
  }

  @Test
  void canDeleteFailsClosedWhenVersionAuthorityBelongsToAnotherTenant() {
    VersionAssetArtifact artifact = tombstonedExportedArtifact();
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    Version wrongTenantVersion = new Version();
    wrongTenantVersion.setId(7L);
    wrongTenantVersion.setTenantId("tenant-2");
    wrongTenantVersion.setVersionState(VersionLifecycleState.RETIRED);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(wrongTenantVersion));

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(false, eligibility.deletable());
    assertEquals("VERSION_AUTHORITY_NOT_FOUND", eligibility.failureCode());
  }

  @Test
  void canDeleteAllowsExactRetiredVersionWhenNoOtherReferencesRemain() {
    VersionAssetArtifact artifact = tombstonedExportedArtifact();
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("tenant-1");
    version.setVersionState(VersionLifecycleState.RETIRED);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version));
    when(publishedReleaseBundleRepository.findByTenantIdAndVersionId("tenant-1", 7L))
        .thenReturn(Optional.empty());
    when(launchDescriptorRepository.existsByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndSourceVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndTargetVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(false);

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(true, eligibility.deletable());
    assertEquals(null, eligibility.failureCode());
  }

  @Test
  void canDeleteFailsClosedWhenExportVersionProofIsMissing() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(false, eligibility.deletable());
    assertEquals("VERSION_ASSET_EXPORT_PROOF_MISSING", eligibility.failureCode());
  }

  @Test
  void canDeleteFailsClosedWhenLaunchDescriptorStillReferencesVersion() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(8);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(launchDescriptorRepository.existsByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(true);

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(false, eligibility.deletable());
    assertEquals("VERSION_ASSET_LAUNCH_REFERENCE_EXISTS", eligibility.failureCode());
  }

  @Test
  void canDeleteFailsClosedWhenApprovedRemapSetStillReferencesVersion() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(8);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    when(repository.findByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(Optional.of(artifact));
    when(launchDescriptorRepository.existsByTenantIdAndVersionId("tenant-1", 7L)).thenReturn(false);
    when(remapSetRepository.existsByTenantIdAndSourceVersionIdAndStatus(
            "tenant-1", 7L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(true);

    var eligibility = service.canDeleteVersionAssets("tenant-1", 7L);

    assertEquals(false, eligibility.deletable());
    assertEquals("VERSION_ASSET_TEMPLATE_REMAP_REFERENCE_EXISTS", eligibility.failureCode());
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

  private static VersionAssetArtifact initialStagedIntent() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(8);
    artifact.setArtifactState(VersionAssetArtifactState.STAGED);
    artifact.setStateEpoch(1L);
    artifact.setLastWorkflowId("workflow-1");
    artifact.setExportedManifestAssetKeysJson("[]");
    return artifact;
  }

  private static VersionAssetArtifact tombstonedExportedArtifact() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("tenant-1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(8);
    artifact.setArtifactState(VersionAssetArtifactState.TOMBSTONED);
    artifact.setStateEpoch(5L);
    return artifact;
  }

  private static void setArtifactCandidate(
      VersionAssetArtifact artifact, String manifestHash, List<PublishedArtifactDigest> digests) {
    List<String> keys = digests.stream().map(PublishedArtifactDigest::usageKey).toList();
    List<StoredObjectProof> objectProofs = new ArrayList<>();
    for (PublishedArtifactDigest digest : digests) {
      objectProofs.add(new StoredObjectProof(digest.immutableObjectKey(), digest.contentDigest()));
    }
    objectProofs.add(
        new StoredObjectProof(
            "manifests/sha256/" + manifestHash.substring("sha256:".length()), manifestHash));
    objectProofs.sort(java.util.Comparator.comparing(StoredObjectProof::immutableObjectKey));

    artifact.setManifestHash(manifestHash);
    artifact.setManifestSchemaVersion(1);
    artifact.setExportedManifestAssetKeysJson(writeJson(keys));
    artifact.setArtifactDigestsJson(writeJson(digests));
    artifact.setPublishedObjectProofsJson(writeJson(objectProofs));
    artifact.setCandidateSnapshotVersionId(artifact.getVersionId());
  }

  private static String writeJson(Object value) {
    try {
      return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to serialize artifact candidate proof", exception);
    }
  }

  private record StoredObjectProof(String immutableObjectKey, String contentDigest) {}
}
