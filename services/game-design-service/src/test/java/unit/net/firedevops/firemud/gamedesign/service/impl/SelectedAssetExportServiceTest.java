package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventoryReadService;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService.CandidateBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

/** The selected source and durable candidate readbacks must gate every immutable object write. */
class SelectedAssetExportServiceTest {
  private final VersionAssetPublicationRepository legacy =
      mock(VersionAssetPublicationRepository.class);
  private final S3Client s3 = mock(S3Client.class);
  private final SelectedDraftAssetInventoryReadService inventoryReader =
      mock(SelectedDraftAssetInventoryReadService.class);
  private final VersionAssetExportCandidateService candidateService =
      mock(VersionAssetExportCandidateService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final PublicationDigestRequestBinding request =
      PublicationDigestRequestBinding.full(
          "11111111-1111-4111-8111-111111111111", "19", "selected-export-request");
  private AssetExportServiceImpl exporter;
  private SelectedDraftAssetInventory inventory;
  private byte[] inventoryBytes;
  private byte[] manifestBytes;
  private ExportedAssetManifest manifest;
  private CandidateBinding candidate;

  @BeforeEach
  void setUp() throws Exception {
    byte[] operationBytes = "selected-operation".getBytes(StandardCharsets.UTF_8);
    String commitDigest = "sha256:" + "a".repeat(64);
    inventoryBytes =
        Rfc8785CanonicalJson.canonicalizeUtf8(
            mapper.writeValueAsString(
                Map.ofEntries(
                    Map.entry("schema", SelectedDraftAssetInventory.SCHEMA),
                    Map.entry(
                        "requestPreimageBase64",
                        Base64.getEncoder().encodeToString(request.canonicalPreimage())),
                    Map.entry("requestDigest", request.requestDigest()),
                    Map.entry(
                        "operationBase64", Base64.getEncoder().encodeToString(operationBytes)),
                    Map.entry("selectedCommitDigest", commitDigest))));
    String inventoryDigest = sha256(inventoryBytes);
    inventory = mock(SelectedDraftAssetInventory.class);
    when(inventory.request()).thenReturn(request);
    when(inventory.digest()).thenReturn(inventoryDigest);
    when(inventory.canonicalBytes()).thenReturn(inventoryBytes.clone());
    when(inventory.assets()).thenReturn(List.of());
    when(inventoryReader.read(request)).thenReturn(inventory);

    manifestBytes =
        Rfc8785CanonicalJson.canonicalizeUtf8(
            mapper.writeValueAsString(
                Map.ofEntries(
                    Map.entry("schema", "game-design-selected-asset-manifest/v1"),
                    Map.entry("manifestSchemaVersion", 1),
                    Map.entry("selectedInventorySchema", SelectedDraftAssetInventory.SCHEMA),
                    Map.entry("selectedInventoryDigest", inventoryDigest),
                    Map.entry("artifacts", List.of()))));
    manifest = new ExportedAssetManifest(sha256(manifestBytes), 1, List.of(), List.of());
    candidate =
        new CandidateBinding(
            request.requestDigest(),
            sha256(operationBytes),
            commitDigest,
            SelectedDraftAssetInventory.SCHEMA,
            inventoryDigest,
            manifest,
            request.canonicalPreimage(),
            inventoryBytes,
            manifestBytes);
    when(candidateService.readSelectedCandidate(request)).thenReturn(candidate);
    when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenReturn(objectBytes(manifestBytes, "application/json"));
    exporter =
        new AssetExportServiceImpl(
            legacy, s3, properties(), mapper, inventoryReader, candidateService);
  }

  @Test
  void durableCandidateAndIndependentInventoryReadbacksPrecedeConditionalWrite() {
    var result = exporter.exportSelectedAssets(request);

    assertEquals(manifest, result.manifest());
    assertEquals(candidate, result.candidateBinding());
    verifyRecordedCandidate(org.mockito.Mockito.times(1));
    verify(inventoryReader, org.mockito.Mockito.times(2)).read(request);
    verify(s3)
        .putObject(
            argThat(
                (PutObjectRequest put) ->
                    put.key().equals("manifests/sha256/" + manifest.manifestHash().substring(7))
                        && "*".equals(put.ifNoneMatch())),
            any(RequestBody.class));
    InOrder order = inOrder(candidateService, s3);
    order
        .verify(candidateService)
        .recordSelectedCandidate(
            eq(inventory),
            eq(manifest),
            argThat(bytes -> java.util.Arrays.equals(bytes, manifestBytes)));
    order.verify(candidateService).readSelectedCandidate(request);
    order.verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void missingCompleteInventoryDeniesBeforeCandidateOrObjectWrites() {
    when(inventoryReader.read(request))
        .thenThrow(new IllegalStateException("complete inventory unavailable"));

    assertThrows(IllegalStateException.class, () -> exporter.exportSelectedAssets(request));

    verifyNoInteractions(candidateService, s3);
  }

  @Test
  void changedIndependentInventoryReadbackDeniesBeforeObjectWrites() {
    byte[] changedBytes = inventoryBytes.clone();
    changedBytes[changedBytes.length - 2] = (byte) (changedBytes[changedBytes.length - 2] ^ 1);
    SelectedDraftAssetInventory changed = mock(SelectedDraftAssetInventory.class);
    when(changed.digest()).thenReturn(sha256(changedBytes));
    when(changed.canonicalBytes()).thenReturn(changedBytes);
    when(inventoryReader.read(request)).thenReturn(inventory, changed);

    assertThrows(IllegalStateException.class, () -> exporter.exportSelectedAssets(request));

    verifyRecordedCandidate(org.mockito.Mockito.times(1));
    verifyNoInteractions(s3);
  }

  @Test
  void candidateMismatchDeniesBeforeObjectWrites() {
    CandidateBinding changed =
        new CandidateBinding(
            "b".repeat(64),
            candidate.operationDigest(),
            candidate.selectedCommitDigest(),
            candidate.inventorySchema(),
            candidate.inventoryDigest(),
            candidate.manifest(),
            candidate.requestPreimage(),
            candidate.inventoryBytes(),
            candidate.manifestBytes());
    when(candidateService.readSelectedCandidate(request)).thenReturn(changed);

    assertThrows(IllegalStateException.class, () -> exporter.exportSelectedAssets(request));

    verifyRecordedCandidate(org.mockito.Mockito.times(1));
    verify(inventoryReader, org.mockito.Mockito.times(1)).read(request);
    verifyNoInteractions(s3);
  }

  @Test
  void finalizationGuardDelegatesTheExactReadbackCandidateAndManifest() {
    var result = exporter.exportSelectedAssets(request);

    exporter.requireSelectedCandidateForFinalization(request, result);

    verify(candidateService).requireSelectedCandidateForFinalization(request, candidate);
  }

  @Test
  void exactConditionalCollisionIsResolvedByExactObjectReadbackAndRetry() {
    doThrow(S3Exception.builder().statusCode(412).message("precondition failed").build())
        .when(s3)
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));

    var first = exporter.exportSelectedAssets(request);
    var retry = exporter.exportSelectedAssets(request);

    assertEquals(first, retry);
    verify(s3, org.mockito.Mockito.times(2)).getObjectAsBytes(any(GetObjectRequest.class));
    verifyRecordedCandidate(org.mockito.Mockito.times(2));
  }

  @Test
  void missingObjectReadbackAfterLostConditionalPutRemainsPendingAndRetryUsesSameCandidate() {
    when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(
            S3Exception.builder().statusCode(503).message("conditional put response lost").build())
        .thenReturn(null);
    when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("object not visible yet").build())
        .thenReturn(objectBytes(manifestBytes, "application/json"));

    assertThrows(
        AssetExportService.SelectedExportOutcomePendingException.class,
        () -> exporter.exportSelectedAssets(request));
    var retry = exporter.exportSelectedAssets(request);

    assertEquals(manifest, retry.manifest());
    assertEquals(candidate, retry.candidateBinding());
    verifyRecordedCandidate(org.mockito.Mockito.times(2));
    verify(s3, org.mockito.Mockito.times(2))
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void deniedObjectReadbackRemainsPendingRatherThanClaimingByteConflict() {
    when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(403).message("readback denied").build());

    assertThrows(
        AssetExportService.SelectedExportOutcomePendingException.class,
        () -> exporter.exportSelectedAssets(request));

    verifyRecordedCandidate(org.mockito.Mockito.times(1));
    verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void objectReadbackMismatchLeavesCandidatePendingAndFailsClosed() {
    when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenReturn(objectBytes("different".getBytes(StandardCharsets.UTF_8), "application/json"));

    var failure =
        assertThrows(IllegalStateException.class, () -> exporter.exportSelectedAssets(request));

    assertEquals("SELECTED_ASSET_OBJECT_READBACK_MISMATCH", failure.getMessage());
    verifyRecordedCandidate(org.mockito.Mockito.times(1));
  }

  private void verifyRecordedCandidate(org.mockito.verification.VerificationMode mode) {
    verify(candidateService, mode)
        .recordSelectedCandidate(
            org.mockito.ArgumentMatchers.eq(inventory),
            org.mockito.ArgumentMatchers.eq(manifest),
            argThat(bytes -> java.util.Arrays.equals(bytes, manifestBytes)));
  }

  private static AssetStoreProperties properties() {
    AssetStoreProperties result = new AssetStoreProperties();
    result.setBucket("private-assets");
    result.setEndpoint("http://localhost:9000");
    result.setRegion("ap-southeast-2");
    result.setAccessKey("test");
    result.setSecretKey("test");
    return result;
  }

  private static ResponseBytes<GetObjectResponse> objectBytes(byte[] bytes, String contentType) {
    return ResponseBytes.fromByteArray(
        GetObjectResponse.builder()
            .contentLength((long) bytes.length)
            .contentType(contentType)
            .build(),
        bytes);
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
