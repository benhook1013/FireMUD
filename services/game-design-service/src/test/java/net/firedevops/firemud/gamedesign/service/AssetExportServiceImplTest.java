package net.firedevops.firemud.gamedesign.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

/** Export delegation proof; physical source validation belongs to the PostgreSQL owner tests. */
@ExtendWith(MockitoExtension.class)
class AssetExportServiceImplTest {
  @Mock private VersionAssetPublicationRepository publicationRepository;
  @Mock private S3Client s3Client;

  private AssetExportServiceImpl service;

  @BeforeEach
  void setup() {
    service = newService();
  }

  private AssetExportServiceImpl newService() {
    AssetStoreProperties props = new AssetStoreProperties();
    props.setBucket("bucket");
    props.setEndpoint("http://localhost:9000");
    props.setRegion("ap-southeast-2");
    props.setAccessKey("a");
    props.setSecretKey("s");
    return new AssetExportServiceImpl(publicationRepository, s3Client, props, new ObjectMapper());
  }

  @Test
  void exportsOnlyRetainedVersionSelection() throws IOException {
    when(publicationRepository.readFrozenSnapshot("t", 1))
        .thenReturn(snapshot("t", 1, List.of(asset("logo.png", "selected-bytes"))));

    ExportedAssetManifest manifest = service.exportAssets("t", 1);

    var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
    var bodies = ArgumentCaptor.forClass(RequestBody.class);
    verify(s3Client, times(2)).putObject(requests.capture(), bodies.capture());
    assertEquals(
        List.of("t/1/logo.png", "t/1/manifest.json"),
        requests.getAllValues().stream().map(PutObjectRequest::key).toList());
    assertEquals("image/png", requests.getAllValues().getFirst().contentType());
    assertArrayEquals(
        "selected-bytes".getBytes(StandardCharsets.UTF_8), bytes(bodies.getAllValues().getFirst()));
    assertEquals(
        "{\"logo.png\":\"http://localhost:9000/bucket/t/1/logo.png\"}",
        new String(bytes(bodies.getAllValues().getLast()), StandardCharsets.UTF_8));
    assertEquals(List.of("logo.png", "manifest.json"), manifest.requiredManifestAssetKeys());
    verify(publicationRepository).readFrozenSnapshot("t", 1);
  }

  @Test
  void independentlyReconstructedExporterReplaysDurableSelectionExactly() throws IOException {
    when(publicationRepository.readFrozenSnapshot("t", 1))
        .thenReturn(snapshot("t", 1, List.of(asset("logo.png", "original"))));

    ExportedAssetManifest first = service.exportAssets("t", 1);
    ExportedAssetManifest retry = newService().exportAssets("t", 1);

    assertEquals(first, retry);
    var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
    var bodies = ArgumentCaptor.forClass(RequestBody.class);
    verify(s3Client, times(4)).putObject(requests.capture(), bodies.capture());
    for (int index = 0; index < 2; index++) {
      assertEquals(requests.getAllValues().get(index), requests.getAllValues().get(index + 2));
      assertArrayEquals(
          bytes(bodies.getAllValues().get(index)), bytes(bodies.getAllValues().get(index + 2)));
    }
    verify(publicationRepository, times(2)).readFrozenSnapshot("t", 1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "No durable frozen Version asset snapshot exists",
        "Frozen Version asset snapshot identity is inconsistent",
        "Frozen Version asset source bytes or mapping failed verification",
        "Exact tenant Version was not found"
      })
  void ownerReadFailureDeniesBeforeAnyObjectStoreSideEffect(String ownerFailure) {
    when(publicationRepository.readFrozenSnapshot("t", 1))
        .thenThrow(new IllegalStateException(ownerFailure));

    var thrown = assertThrows(IllegalStateException.class, () -> service.exportAssets("t", 1));

    assertEquals(ownerFailure, thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void retryRevalidatesOwnerBytesBeforeAnyFurtherWrite() {
    when(publicationRepository.readFrozenSnapshot("t", 1))
        .thenReturn(snapshot("t", 1, List.of(asset("logo.png", "original"))))
        .thenThrow(
            new IllegalStateException(
                "Frozen Version asset source bytes or mapping failed verification"));
    service.exportAssets("t", 1);
    org.mockito.Mockito.clearInvocations(s3Client);

    assertThrows(IllegalStateException.class, () -> service.exportAssets("t", 1));

    verify(publicationRepository, times(2)).readFrozenSnapshot("t", 1);
    verifyNoInteractions(s3Client);
  }

  @Test
  void mismatchedReturnedTenantScopeDeniesBeforeWrites() {
    when(publicationRepository.readFrozenSnapshot("t", 1))
        .thenReturn(snapshot("other", 1, List.of(asset("logo.png", "data"))));

    var thrown = assertThrows(IllegalStateException.class, () -> service.exportAssets("t", 1));

    assertEquals("ASSET_EXPORT_SNAPSHOT_SCOPE_MISMATCH", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void mismatchedReturnedVersionScopeDeniesBeforeWrites() {
    when(publicationRepository.readFrozenSnapshot("t", 1))
        .thenReturn(snapshot("t", 2, List.of(asset("logo.png", "data"))));

    var thrown = assertThrows(IllegalStateException.class, () -> service.exportAssets("t", 1));

    assertEquals("ASSET_EXPORT_SNAPSHOT_SCOPE_MISMATCH", thrown.getMessage());
    verifyNoInteractions(s3Client);
  }

  @Test
  void explicitlyRetainedEmptySelectionExportsManifestOnly() {
    when(publicationRepository.readFrozenSnapshot("t", 1)).thenReturn(snapshot("t", 1, List.of()));

    var manifest = service.exportAssets("t", 1);

    verify(s3Client)
        .putObject(
            argThat((PutObjectRequest r) -> r.key().equals("t/1/manifest.json")),
            any(RequestBody.class));
    assertEquals(List.of("manifest.json"), manifest.requiredManifestAssetKeys());
  }

  @Test
  void deleteExportedAssetsUsesExactManifestKeyList() {
    service.deleteExportedAssets("t", 1, List.of("logo.png", "manifest.json", "logo.png"));

    verify(s3Client)
        .deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals("t/1/logo.png")));
    verify(s3Client)
        .deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals("t/1/manifest.json")));
    verifyNoInteractions(publicationRepository);
  }

  private static ExportSnapshot snapshot(
      String tenantId, int versionNumber, List<AssetSelection> assets) {
    return new ExportSnapshot(
        tenantId, 7L, versionNumber, 1L, UUID.randomUUID(), UUID.randomUUID(), assets);
  }

  private static AssetSelection asset(String usageKey, String data) {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    try {
      return new AssetSelection(
          usageKey,
          11L,
          bytes,
          "image/png",
          "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static byte[] bytes(RequestBody body) throws IOException {
    try (var stream = body.contentStreamProvider().newStream()) {
      return stream.readAllBytes();
    }
  }
}
