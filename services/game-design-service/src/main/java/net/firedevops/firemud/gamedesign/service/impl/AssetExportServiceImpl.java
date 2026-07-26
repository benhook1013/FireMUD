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
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
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
  private static final String REPAIR_VERSION_SCOPE_UNAVAILABLE = "REPAIR_VERSION_SCOPE_UNAVAILABLE";

  private final GameAssetRepository repository;
  private final VersionRepository versionRepository;
  private final Map<VersionScope, FrozenAssetSnapshot> frozenSnapshots = new ConcurrentHashMap<>();

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "S3Client is thread-safe")
  private final S3Client s3Client;

  private final AssetStoreProperties properties;
  private final ObjectMapper objectMapper;

  public AssetExportServiceImpl(
      GameAssetRepository repository,
      S3Client s3Client,
      AssetStoreProperties properties,
      ObjectMapper objectMapper,
      VersionRepository versionRepository) {
    this.repository = repository;
    this.versionRepository = versionRepository;
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
    VersionResolution resolution = resolveVersionScope(tenantId, version);
    FrozenAssetSnapshot snapshot = resolveSnapshot(resolution.scope(), resolution.versionState());
    String prefix = tenantId + "/" + version + "/";
    Map<String, String> manifest = new LinkedHashMap<>();
    ArrayList<String> requiredManifestAssetKeys = new ArrayList<>();
    for (FrozenAsset asset : snapshot.assets()) {
      String key = prefix + asset.fileName();
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(key)
              .contentType(asset.contentType())
              .build(),
          RequestBody.fromBytes(asset.data()));
      String url = properties.getEndpoint() + "/" + properties.getBucket() + "/" + key;
      manifest.put(asset.fileName(), url);
      requiredManifestAssetKeys.add(asset.fileName());
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
      requiredManifestAssetKeys.add("manifest.json");
      return new ExportedAssetManifest(
          sha256(manifestJson), List.copyOf(requiredManifestAssetKeys));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to write manifest", e);
    }
  }

  private VersionResolution resolveVersionScope(String tenantId, int versionNumber) {
    Version version =
        versionRepository
            .findByTenantIdAndVersionNumber(tenantId, versionNumber)
            .orElseThrow(() -> new IllegalArgumentException(REPAIR_VERSION_SCOPE_UNAVAILABLE));
    return new VersionResolution(
        new VersionScope(tenantId, version.getId(), versionNumber), version.getVersionState());
  }

  private FrozenAssetSnapshot resolveSnapshot(
      VersionScope scope, VersionLifecycleState versionState) {
    FrozenAssetSnapshot existing = frozenSnapshots.get(scope);
    if (existing != null) {
      return existing;
    }
    if (versionState == VersionLifecycleState.PUBLISHED
        || versionState == VersionLifecycleState.ACTIVE) {
      throw new IllegalStateException(REPAIR_VERSION_SCOPE_UNAVAILABLE);
    }
    return frozenSnapshots.computeIfAbsent(
        scope, ignored -> freeze(repository.findByTenantId(scope.tenantId())));
  }

  private FrozenAssetSnapshot freeze(List<GameAsset> assets) {
    Map<String, FrozenAsset> frozenByUsageKey = new LinkedHashMap<>();
    for (GameAsset asset : assets) {
      byte[] data = asset.getData();
      FrozenAsset frozen =
          new FrozenAsset(
              asset.getId(), asset.getFileName(), asset.getContentType(), data, sha256(data));
      if (frozenByUsageKey.putIfAbsent(asset.getFileName(), frozen) != null) {
        throw new IllegalStateException("ASSET_USAGE_KEY_COLLISION");
      }
    }
    return new FrozenAssetSnapshot(List.copyOf(frozenByUsageKey.values()));
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

  private record VersionResolution(VersionScope scope, VersionLifecycleState versionState) {}

  private record VersionScope(String tenantId, Long versionId, int versionNumber) {}

  private record FrozenAssetSnapshot(List<FrozenAsset> assets) {
    private FrozenAssetSnapshot {
      assets = List.copyOf(assets);
    }
  }

  private record FrozenAsset(
      Long sourceAssetId, String fileName, String contentType, byte[] data, String contentHash) {
    private FrozenAsset {
      data = data == null ? null : data.clone();
    }

    @Override
    public byte[] data() {
      return data == null ? null : data.clone();
    }
  }
}
