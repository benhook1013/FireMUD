package net.firedevops.firemud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.config.AssetStoreProperties;
import net.firedevops.firemud.entity.GameAsset;
import net.firedevops.firemud.repository.GameAssetRepository;
import net.firedevops.firemud.service.AssetExportService;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
public class AssetExportServiceImpl implements AssetExportService {
  private final GameAssetRepository repository;
  private final S3Client s3Client;
  private final AssetStoreProperties properties;
  private final ObjectMapper objectMapper = new ObjectMapper();

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
