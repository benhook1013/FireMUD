package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportOutcomePendingException;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionAssetPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import tools.jackson.databind.ObjectMapper;

class VersionAssetExportCandidateServiceImplTest {
  private static final String TENANT_ID = "owner-tenant-key";
  private static final int VERSION_NUMBER = 7;
  private static final long VERSION_ID = 23L;
  private static final String PUBLISH_WORKFLOW_ID = "publish-7";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("8b0c1a0f-b8c9-4c4b-9021-381f57699001");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("8b0c1a0f-b8c9-4c4b-9021-381f57699002");

  private final VersionRepository versionRepository = mock(VersionRepository.class);
  private final GameRepository gameRepository = mock(GameRepository.class);
  private final PublishAttemptRepository attemptRepository = mock(PublishAttemptRepository.class);
  private final VersionAssetArtifactRepository artifactRepository =
      mock(VersionAssetArtifactRepository.class);
  private final VersionAssetPublicationService publicationService =
      mock(VersionAssetPublicationService.class);
  private final PlatformTransactionManager transactionManager = new NoOpTransactionManager();
  private final S3Client s3Client = mock(S3Client.class);
  private final Map<String, StoredObject> objectStore = new HashMap<>();

  private VersionAssetExportCandidateService candidateService;
  private Version version;
  private VersionAssetArtifact artifact;
  private PublishAttempt attempt;
  private ExportSnapshot snapshot;
  private AssetExportServiceImpl exporter;

  @BeforeEach
  void setUp() throws IOException {
    byte[] sourceBytes = "published logo bytes".getBytes(StandardCharsets.UTF_8);
    String sourceDigest = digest(sourceBytes);
    snapshot =
        new ExportSnapshot(
            TENANT_ID,
            VERSION_ID,
            VERSION_NUMBER,
            3L,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            List.of(
                new AssetSelection("branding/logo", 41L, sourceBytes, "image/png", sourceDigest)));

    version = new Version();
    version.setId(VERSION_ID);
    version.setTenantId(TENANT_ID);
    version.setVersionNumber(VERSION_NUMBER);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(3L);
    version.setCanonicalTenantId(CANONICAL_TENANT_ID);
    version.setCanonicalVersionId(CANONICAL_VERSION_ID);
    version.setIdentitySourceGameRowId(9L);
    version.setIdentitySourceGameTenantKey(TENANT_ID);
    version.setIdentitySourceProvenanceKind("NEW_GAME_ROW");

    artifact = new VersionAssetArtifact();
    artifact.setId(51L);
    artifact.setTenantId(TENANT_ID);
    artifact.setVersionId(VERSION_ID);
    artifact.setExportedVersionNumber(VERSION_NUMBER);
    artifact.setArtifactState(VersionAssetArtifactState.STAGED);
    artifact.setStateEpoch(1L);
    artifact.setLastWorkflowId(PUBLISH_WORKFLOW_ID);

    attempt = new PublishAttempt();
    attempt.setId(61L);
    attempt.setTenantId(TENANT_ID);
    attempt.setVersionId(VERSION_ID);
    attempt.setVersionNumber(VERSION_NUMBER);
    attempt.setPublishWorkflowId(PUBLISH_WORKFLOW_ID);
    attempt.setStatus(PublishAttemptStatus.PENDING);

    when(gameRepository.findByTenantIdForUpdate(TENANT_ID)).thenReturn(new Game());
    when(versionRepository.findByTenantIdAndVersionNumber(TENANT_ID, VERSION_NUMBER))
        .thenReturn(Optional.of(version));
    when(versionRepository.findByTenantIdAndIdForUpdate(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version));
    when(attemptRepository.findByPublishWorkflowIdForUpdate(PUBLISH_WORKFLOW_ID))
        .thenReturn(Optional.of(attempt));
    when(artifactRepository.findByTenantIdAndVersionId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(artifact));
    when(artifactRepository.findByTenantIdAndVersionIdForUpdate(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(artifact));
    when(artifactRepository.save(any(VersionAssetArtifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(publicationService.freezeOrReadSnapshot(TENANT_ID, VERSION_NUMBER)).thenReturn(snapshot);
    when(publicationService.readFrozenSnapshot(TENANT_ID, VERSION_NUMBER)).thenReturn(snapshot);
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            invocation -> {
              PutObjectRequest request = invocation.getArgument(0);
              RequestBody body = invocation.getArgument(1);
              byte[] bytes;
              try (InputStream input = body.contentStreamProvider().newStream()) {
                bytes = input.readAllBytes();
              }
              objectStore.put(request.key(), new StoredObject(bytes, request.contentType()));
              return PutObjectResponse.builder().build();
            });
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenAnswer(
            invocation -> {
              GetObjectRequest request = invocation.getArgument(0);
              StoredObject stored = objectStore.get(request.key());
              if (stored == null) {
                throw new IllegalStateException("object is missing");
              }
              return ResponseBytes.fromByteArray(
                  GetObjectResponse.builder()
                      .contentType(stored.contentType())
                      .contentLength((long) stored.bytes().length)
                      .build(),
                  stored.bytes());
            });

    candidateService =
        new VersionAssetExportCandidateServiceImpl(
            versionRepository,
            gameRepository,
            attemptRepository,
            artifactRepository,
            publicationService,
            transactionManager,
            new ObjectMapper());
    AssetStoreProperties properties = new AssetStoreProperties();
    properties.setBucket("private-bucket");
    properties.setEndpoint("http://minio.internal:9000");
    properties.setPublicBaseUrl("https://assets.example.invalid/assets");
    exporter =
        new AssetExportServiceImpl(publicationService, candidateService, s3Client, properties);
  }

  @Test
  void publishedRepairReusesCandidateAfterSuccessfulPublishAttempt() {
    ExportedAssetManifest published = exporter.exportAssets(TENANT_ID, VERSION_NUMBER);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(4L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(3L);
    String artifactKey = published.artifactDigests().get(0).immutableObjectKey();
    String manifestKey = "manifests/sha256/" + published.manifestHash().substring(7);
    objectStore.remove(artifactKey);
    objectStore.remove(manifestKey);
    clearInvocations(attemptRepository, artifactRepository, s3Client);

    ExportedAssetManifest repaired =
        exporter.repairPublishedAssets(TENANT_ID, VERSION_NUMBER, publishedBundle(published));

    assertEquals(published, repaired);
    ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client, times(2)).putObject(puts.capture(), any(RequestBody.class));
    assertEquals(
        List.of(artifactKey, manifestKey),
        puts.getAllValues().stream().map(PutObjectRequest::key).toList());
    assertEquals(
        List.of("image/png", "application/json"),
        puts.getAllValues().stream().map(PutObjectRequest::contentType).toList());
    assertEquals(
        List.of("*", "*"),
        puts.getAllValues().stream().map(PutObjectRequest::ifNoneMatch).toList());

    ArgumentCaptor<GetObjectRequest> readbacks = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3Client, times(2)).getObjectAsBytes(readbacks.capture());
    assertEquals(
        List.of(artifactKey, manifestKey),
        readbacks.getAllValues().stream().map(GetObjectRequest::key).toList());
    assertEquals(
        List.of(artifactKey, manifestKey), objectStore.keySet().stream().sorted().toList());
    assertEquals("image/png", objectStore.get(artifactKey).contentType());
    assertEquals(
        digest(snapshot.items().get(0).bytes()), digest(objectStore.get(artifactKey).bytes()));
    assertEquals(published.manifestHash(), digest(objectStore.get(manifestKey).bytes()));
    assertEquals("application/json", objectStore.get(manifestKey).contentType());
    verify(attemptRepository, never()).findByPublishWorkflowIdForUpdate(any());
    verify(artifactRepository, never()).save(any(VersionAssetArtifact.class));
  }

  @Test
  void mismatchedReleaseBundleFailsBeforeObjectWritesAfterSuccessfulPublish() {
    ExportedAssetManifest published = exporter.exportAssets(TENANT_ID, VERSION_NUMBER);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(4L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(3L);
    clearInvocations(s3Client);

    PublishedReleaseBundleDto mismatchedBundle =
        publishedBundleWithHash(published, "sha256:" + "0".repeat(64));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> exporter.repairPublishedAssets(TENANT_ID, VERSION_NUMBER, mismatchedBundle));

    assertEquals(
        PublishedReleaseBundleContract.REPAIR_ATTESTATION_MISMATCH
            + ": repair could not reproduce the attested manifest hash",
        thrown.getMessage());
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void changedAttestedArtifactProofFailsBeforeObjectWritesAfterSuccessfulPublish() {
    ExportedAssetManifest published = exporter.exportAssets(TENANT_ID, VERSION_NUMBER);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(4L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(3L);
    clearInvocations(s3Client);

    PublishedArtifactDigest digest = published.artifactDigests().get(0);
    String changedContentDigest = "sha256:" + "f".repeat(64);
    PublishedArtifactDigest changedDigest =
        new PublishedArtifactDigest(
            digest.usageKey(),
            digest.artifactKind(),
            "artifacts/sha256/" + changedContentDigest.substring(7),
            changedContentDigest,
            digest.contentType(),
            digest.artifactSchemaVersion());
    PublishedReleaseBundleDto mismatchedBundle =
        publishedBundleWithProof(
            published,
            published.manifestHash(),
            published.requiredManifestAssetKeys(),
            List.of(changedDigest));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> exporter.repairPublishedAssets(TENANT_ID, VERSION_NUMBER, mismatchedBundle));

    assertEquals(
        PublishedReleaseBundleContract.REPAIR_ATTESTATION_MISMATCH
            + ": repair could not reproduce the attested artifact/schema proof",
        thrown.getMessage());
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void changedAttestedRequiredKeysFailBeforeObjectWritesAfterSuccessfulPublish() {
    ExportedAssetManifest published = exporter.exportAssets(TENANT_ID, VERSION_NUMBER);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(4L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(3L);
    clearInvocations(s3Client);

    PublishedReleaseBundleDto mismatchedBundle =
        publishedBundleWithProof(
            published,
            published.manifestHash(),
            List.of("branding/banner"),
            published.artifactDigests());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> exporter.repairPublishedAssets(TENANT_ID, VERSION_NUMBER, mismatchedBundle));

    assertEquals(
        PublishedReleaseBundleContract.REPAIR_ATTESTED_ASSET_KEY_MISMATCH
            + ": repair could not reproduce the attested manifest asset key set",
        thrown.getMessage());
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void unavailableCandidateProofFailsBeforeObjectWritesAfterSuccessfulPublish() {
    exporter.exportAssets(TENANT_ID, VERSION_NUMBER);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(4L);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(3L);
    artifact.setArtifactDigestsJson(null);
    artifact.setPublishedObjectProofsJson(null);
    artifact.setManifestSchemaVersion(null);
    artifact.setCandidateSnapshotVersionId(null);
    clearInvocations(s3Client);

    assertThrows(
        AssetExportOutcomePendingException.class,
        () ->
            exporter.repairPublishedAssets(
                TENANT_ID,
                VERSION_NUMBER,
                publishedBundle(
                    new ExportedAssetManifest(
                        artifact.getManifestHash(),
                        1,
                        List.of("branding/logo"),
                        List.of(
                            new PublishedArtifactDigest(
                                "branding/logo",
                                "BINARY",
                                "artifacts/sha256/"
                                    + digest(snapshot.items().get(0).bytes()).substring(7),
                                digest(snapshot.items().get(0).bytes()),
                                "image/png",
                                1))))));

    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void failedCandidateRemainsReadableButCannotBeRecordedOrExported() {
    ExportedAssetManifest retained = exporter.exportAssets(TENANT_ID, VERSION_NUMBER);
    attempt.setStatus(PublishAttemptStatus.FAILED);
    version.setVersionState(VersionLifecycleState.FAILED);
    version.setVersionStateEpoch(4L);
    artifact.setArtifactState(VersionAssetArtifactState.FAILED);
    artifact.setStateEpoch(3L);
    clearInvocations(s3Client);

    assertEquals(retained, candidateService.readExportCandidate(TENANT_ID, VERSION_NUMBER));

    assertThrows(
        IllegalStateException.class,
        () -> candidateService.recordExportCandidate(TENANT_ID, VERSION_NUMBER, retained));
    assertThrows(
        IllegalStateException.class, () -> exporter.exportAssets(TENANT_ID, VERSION_NUMBER));

    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  private PublishedReleaseBundleDto publishedBundle(ExportedAssetManifest manifest) {
    return publishedBundleWithHash(manifest, manifest.manifestHash());
  }

  private PublishedReleaseBundleDto publishedBundleWithHash(
      ExportedAssetManifest manifest, String manifestHash) {
    return publishedBundleWithProof(
        manifest, manifestHash, manifest.requiredManifestAssetKeys(), manifest.artifactDigests());
  }

  private PublishedReleaseBundleDto publishedBundleWithProof(
      ExportedAssetManifest manifest,
      String manifestHash,
      List<String> requiredManifestAssetKeys,
      List<PublishedArtifactDigest> artifactDigests) {
    return new PublishedReleaseBundleDto(
        1L,
        TENANT_ID,
        VERSION_ID,
        VERSION_NUMBER,
        "v1",
        PUBLISH_WORKFLOW_ID,
        manifestHash,
        requiredManifestAssetKeys,
        List.of(),
        List.of(),
        "generation-1",
        false,
        null,
        java.time.LocalDateTime.now(),
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        "opaque-release-ref",
        manifest.manifestSchemaVersion(),
        artifactDigests);
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record StoredObject(byte[] bytes, String contentType) {
    private StoredObject {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private static final class NoOpTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
