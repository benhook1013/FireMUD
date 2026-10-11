package net.firedevops.firemud.gamedesign.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AssetExportServiceImplTest {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("8b0c1a0f-b8c9-4c4b-9021-381f57699001");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("8b0c1a0f-b8c9-4c4b-9021-381f57699002");
  private static final String TENANT_ID = "owner-tenant-key";
  private static final int VERSION_NUMBER = 7;
  private static final String PUBLIC_BASE_URL = "https://assets.example.invalid/assets";
  private static final String PRIVATE_ENDPOINT = "http://minio.internal:9000";

  @Mock private VersionAssetPublicationService publicationService;
  @Mock private VersionAssetExportCandidateService candidateService;
  @Mock private S3Client s3Client;

  private final AtomicReference<ExportedAssetManifest> storedCandidate = new AtomicReference<>();
  private final Map<String, StoredObject> objectStore = new HashMap<>();
  private final Set<String> failPutBeforeCommit = new HashSet<>();
  private final Set<String> losePutAcknowledgementAfterCommit = new HashSet<>();
  private final Set<String> failReadback = new HashSet<>();

  private AssetExportServiceImpl service;
  private ExportSnapshot snapshot;

  @BeforeEach
  void setup() {
    objectStore.clear();
    failPutBeforeCommit.clear();
    losePutAcknowledgementAfterCommit.clear();
    failReadback.clear();
    storedCandidate.set(null);
    snapshot = singleAssetSnapshot();
    lenient()
        .when(publicationService.freezeOrReadSnapshot(TENANT_ID, VERSION_NUMBER))
        .thenAnswer(ignored -> snapshot);
    lenient()
        .when(publicationService.readFrozenSnapshot(TENANT_ID, VERSION_NUMBER))
        .thenAnswer(ignored -> copySnapshot(snapshot));
    lenient()
        .when(
            candidateService.recordExportCandidate(
                any(String.class), anyInt(), any(ExportedAssetManifest.class)))
        .thenAnswer(
            invocation -> {
              ExportedAssetManifest requested = invocation.getArgument(2);
              ExportedAssetManifest existing = storedCandidate.get();
              if (existing == null) {
                storedCandidate.set(requested);
                existing = requested;
              }
              if (!existing.equals(requested)) {
                throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_CONFLICT");
              }
              return existing;
            });
    lenient()
        .when(candidateService.readExportCandidate(any(String.class), anyInt()))
        .thenAnswer(
            ignored -> {
              ExportedAssetManifest existing = storedCandidate.get();
              if (existing == null) {
                throw new IllegalStateException("candidate not found");
              }
              return existing;
            });
    lenient()
        .when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(this::putObject);
    lenient()
        .when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenAnswer(this::getObject);
    service = newService(PUBLIC_BASE_URL);
  }

  @Test
  void readsAndMatchesDurableSnapshotBeforeConditionalObjectWrites() {
    service.exportAssets(TENANT_ID, VERSION_NUMBER);

    InOrder order = inOrder(publicationService, candidateService, s3Client);
    order.verify(publicationService).freezeOrReadSnapshot(TENANT_ID, VERSION_NUMBER);
    order.verify(publicationService).readFrozenSnapshot(TENANT_ID, VERSION_NUMBER);
    order
        .verify(candidateService)
        .recordExportCandidate(
            org.mockito.ArgumentMatchers.eq(TENANT_ID),
            org.mockito.ArgumentMatchers.eq(VERSION_NUMBER),
            any(ExportedAssetManifest.class));
    order.verify(candidateService).readExportCandidate(TENANT_ID, VERSION_NUMBER);
    order
        .verify(s3Client)
        .putObject(
            argThat(
                (PutObjectRequest request) ->
                    "*".equals(request.ifNoneMatch())
                        && request.key().startsWith("artifacts/sha256/")),
            any(RequestBody.class));
  }

  @Test
  void unresolvedCandidateWriteBecomesOutcomePendingBeforeAnyObjectWrite() {
    org.mockito.Mockito.doThrow(new IllegalStateException("temporary candidate store failure"))
        .when(candidateService)
        .recordExportCandidate(
            org.mockito.ArgumentMatchers.eq(TENANT_ID),
            org.mockito.ArgumentMatchers.eq(VERSION_NUMBER),
            any(ExportedAssetManifest.class));

    AssetExportOutcomePendingException thrown =
        assertThrows(
            AssetExportOutcomePendingException.class,
            () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    org.junit.jupiter.api.Assertions.assertTrue(
        thrown.getMessage().startsWith("ASSET_EXPORT_OUTCOME_PENDING:"));
    verify(candidateService, never()).readExportCandidate(TENANT_ID, VERSION_NUMBER);
    verifyNoInteractions(s3Client);
    assertEquals(0, objectStore.size());
  }

  @Test
  void unresolvedCandidateReadbackBecomesOutcomePendingBeforeAnyObjectWrite() {
    org.mockito.Mockito.doThrow(new IllegalStateException("candidate readback unavailable"))
        .when(candidateService)
        .readExportCandidate(TENANT_ID, VERSION_NUMBER);

    AssetExportOutcomePendingException thrown =
        assertThrows(
            AssetExportOutcomePendingException.class,
            () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    org.junit.jupiter.api.Assertions.assertTrue(
        thrown.getMessage().startsWith("ASSET_EXPORT_OUTCOME_PENDING:"));
    verifyNoInteractions(s3Client);
    assertEquals(0, objectStore.size());
  }

  @Test
  void conflictingPersistedCandidateIsDeniedBeforeAnyObjectWrite() {
    ExportedAssetManifest conflict = conflictingCandidate();
    org.mockito.Mockito.doReturn(conflict)
        .when(candidateService)
        .readExportCandidate(TENANT_ID, VERSION_NUMBER);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals("ASSET_EXPORT_CANDIDATE_CONFLICT", thrown.getMessage());
    verifyNoInteractions(s3Client);
    assertEquals(0, objectStore.size());
  }

  @Test
  void missingDurableSnapshotFailsClosedWithoutReadingCurrentAssetsOrWritingObjects() {
    when(publicationService.freezeOrReadSnapshot(TENANT_ID, VERSION_NUMBER)).thenReturn(null);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals("VERSION_ASSET_SNAPSHOT_UNAVAILABLE", thrown.getMessage());
    verify(publicationService, never()).readFrozenSnapshot(TENANT_ID, VERSION_NUMBER);
    verifyNoInteractions(s3Client);
  }

  @Test
  void exportsCanonicalActualByteDigestsAndManifestWithoutPrivateStorageLocation()
      throws Exception {
    ExportedAssetManifest exported = service.exportAssets(TENANT_ID, VERSION_NUMBER);

    assertEquals(exported, storedCandidate.get());
    byte[] assetBytes = "real uploaded bytes".getBytes(StandardCharsets.UTF_8);
    String assetHex = sha256Hex(assetBytes);
    String assetKey = "artifacts/sha256/" + assetHex;
    assertEquals("sha256:" + assetHex, exported.artifactDigests().get(0).contentDigest());
    assertEquals(assetKey, exported.artifactDigests().get(0).immutableObjectKey());
    assertEquals(List.of("branding/logo"), exported.requiredManifestAssetKeys());
    assertEquals(1, exported.manifestSchemaVersion());
    assertEquals(
        Set.of(
            assetKey, "manifests/sha256/" + exported.manifestHash().substring("sha256:".length())),
        objectStore.keySet());
    assertEquals("sha256:" + sha256Hex(manifestBytes(exported)), exported.manifestHash());

    String manifestJson = new String(manifestBytes(exported), StandardCharsets.UTF_8);
    assertEquals(
        "{\"schemaVersion\":1,\"assets\":{\"branding/logo\":{\"usageKey\":\"branding/logo\","
            + "\"artifactKind\":\"BINARY\",\"immutableObjectKey\":\""
            + assetKey
            + "\",\"contentDigest\":\"sha256:"
            + assetHex
            + "\",\"contentType\":\"image/png\",\"artifactSchemaVersion\":1,"
            + "\"producerService\":\"game-design-service\",\"versionId\":\""
            + CANONICAL_VERSION_ID
            + "\",\"url\":\""
            + PUBLIC_BASE_URL
            + "/"
            + assetKey
            + "\"}}}",
        manifestJson);
    JsonNode manifest = new ObjectMapper().readTree(manifestJson);
    assertEquals(1, manifest.get("schemaVersion").asInt());
    assertEquals(1, manifest.get("assets").size());
    assertEquals("branding/logo", manifest.get("assets").propertyNames().iterator().next());
    assertEquals(-1, manifestJson.indexOf("minio.internal"));
    assertEquals(-1, manifestJson.indexOf("bucket"));
    verify(s3Client)
        .putObject(
            argThat(
                (PutObjectRequest request) ->
                    request
                            .key()
                            .equals(
                                "manifests/sha256/"
                                    + exported.manifestHash().substring("sha256:".length()))
                        && "application/json".equals(request.contentType())
                        && "*".equals(request.ifNoneMatch())),
            any(RequestBody.class));
  }

  @Test
  void emptyMappedSnapshotCreatesRealCanonicalEmptyManifest() throws Exception {
    snapshot = snapshot(List.of());

    ExportedAssetManifest exported = service.exportAssets(TENANT_ID, VERSION_NUMBER);

    assertEquals(List.of(), exported.requiredManifestAssetKeys());
    assertEquals(List.of(), exported.artifactDigests());
    assertEquals(
        "{\"schemaVersion\":1,\"assets\":{}}",
        new String(manifestBytes(exported), StandardCharsets.UTF_8));
    assertEquals(
        Set.of("manifests/sha256/" + exported.manifestHash().substring("sha256:".length())),
        objectStore.keySet());
  }

  @Test
  void serializesAssetsInExactUtf8ByteUsageOrder() {
    String bmpKey = "\ue000";
    String supplementaryKey = "\ud800\udc00";
    snapshot =
        snapshot(
            List.of(
                selection(supplementaryKey, 25L, "supplementary", "image/png"),
                selection(bmpKey, 24L, "bmp", "image/png")));

    ExportedAssetManifest exported = service.exportAssets(TENANT_ID, VERSION_NUMBER);
    String manifestJson = new String(manifestBytes(exported), StandardCharsets.UTF_8);

    assertEquals(List.of(bmpKey, supplementaryKey), exported.requiredManifestAssetKeys());
    org.junit.jupiter.api.Assertions.assertTrue(
        manifestJson.indexOf('"' + bmpKey + '"')
            < manifestJson.indexOf('"' + supplementaryKey + '"'));
  }

  @Test
  void serviceRestartReusesExactPersistedSnapshotAndImmutableKeys() {
    ExportedAssetManifest first = service.exportAssets(TENANT_ID, VERSION_NUMBER);
    AssetExportServiceImpl restarted = newService(PUBLIC_BASE_URL);

    ExportedAssetManifest retry = restarted.exportAssets(TENANT_ID, VERSION_NUMBER);

    assertEquals(first, retry);
    verify(publicationService, org.mockito.Mockito.times(2))
        .freezeOrReadSnapshot(TENANT_ID, VERSION_NUMBER);
    verify(publicationService, org.mockito.Mockito.times(2))
        .readFrozenSnapshot(TENANT_ID, VERSION_NUMBER);
    assertEquals(2, objectStore.size());
  }

  @Test
  void rejectsIndependentlyReadSnapshotMismatchBeforeAnyStoreWrite() {
    ExportSnapshot changedBytes =
        snapshot(List.of(selection("branding/logo", 24L, "different uploaded bytes", "image/png")));
    when(publicationService.readFrozenSnapshot(TENANT_ID, VERSION_NUMBER)).thenReturn(changedBytes);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals("VERSION_ASSET_SNAPSHOT_MISMATCH", thrown.getMessage());
    assertEquals(0, objectStore.size());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsSnapshotSourceIdentityOrContentTypeSubstitutionBeforeAnyStoreWrite() {
    when(publicationService.readFrozenSnapshot(TENANT_ID, VERSION_NUMBER))
        .thenReturn(
            snapshot(List.of(selection("branding/logo", 25L, "real uploaded bytes", "image/png"))));

    IllegalStateException changedSourceId =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));
    assertEquals("VERSION_ASSET_SNAPSHOT_MISMATCH", changedSourceId.getMessage());
    verifyNoInteractions(s3Client);

    org.mockito.Mockito.clearInvocations(publicationService);
    when(publicationService.readFrozenSnapshot(TENANT_ID, VERSION_NUMBER))
        .thenReturn(
            snapshot(
                List.of(selection("branding/logo", 24L, "real uploaded bytes", "image/jpeg"))));

    IllegalStateException changedContentType =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));
    assertEquals("VERSION_ASSET_SNAPSHOT_MISMATCH", changedContentType.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsOutOfScopeOrIncompleteCanonicalSnapshotBeforeAnyStoreWrite() {
    snapshot =
        snapshot(
            "another-tenant",
            VERSION_NUMBER,
            19L,
            1L,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            snapshot.items());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals(
        "VERSION_ASSET_SNAPSHOT_INVALID: incomplete or out-of-scope snapshot", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsNilCanonicalUuidBeforeAnyStoreWrite() {
    snapshot = snapshot(List.of(), NIL_UUID, CANONICAL_VERSION_ID);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals(
        "VERSION_ASSET_SNAPSHOT_INVALID: incomplete or out-of-scope snapshot", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsNonpositiveOwnerVersionIdentityBeforeAnyStoreWrite() {
    snapshot =
        snapshot(
            TENANT_ID,
            VERSION_NUMBER,
            0L,
            3L,
            CANONICAL_TENANT_ID,
            CANONICAL_VERSION_ID,
            List.of());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals(
        "VERSION_ASSET_SNAPSHOT_INVALID: incomplete or out-of-scope snapshot", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsMalformedUtf8UsageKeyBeforeSortingOrWriting() {
    snapshot = snapshot(List.of(selection("\ud800", 24L, "real uploaded bytes", "image/png")));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals(
        "VERSION_ASSET_SNAPSHOT_INVALID: usageKey is not valid UTF-8", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsDuplicateUsageKeysBeforeAnyStoreWrite() {
    snapshot =
        snapshot(
            List.of(
                selection("branding/logo", 24L, "first", "image/png"),
                selection("branding/logo", 25L, "second", "image/png")));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals("ASSET_USAGE_KEY_COLLISION", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsReservedManifestKeyAndDuplicateSourceIdentityBeforeAnyStoreWrite() {
    snapshot =
        snapshot(List.of(selection("manifest.json", 24L, "real uploaded bytes", "image/png")));

    IllegalStateException reservedKey =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals("ASSET_USAGE_KEY_COLLISION", reservedKey.getMessage());
    verifyNoInteractions(s3Client);

    org.mockito.Mockito.clearInvocations(publicationService);
    snapshot =
        snapshot(
            List.of(
                selection("branding/logo", 24L, "first", "image/png"),
                selection("branding/icon", 24L, "second", "image/png")));

    IllegalStateException duplicateSource =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals(
        "VERSION_ASSET_SNAPSHOT_INVALID: duplicate source asset identity",
        duplicateSource.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsInvalidSourceIdentityAndDigestBeforeAnyStoreWrite() {
    snapshot =
        snapshot(List.of(selection("branding/logo", 0L, "real uploaded bytes", "image/png")));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals(
        "VERSION_ASSET_SNAPSHOT_INVALID: incomplete source asset identity", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsSourceByteDigestMismatchBeforeAnyStoreWrite() {
    byte[] bytes = "real uploaded bytes".getBytes(StandardCharsets.UTF_8);
    snapshot =
        snapshot(
            List.of(
                new AssetSelection(
                    "branding/logo", 24L, bytes, "image/png", "sha256:" + "f".repeat(64))));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals("ASSET_SOURCE_BYTES_MISMATCH", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void rejectsInvalidPublicOriginsBeforeAnyExternalWrites() {
    for (String invalidBaseUrl :
        Arrays.asList(
            null,
            "http://assets.example.invalid/assets",
            "https://user@assets.example.invalid/assets",
            "https://assets.example.invalid/assets?x=1",
            "https://assets.example.invalid/assets#fragment",
            "https://assets.example.invalid/private",
            "https://minio.internal:9000/assets")) {
      AssetExportServiceImpl invalidService = newService(invalidBaseUrl);

      assertThrows(
          IllegalStateException.class,
          () -> invalidService.exportAssets(TENANT_ID, VERSION_NUMBER),
          String.valueOf(invalidBaseUrl));
    }
    verifyNoInteractions(s3Client);
  }

  @Test
  void identicalConditionalExistingObjectsMakeRetryIdempotent() {
    ExportedAssetManifest first = service.exportAssets(TENANT_ID, VERSION_NUMBER);
    ExportedAssetManifest retry = service.exportAssets(TENANT_ID, VERSION_NUMBER);

    assertEquals(first, retry);
    assertEquals(2, objectStore.size());
    verify(s3Client, org.mockito.Mockito.times(4))
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void changedPreexistingObjectIsDeniedWithoutOverwritingOrDeletingIt() {
    byte[] original = "original immutable bytes".getBytes(StandardCharsets.UTF_8);
    String key =
        "artifacts/sha256/" + sha256Hex("real uploaded bytes".getBytes(StandardCharsets.UTF_8));
    objectStore.put(key, new StoredObject(original, "image/png"));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    org.junit.jupiter.api.Assertions.assertTrue(
        thrown.getMessage().startsWith("IMMUTABLE_OBJECT_CONFLICT"));
    assertArrayEquals(original, objectStore.get(key).bytes());
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    assertEquals(1, objectStore.size());
  }

  @Test
  void lostPutAcknowledgementRecoversOnlyAfterExactCommittedReadback() {
    String key =
        "artifacts/sha256/" + sha256Hex("real uploaded bytes".getBytes(StandardCharsets.UTF_8));
    losePutAcknowledgementAfterCommit.add(key);

    ExportedAssetManifest exported = service.exportAssets(TENANT_ID, VERSION_NUMBER);

    assertEquals(
        "sha256:" + sha256Hex("real uploaded bytes".getBytes(StandardCharsets.UTF_8)),
        exported.artifactDigests().get(0).contentDigest());
    assertEquals(2, objectStore.size());
  }

  @Test
  void unavailableReadbackAfterAmbiguousPutProducesOutcomePendingAndRetainsBytes() {
    String key =
        "artifacts/sha256/" + sha256Hex("real uploaded bytes".getBytes(StandardCharsets.UTF_8));
    failPutBeforeCommit.add(key);
    failReadback.add(key);

    AssetExportOutcomePendingException thrown =
        assertThrows(
            AssetExportOutcomePendingException.class,
            () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    org.junit.jupiter.api.Assertions.assertTrue(
        thrown.getMessage().startsWith("ASSET_EXPORT_OUTCOME_PENDING:"));
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    assertEquals(0, objectStore.size());
  }

  @Test
  void unavailableReadbackAfterCommittedPutIsOutcomePendingWithoutCleanup() {
    String key =
        "artifacts/sha256/" + sha256Hex("real uploaded bytes".getBytes(StandardCharsets.UTF_8));
    failReadback.add(key);

    assertThrows(
        AssetExportOutcomePendingException.class,
        () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    assertEquals(1, objectStore.size());
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void contentTypeMismatchOnReadbackDeniesObjectProof() {
    byte[] bytes = "real uploaded bytes".getBytes(StandardCharsets.UTF_8);
    String key = "artifacts/sha256/" + sha256Hex(bytes);
    objectStore.put(key, new StoredObject(bytes, "application/octet-stream"));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> service.exportAssets(TENANT_ID, VERSION_NUMBER));

    org.junit.jupiter.api.Assertions.assertTrue(
        thrown.getMessage().startsWith("IMMUTABLE_OBJECT_CONFLICT"));
    assertEquals(1, objectStore.size());
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void deleteFailsClosedWithoutIssuingAnyObjectDeletion() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.deleteExportedAssets(TENANT_ID, VERSION_NUMBER, List.of("branding/logo")));

    assertEquals(
        "CONTENT_ADDRESSED_PURGE_PROOF_REQUIRED: version-scoped deletion cannot select shared immutable objects",
        thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  private AssetExportServiceImpl newService(String publicBaseUrl) {
    AssetStoreProperties properties = new AssetStoreProperties();
    properties.setBucket("private-bucket");
    properties.setEndpoint(PRIVATE_ENDPOINT);
    properties.setPublicBaseUrl(publicBaseUrl);
    return new AssetExportServiceImpl(publicationService, candidateService, s3Client, properties);
  }

  private ExportedAssetManifest conflictingCandidate() {
    byte[] bytes = "real uploaded bytes".getBytes(StandardCharsets.UTF_8);
    String hex = sha256Hex(bytes);
    return new ExportedAssetManifest(
        "sha256:" + "0".repeat(64),
        1,
        List.of("branding/logo"),
        List.of(
            new PublishedArtifactDigest(
                "branding/logo",
                "BINARY",
                "artifacts/sha256/" + hex,
                "sha256:" + hex,
                "image/png",
                1)));
  }

  private ExportSnapshot singleAssetSnapshot() {
    return snapshot(List.of(selection("branding/logo", 24L, "real uploaded bytes", "image/png")));
  }

  private ExportSnapshot snapshot(List<AssetSelection> items) {
    return snapshot(items, CANONICAL_TENANT_ID, CANONICAL_VERSION_ID);
  }

  private ExportSnapshot snapshot(
      List<AssetSelection> items, UUID canonicalTenantId, UUID canonicalVersionId) {
    return snapshot(
        TENANT_ID, VERSION_NUMBER, 19L, 3L, canonicalTenantId, canonicalVersionId, items);
  }

  private ExportSnapshot snapshot(
      String tenantId,
      int versionNumber,
      long versionId,
      long versionStateEpoch,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      List<AssetSelection> items) {
    return new ExportSnapshot(
        tenantId,
        versionId,
        versionNumber,
        versionStateEpoch,
        canonicalTenantId,
        canonicalVersionId,
        items);
  }

  private static AssetSelection selection(
      String usageKey, long assetId, String content, String contentType) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new AssetSelection(usageKey, assetId, bytes, contentType, "sha256:" + sha256Hex(bytes));
  }

  private byte[] manifestBytes(ExportedAssetManifest exported) {
    return objectStore
        .get("manifests/sha256/" + exported.manifestHash().substring("sha256:".length()))
        .bytes();
  }

  private static ExportSnapshot copySnapshot(ExportSnapshot source) {
    return new ExportSnapshot(
        source.tenantId(),
        source.versionId(),
        source.versionNumber(),
        source.capturedVersionStateEpoch(),
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.items().stream()
            .map(
                item ->
                    new AssetSelection(
                        item.usageKey(),
                        item.assetId(),
                        item.bytes(),
                        item.contentType(),
                        item.contentDigest()))
            .toList());
  }

  private software.amazon.awssdk.services.s3.model.PutObjectResponse putObject(
      org.mockito.invocation.InvocationOnMock invocation) throws IOException {
    PutObjectRequest request = invocation.getArgument(0);
    RequestBody body = invocation.getArgument(1);
    if (failPutBeforeCommit.remove(request.key())) {
      throw new IllegalStateException("simulated lost put result before durable readback");
    }
    if (objectStore.containsKey(request.key())) {
      throw S3Exception.builder()
          .statusCode(412)
          .message("conditional object already exists")
          .build();
    }
    byte[] bytes;
    try (InputStream input = body.contentStreamProvider().newStream()) {
      bytes = input.readAllBytes();
    }
    objectStore.put(request.key(), new StoredObject(bytes, request.contentType()));
    if (losePutAcknowledgementAfterCommit.remove(request.key())) {
      throw new IllegalStateException("simulated lost put acknowledgement after commit");
    }
    return PutObjectResponse.builder().build();
  }

  private ResponseBytes<GetObjectResponse> getObject(
      org.mockito.invocation.InvocationOnMock invocation) {
    GetObjectRequest request = invocation.getArgument(0);
    if (failReadback.contains(request.key())) {
      throw new IllegalStateException("simulated unavailable object readback");
    }
    StoredObject object = objectStore.get(request.key());
    if (object == null) {
      throw new IllegalStateException("simulated missing object readback");
    }
    GetObjectResponse metadata =
        GetObjectResponse.builder()
            .contentType(object.contentType())
            .contentLength((long) object.bytes().length)
            .build();
    return ResponseBytes.fromByteArray(metadata, object.bytes());
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        result.append(Character.forDigit((value >>> 4) & 0xf, 16));
        result.append(Character.forDigit(value & 0xf, 16));
      }
      return result.toString();
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
}
