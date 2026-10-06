package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class VersionAssetExportCandidateServiceImplTest {
  private static final String TENANT_ID = "candidate-owner";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("8b0c1a0f-b8c9-4c4b-9021-381f57699001");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("8b0c1a0f-b8c9-4c4b-9021-381f57699002");

  @Mock private VersionRepository versionRepository;
  @Mock private net.firedevops.firemud.gamedesign.repository.GameRepository gameRepository;

  @Mock
  private net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository attemptRepository;

  @Mock private VersionAssetArtifactRepository artifactRepository;
  @Mock private VersionAssetPublicationService publicationService;
  @Mock private PlatformTransactionManager transactionManager;

  private Version version;
  private VersionAssetArtifact artifact;
  private ExportSnapshot snapshot;
  private ExportedAssetManifest candidate;
  private VersionAssetExportCandidateServiceImpl service;

  @BeforeEach
  void setUp() {
    lenient()
        .when(gameRepository.findByTenantIdForUpdate(TENANT_ID))
        .thenReturn(new net.firedevops.firemud.gamedesign.entity.Game());
    TransactionStatus transactionStatus = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    byte[] bytes = "unit candidate bytes".getBytes(StandardCharsets.UTF_8);
    String contentDigest = sha256(bytes);
    version = newVersion(700L, 1);
    artifact = newStagedArtifact(700L, 1L);
    snapshot =
        new ExportSnapshot(
            TENANT_ID,
            700L,
            1,
            1L,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            List.of(new AssetSelection("logo.png", 17L, bytes, "image/png", contentDigest)));
    candidate = candidate(contentDigest, "logo.png", "unit manifest bytes");

    when(versionRepository.findByTenantIdAndVersionNumber(TENANT_ID, 1))
        .thenReturn(Optional.of(version));
    when(versionRepository.findByTenantIdAndIdForUpdate(TENANT_ID, 700L))
        .thenReturn(Optional.of(version));
    when(artifactRepository.findByTenantIdAndVersionIdForUpdate(TENANT_ID, 700L))
        .thenReturn(Optional.of(artifact));
    lenient()
        .when(artifactRepository.findByTenantIdAndVersionId(TENANT_ID, 700L))
        .thenAnswer(ignored -> Optional.of(artifact));
    lenient()
        .when(artifactRepository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(publicationService.readFrozenSnapshot(TENANT_ID, 1)).thenReturn(snapshot);

    service =
        new VersionAssetExportCandidateServiceImpl(
            versionRepository,
            gameRepository,
            attemptRepository,
            artifactRepository,
            publicationService,
            transactionManager,
            new ObjectMapper());
  }

  @Test
  void commitsCompleteCandidateEvidenceAndIndependentReadback() {
    ExportedAssetManifest recorded = service.recordExportCandidate(TENANT_ID, 1, candidate);
    ExportedAssetManifest readback = service.readExportCandidate(TENANT_ID, 1);

    assertThat(recorded).isEqualTo(candidate);
    assertThat(readback).isEqualTo(candidate);
    assertThat(artifact.getStateEpoch()).isEqualTo(2L);
    assertThat(artifact.getManifestHash()).isEqualTo(candidate.manifestHash());
    assertThat(artifact.getManifestSchemaVersion()).isEqualTo(1);
    assertThat(artifact.getCandidateSnapshotVersionId()).isEqualTo(700L);
    assertThat(artifact.getExportedManifestAssetKeysJson()).isEqualTo("[\"logo.png\"]");
    assertThat(artifact.getPublishedObjectProofsJson())
        .contains("artifacts/sha256/", "manifests/sha256/", candidate.manifestHash());
    verify(transactionManager, times(2)).commit(any(TransactionStatus.class));
  }

  @Test
  void exactRetryReadsExistingProofWithoutChangingEpochOrSavingAgain() {
    service.recordExportCandidate(TENANT_ID, 1, candidate);
    long committedEpoch = artifact.getStateEpoch();

    assertThat(service.recordExportCandidate(TENANT_ID, 1, candidate)).isEqualTo(candidate);

    assertThat(artifact.getStateEpoch()).isEqualTo(committedEpoch);
    verify(artifactRepository, times(1)).save(any(VersionAssetArtifact.class));
  }

  @Test
  void conflictingManifestOrFabricatedSourceDigestIsDeniedWithoutMutation() {
    service.recordExportCandidate(TENANT_ID, 1, candidate);
    long committedEpoch = artifact.getStateEpoch();
    ExportedAssetManifest otherManifest =
        new ExportedAssetManifest(
            "sha256:" + "a".repeat(64),
            candidate.manifestSchemaVersion(),
            candidate.requiredManifestAssetKeys(),
            candidate.artifactDigests());

    assertThatThrownBy(() -> service.recordExportCandidate(TENANT_ID, 1, otherManifest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");

    String fabricatedDigest = "sha256:" + "b".repeat(64);
    ExportedAssetManifest fabricated =
        new ExportedAssetManifest(
            candidate.manifestHash(),
            1,
            List.of("logo.png"),
            List.of(
                new PublishedArtifactDigest(
                    "logo.png",
                    "BINARY",
                    "artifacts/sha256/" + fabricatedDigest.substring(7),
                    fabricatedDigest,
                    "image/png",
                    1)));
    assertThatThrownBy(() -> service.recordExportCandidate(TENANT_ID, 1, fabricated))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");
    assertThat(artifact.getStateEpoch()).isEqualTo(committedEpoch);
    verify(artifactRepository, times(1)).save(any(VersionAssetArtifact.class));
  }

  @Test
  void requiresExistingStagedArtifactAndMatchingVersionProvenance() {
    when(artifactRepository.findByTenantIdAndVersionIdForUpdate(TENANT_ID, 700L))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.recordExportCandidate(TENANT_ID, 1, candidate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_NOT_FOUND");
    verify(artifactRepository, never()).save(any(VersionAssetArtifact.class));

    when(artifactRepository.findByTenantIdAndVersionIdForUpdate(TENANT_ID, 700L))
        .thenReturn(Optional.of(artifact));
    version.setCanonicalVersionId(NIL_UUID);
    assertThatThrownBy(() -> service.recordExportCandidate(TENANT_ID, 1, candidate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");
    verify(artifactRepository, never()).save(any(VersionAssetArtifact.class));

    version.setCanonicalVersionId(CANONICAL_VERSION_ID);
    version.setCanonicalTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> service.recordExportCandidate(TENANT_ID, 1, candidate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");
    verify(artifactRepository, never()).save(any(VersionAssetArtifact.class));
  }

  @Test
  void rejectsEpochOverflowAndProofReadbackDrift() {
    artifact.setStateEpoch(Long.MAX_VALUE);
    assertThatThrownBy(() -> service.recordExportCandidate(TENANT_ID, 1, candidate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");
    verify(artifactRepository, never()).save(any(VersionAssetArtifact.class));

    artifact.setStateEpoch(1L);
    service.recordExportCandidate(TENANT_ID, 1, candidate);
    artifact.setPublishedObjectProofsJson("[]");
    assertThatThrownBy(() -> service.readExportCandidate(TENANT_ID, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");
  }

  private static Version newVersion(long id, int number) {
    Version value = new Version();
    value.setId(id);
    value.setTenantId(TENANT_ID);
    value.setVersionNumber(number);
    value.setVersionState(VersionLifecycleState.DRAFT);
    value.setVersionStateEpoch(1L);
    value.setCanonicalTenantId(CANONICAL_TENANT_ID);
    value.setCanonicalVersionId(CANONICAL_VERSION_ID);
    value.setIdentitySourceGameRowId(80L);
    value.setIdentitySourceGameTenantKey(TENANT_ID);
    value.setIdentitySourceProvenanceKind("NEW_GAME_ROW");
    return value;
  }

  private static VersionAssetArtifact newStagedArtifact(long versionId, long epoch) {
    VersionAssetArtifact value = new VersionAssetArtifact();
    value.setId(90L);
    value.setTenantId(TENANT_ID);
    value.setVersionId(versionId);
    value.setExportedVersionNumber(1);
    value.setArtifactState(VersionAssetArtifactState.STAGED);
    value.setStateEpoch(epoch);
    value.setLastWorkflowId("stable-workflow");
    value.setExportedManifestAssetKeysJson("[]");
    return value;
  }

  private static ExportedAssetManifest candidate(
      String contentDigest, String usageKey, String manifestBytes) {
    String manifestHash = sha256(manifestBytes.getBytes(StandardCharsets.UTF_8));
    return new ExportedAssetManifest(
        manifestHash,
        1,
        List.of(usageKey),
        List.of(
            new PublishedArtifactDigest(
                usageKey,
                "BINARY",
                "artifacts/sha256/" + contentDigest.substring(7),
                contentDigest,
                "image/png",
                1)));
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static final UUID NIL_UUID = new UUID(0L, 0L);
}
