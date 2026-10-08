package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators remain internal service dependencies")
public class AssetExportServiceImpl implements AssetExportService {
  private static final String MANIFEST_OBJECT_KEY = "manifest.json";

  private final VersionAssetPublicationRepository publicationRepository;

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "S3Client is thread-safe")
  private final S3Client s3Client;

  private final AssetStoreProperties properties;
  private final ObjectMapper objectMapper;

  public AssetExportServiceImpl(
      VersionAssetPublicationRepository publicationRepository,
      S3Client s3Client,
      AssetStoreProperties properties,
      ObjectMapper objectMapper) {
    this.publicationRepository = publicationRepository;
    this.s3Client = s3Client;
    this.properties = copyProperties(properties);
    this.objectMapper = objectMapper;
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
  public ExportedAssetManifest exportAssets(String tenantId, int version) {
    // The owner read verifies the complete retained mapping projection and actual source bytes
    // before any object-store write. Export never creates a selection or falls back to uploads.
    var snapshot = publicationRepository.readFrozenSnapshot(tenantId, version);
    if (!tenantId.equals(snapshot.tenantId()) || version != snapshot.versionNumber()) {
      throw new IllegalStateException("ASSET_EXPORT_SNAPSHOT_SCOPE_MISMATCH");
    }
    String prefix = tenantId + "/" + version + "/";
    Map<String, String> manifest = new LinkedHashMap<>();
    ArrayList<String> requiredManifestAssetKeys = new ArrayList<>();
    for (var asset : snapshot.items()) {
      String key = prefix + asset.usageKey();
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(key)
              .contentType(asset.contentType())
              .build(),
          RequestBody.fromBytes(asset.bytes()));
      String url = properties.getEndpoint() + "/" + properties.getBucket() + "/" + key;
      manifest.put(asset.usageKey(), url);
      requiredManifestAssetKeys.add(asset.usageKey());
    }
    try {
      String manifestJson = objectMapper.writeValueAsString(manifest);
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(prefix + MANIFEST_OBJECT_KEY)
              .contentType("application/json")
              .build(),
          RequestBody.fromString(manifestJson, StandardCharsets.UTF_8));
      requiredManifestAssetKeys.add(MANIFEST_OBJECT_KEY);
      return new ExportedAssetManifest(
          sha256(manifestJson), List.copyOf(requiredManifestAssetKeys));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to write manifest", e);
    }
  }

  @Override
  @Timed("gamedesign.asset.delete")
  public void deleteExportedAssets(String tenantId, int version, List<String> manifestAssetKeys) {
    String prefix = tenantId + "/" + version + "/";
    for (String assetKey : new LinkedHashSet<>(manifestAssetKeys)) {
      s3Client.deleteObject(
          DeleteObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(prefix + assetKey)
              .build());
    }
  }

  private String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private String sha256(byte[] value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value);
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
