package net.firedevops.firemud.gamedesign.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class AssetExportServiceImpl implements AssetExportService {
  private final GameAssetRepository repository;

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "S3Client is thread-safe")
  private final S3Client s3Client;

  private final AssetStoreProperties properties;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public AssetExportServiceImpl(
      GameAssetRepository repository, S3Client s3Client, AssetStoreProperties properties) {
    this.repository = repository;
    this.s3Client = s3Client;
    this.properties = copyProperties(properties);
  }

  private static AssetStoreProperties copyProperties(AssetStoreProperties source) {
    AssetStoreProperties copy = new AssetStoreProperties();
    copy.setEndpoint(source.getEndpoint());
    copy.setBucket(source.getBucket());
    copy.setRegion(source.getRegion());
    copy.setAccessKey(source.getAccessKey());
    copy.setSecretKey(source.getSecretKey());
    return copy;
  }

  @Override
  @Timed("gamedesign.asset.export")
  public void exportAssets(String tenantId, int version) {
    String prefix = tenantId + "/" + version + "/";
    List<GameAsset> assets = repository.findByTenantId(tenantId);
    Map<String, String> manifest = new HashMap<>();
    for (GameAsset asset : assets) {
      String key = prefix + asset.getFileName();
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(key)
              .contentType(asset.getContentType())
              .build(),
          RequestBody.fromBytes(asset.getData()));
      String url = properties.getEndpoint() + "/" + properties.getBucket() + "/" + key;
      manifest.put(asset.getFileName(), url);
    }
    try {
      String manifestJson = objectMapper.writeValueAsString(manifest);
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(prefix + "manifest.json")
              .contentType("application/json")
              .build(),
          RequestBody.fromString(manifestJson, StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to write manifest", e);
    }
  }

  @Override
  @Timed("gamedesign.asset.delete")
  public void deleteExportedAssets(String tenantId, int version) {
    String prefix = tenantId + "/" + version + "/";
    List<GameAsset> assets = repository.findByTenantId(tenantId);
    for (GameAsset asset : assets) {
      s3Client.deleteObject(
          DeleteObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(prefix + asset.getFileName())
              .build());
    }
    s3Client.deleteObject(
        DeleteObjectRequest.builder()
            .bucket(properties.getBucket())
            .key(prefix + "manifest.json")
            .build());
  }
}
