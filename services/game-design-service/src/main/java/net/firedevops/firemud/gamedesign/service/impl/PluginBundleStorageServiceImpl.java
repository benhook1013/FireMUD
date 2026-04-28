package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.service.ParsedPluginBundle;
import net.firedevops.firemud.gamedesign.service.PluginAssetRef;
import net.firedevops.firemud.gamedesign.service.PluginBundleStorageService;
import net.firedevops.firemud.gamedesign.service.PluginDistributionManifest;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators remain internal service dependencies")
public class PluginBundleStorageServiceImpl implements PluginBundleStorageService {
  private static final String BUNDLE_ROOT = "plugin-bundles";

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "S3Client is thread-safe")
  private final S3Client s3Client;

  private final AssetStoreProperties properties;
  private final ObjectMapper objectMapper;

  public PluginBundleStorageServiceImpl(
      S3Client s3Client, AssetStoreProperties properties, ObjectMapper objectMapper) {
    this.s3Client = s3Client;
    this.properties = copyProperties(properties);
    this.objectMapper = objectMapper;
  }

  @Override
  public void storePluginBundle(
      String tenantId, String pluginId, String pluginVersionId, byte[] bundleBytes) {
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(properties.getBucket())
            .key(bundleObjectKey(tenantId, pluginId, pluginVersionId))
            .contentType("application/zip")
            .build(),
        RequestBody.fromBytes(bundleBytes));
  }

  @Override
  public byte[] loadPluginBundle(String tenantId, String pluginId, String pluginVersionId) {
    try {
      ResponseBytes<GetObjectResponse> response =
          s3Client.getObjectAsBytes(
              GetObjectRequest.builder()
                  .bucket(properties.getBucket())
                  .key(bundleObjectKey(tenantId, pluginId, pluginVersionId))
                  .build());
      return response.asByteArray();
    } catch (NoSuchKeyException ex) {
      throw new IllegalArgumentException("NOT_FOUND: uploaded plugin bundle not found");
    }
  }

  @Override
  public PluginDistributionManifest exportPluginAssets(
      String tenantId, ParsedPluginBundle bundle, String signerKeyId, String bundleDigest) {
    if (bundle.assetRefs().isEmpty()) {
      return new PluginDistributionManifest("", "");
    }

    String prefix = bundlePrefix(tenantId, bundle.pluginId(), bundle.pluginVersionId());
    List<Map<String, Object>> assets =
        bundle.assetRefs().stream().map(asset -> writeAsset(prefix, asset, bundle)).toList();

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("tenantId", tenantId);
    manifest.put("pluginId", bundle.pluginId());
    manifest.put("pluginVersionId", bundle.pluginVersionId());
    manifest.put("baseVersionId", bundle.baseVersionId());
    manifest.put("abilitySchemaDigest", bundle.abilitySchemaDigest());
    manifest.put("manifestSchemaVersion", bundle.manifestSchemaVersion());
    manifest.put("bundleDigest", bundleDigest);
    manifest.put("signerKeyId", signerKeyId);
    manifest.put("assets", assets);
    String manifestJson = objectMapper.writeValueAsString(manifest);
    String manifestHash = sha256(manifestJson.getBytes(StandardCharsets.UTF_8));
    String manifestPath = prefix + "/plugin-distribution-manifest.json";
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(properties.getBucket())
            .key(manifestPath)
            .contentType("application/json")
            .build(),
        RequestBody.fromString(manifestJson, StandardCharsets.UTF_8));
    return new PluginDistributionManifest(manifestHash, manifestPath);
  }

  private Map<String, Object> writeAsset(
      String prefix, PluginAssetRef asset, ParsedPluginBundle bundle) {
    byte[] bytes =
        bundle.files().get(asset.path()) == null ? new byte[0] : bundle.files().get(asset.path());
    if (bytes.length == 0) {
      throw new IllegalArgumentException(
          "VALIDATION_FAILED_DESIGN: plugin asset bytes missing for " + asset.assetId());
    }
    String objectKey = prefix + "/assets/" + asset.path();
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(properties.getBucket())
            .key(objectKey)
            .contentType(asset.contentType())
            .build(),
        RequestBody.fromBytes(bytes));
    Map<String, Object> manifestAsset = new LinkedHashMap<>();
    manifestAsset.put("assetId", asset.assetId());
    manifestAsset.put("path", asset.path());
    manifestAsset.put("contentHash", asset.contentHash());
    manifestAsset.put("contentType", asset.contentType());
    Long sizeBytes = asset.sizeBytes();
    manifestAsset.put("sizeBytes", sizeBytes == null ? Long.valueOf(bytes.length) : sizeBytes);
    manifestAsset.put("storagePath", objectKey);
    manifestAsset.put(
        "url", properties.getEndpoint() + "/" + properties.getBucket() + "/" + objectKey);
    return manifestAsset;
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

  private String bundleObjectKey(String tenantId, String pluginId, String pluginVersionId) {
    return bundlePrefix(tenantId, pluginId, pluginVersionId) + "/bundle.zip";
  }

  private String bundlePrefix(String tenantId, String pluginId, String pluginVersionId) {
    return BUNDLE_ROOT + "/" + tenantId + "/" + pluginId + "/" + pluginVersionId;
  }

  private String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte current : hash) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
