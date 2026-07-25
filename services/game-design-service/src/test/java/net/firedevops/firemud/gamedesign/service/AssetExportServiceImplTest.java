package net.firedevops.firemud.gamedesign.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AssetExportServiceImplTest {
  @Mock private GameAssetRepository repository;
  @Mock private S3Client s3Client;

  private AssetExportServiceImpl service;

  @BeforeEach
  void setup() {
    AssetStoreProperties props = new AssetStoreProperties();
    props.setBucket("bucket");
    props.setEndpoint("http://localhost:9000");
    props.setRegion("ap-southeast-2");
    props.setAccessKey("a");
    props.setSecretKey("s");
    service = new AssetExportServiceImpl(repository, s3Client, props, new ObjectMapper());
  }

  @Test
  void exportUploadsAssetsAndManifest() {
    GameAsset asset = new GameAsset();
    asset.setTenantId("t");
    asset.setFileName("logo.png");
    asset.setContentType("image/png");
    asset.setData("data".getBytes(StandardCharsets.UTF_8));
    when(repository.findByTenantIdAndVersionId("t", 7L)).thenReturn(List.of(asset));

    ExportedAssetManifest manifest = service.exportAssets("t", 7L, 1);

    verify(s3Client)
        .putObject(
            argThat((PutObjectRequest r) -> r.key().equals("t/1/logo.png")),
            any(RequestBody.class));
    verify(s3Client)
        .putObject(
            argThat((PutObjectRequest r) -> r.key().equals("t/1/manifest.json")),
            any(RequestBody.class));
    org.junit.jupiter.api.Assertions.assertEquals(2, manifest.requiredManifestAssetKeys().size());
  }

  @Test
  void exportDoesNotPublishAssetThatIsNotMappedToRequestedVersion() {
    GameAsset mappedAsset = new GameAsset();
    mappedAsset.setTenantId("t");
    mappedAsset.setFileName("mapped.png");
    mappedAsset.setContentType("image/png");
    mappedAsset.setData("mapped".getBytes(StandardCharsets.UTF_8));
    when(repository.findByTenantIdAndVersionId("t", 7L)).thenReturn(List.of(mappedAsset));

    ExportedAssetManifest manifest = service.exportAssets("t", 7L, 1);

    verify(repository).findByTenantIdAndVersionId("t", 7L);
    verify(repository, never()).findByTenantId("t");
    verify(s3Client)
        .putObject(
            argThat((PutObjectRequest r) -> r.key().equals("t/1/mapped.png")),
            any(RequestBody.class));
    verify(s3Client, never())
        .putObject(
            argThat((PutObjectRequest r) -> r.key().equals("t/1/unmapped.png")),
            any(RequestBody.class));
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of("mapped.png", "manifest.json"), manifest.requiredManifestAssetKeys());
  }

  @Test
  void deleteExportedAssetsUsesExactManifestKeyList() {
    service.deleteExportedAssets("t", 1, List.of("logo.png", "manifest.json"));

    verify(s3Client)
        .deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals("t/1/logo.png")));
    verify(s3Client)
        .deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals("t/1/manifest.json")));
  }
}
