package net.firedevops.firemud.gamedesign.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
  @Mock private VersionRepository versionRepository;
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
    service =
        new AssetExportServiceImpl(
            repository, s3Client, props, new ObjectMapper(), versionRepository);
  }

  @Test
  void exportUploadsAssetsAndManifest() {
    GameAsset asset = gameAsset("logo.png", "data");
    when(repository.findByTenantId("t")).thenReturn(List.of(asset));

    ExportedAssetManifest manifest = service.exportAssets("t", 1);

    verify(s3Client)
        .putObject(
            argThat((PutObjectRequest r) -> r.key().equals("t/1/logo.png")),
            any(RequestBody.class));
    verify(s3Client)
        .putObject(
            argThat((PutObjectRequest r) -> r.key().equals("t/1/manifest.json")),
            any(RequestBody.class));
    assertEquals(2, manifest.requiredManifestAssetKeys().size());
  }

  @Test
  void sameVersionRetryReusesFrozenSnapshot() {
    GameAsset original = gameAsset("logo.png", "original");
    GameAsset changed = gameAsset("changed.png", "changed");
    when(repository.findByTenantId("t")).thenReturn(List.of(original), List.of(changed));

    ExportedAssetManifest first = service.exportAssets("t", 1);
    ExportedAssetManifest retry = service.exportAssets("t", 1);

    assertEquals(first, retry);
    verify(repository, times(1)).findByTenantId("t");
  }

  @ParameterizedTest
  @EnumSource(
      value = VersionLifecycleState.class,
      names = {"PUBLISHED", "ACTIVE"})
  void publishedAndActiveRetryFailsWithoutFrozenSnapshot(VersionLifecycleState state) {
    Version version = version(state);
    when(versionRepository.findByTenantIdAndVersionNumber("t", 1)).thenReturn(Optional.of(version));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> service.exportAssets("t", 1));

    assertEquals("REPAIR_VERSION_SCOPE_UNAVAILABLE", thrown.getMessage());
    verify(repository, never()).findByTenantId("t");
    verifyNoInteractions(s3Client);
  }

  @Test
  void publishedRetryReusesSnapshotFrozenBeforePublication() {
    Version version = version(VersionLifecycleState.DRAFT);
    when(versionRepository.findByTenantIdAndVersionNumber("t", 1)).thenReturn(Optional.of(version));

    GameAsset original = gameAsset("logo.png", "original");
    GameAsset changed = gameAsset("changed.png", "changed");
    when(repository.findByTenantId("t")).thenReturn(List.of(original), List.of(changed));

    ExportedAssetManifest first = service.exportAssets("t", 1);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    ExportedAssetManifest retry = service.exportAssets("t", 1);

    assertEquals(first, retry);
    verify(repository, times(1)).findByTenantId("t");
  }

  @Test
  void deleteExportedAssetsUsesExactManifestKeyList() {
    service.deleteExportedAssets("t", 1, List.of("logo.png", "manifest.json"));

    verify(s3Client)
        .deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals("t/1/logo.png")));
    verify(s3Client)
        .deleteObject(argThat((DeleteObjectRequest r) -> r.key().equals("t/1/manifest.json")));
  }

  private static Version version(VersionLifecycleState state) {
    Version version = new Version();
    version.setId(7L);
    version.setTenantId("t");
    version.setVersionNumber(1);
    version.setVersionState(state);
    return version;
  }

  private static GameAsset gameAsset(String fileName, String data) {
    GameAsset asset = new GameAsset();
    asset.setTenantId("t");
    asset.setFileName(fileName);
    asset.setContentType("image/png");
    asset.setData(data.getBytes(StandardCharsets.UTF_8));
    return asset;
  }
}
