package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventoryReadService;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

@Service
public class AssetExportServiceImpl implements AssetExportService {
  private static final String MANIFEST_OBJECT_KEY = "manifest.json";

  private final VersionAssetPublicationRepository publicationRepository;

  private final S3Client s3Client;

  private final AssetStoreProperties properties;
  private final ObjectMapper objectMapper;
  private final SelectedDraftAssetInventoryReadService selectedInventoryReader;
  private final VersionAssetExportCandidateService candidateService;

  public AssetExportServiceImpl(
      VersionAssetPublicationRepository publicationRepository,
      S3Client s3Client,
      AssetStoreProperties properties,
      ObjectMapper objectMapper) {
    this(publicationRepository, s3Client, properties, objectMapper, null, null);
  }

  @Autowired
  public AssetExportServiceImpl(
      VersionAssetPublicationRepository publicationRepository,
      S3Client s3Client,
      AssetStoreProperties properties,
      ObjectMapper objectMapper,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      VersionAssetExportCandidateService candidateService) {
    this(
        publicationRepository,
        s3Client,
        properties,
        objectMapper,
        new SelectedDraftAssetInventoryReadService(dsl, transactionManager),
        candidateService);
  }

  AssetExportServiceImpl(
      VersionAssetPublicationRepository publicationRepository,
      S3Client s3Client,
      AssetStoreProperties properties,
      ObjectMapper objectMapper,
      SelectedDraftAssetInventoryReadService selectedInventoryReader,
      VersionAssetExportCandidateService candidateService) {
    this.publicationRepository = publicationRepository;
    this.s3Client = s3Client;
    this.properties = copyProperties(properties);
    this.objectMapper = objectMapper;
    this.selectedInventoryReader = selectedInventoryReader;
    this.candidateService = candidateService;
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
  @Timed("gamedesign.asset.export.selected")
  public SelectedExportResult exportSelectedAssets(PublicationDigestRequestBinding request) {
    if (selectedInventoryReader == null || candidateService == null) {
      throw new IllegalStateException("SELECTED_ASSET_EXPORT_ADAPTER_UNAVAILABLE");
    }
    if (properties.getBucket() == null || properties.getBucket().isBlank()) {
      throw new IllegalStateException("ASSET_STORE_BUCKET_INVALID");
    }
    SelectedDraftAssetInventory inventory;
    try {
      inventory = selectedInventoryReader.read(request);
    } catch (RuntimeException failure) {
      throw inventoryReadFailure(failure);
    }
    if (!request.requestDigest().equals(inventory.request().requestDigest())
        || !Arrays.equals(request.canonicalPreimage(), inventory.request().canonicalPreimage())) {
      throw new IllegalStateException("SELECTED_ASSET_INVENTORY_REQUEST_MISMATCH");
    }

    List<PreparedSelectedAsset> assets = prepareSelectedAssets(inventory);
    byte[] manifestBytes = serializeSelectedManifest(inventory, assets);
    byte[] inventoryBytes = inventory.canonicalBytes();
    SelectedInventoryIdentity identity = selectedInventoryIdentity(inventoryBytes);
    String manifestHash = "sha256:" + sha256(manifestBytes);
    List<PublishedArtifactDigest> artifactDigests =
        assets.stream().map(PreparedSelectedAsset::digest).toList();
    ExportedAssetManifest manifest =
        new ExportedAssetManifest(
            manifestHash,
            1,
            artifactDigests.stream().map(PublishedArtifactDigest::usageKey).toList(),
            artifactDigests);
    VersionAssetExportCandidateService.CandidateBinding expectedBinding =
        new VersionAssetExportCandidateService.CandidateBinding(
            request.requestDigest(),
            identity.operationDigest(),
            identity.selectedCommitDigest(),
            SelectedDraftAssetInventory.SCHEMA,
            inventory.digest(),
            manifest,
            request.canonicalPreimage(),
            inventoryBytes,
            manifestBytes);

    // The owner candidate commits before any object write. Both candidate and complete selected
    // inventory are then independently read back before the immutable store is touched.
    try {
      candidateService.recordSelectedCandidate(inventory, manifest, manifestBytes);
    } catch (RuntimeException failure) {
      throw candidateBoundaryFailure("record", failure);
    }
    VersionAssetExportCandidateService.CandidateBinding storedCandidate;
    try {
      storedCandidate = candidateService.readSelectedCandidate(request);
    } catch (RuntimeException failure) {
      throw candidateBoundaryFailure("read", failure);
    }
    if (!expectedBinding.equals(storedCandidate)) {
      throw new IllegalStateException("SELECTED_ASSET_EXPORT_CANDIDATE_READBACK_MISMATCH");
    }
    SelectedDraftAssetInventory independentlyRead;
    try {
      independentlyRead = selectedInventoryReader.read(request);
    } catch (RuntimeException failure) {
      throw inventoryReadFailure(failure);
    }
    if (!inventory.digest().equals(independentlyRead.digest())
        || !Arrays.equals(inventoryBytes, independentlyRead.canonicalBytes())) {
      throw new IllegalStateException("SELECTED_ASSET_INVENTORY_READBACK_MISMATCH");
    }

    for (PreparedSelectedAsset asset : assets) {
      writeAndVerifyImmutableObject(
          asset.digest().immutableObjectKey(), asset.bytes(), asset.digest().contentType());
    }
    String manifestObjectKey = "manifests/sha256/" + manifestHash.substring("sha256:".length());
    writeAndVerifyImmutableObject(manifestObjectKey, manifestBytes, "application/json");
    return new SelectedExportResult(manifest, storedCandidate);
  }

  @Override
  public void requireSelectedCandidateForFinalization(
      PublicationDigestRequestBinding request, SelectedExportResult exportResult) {
    Objects.requireNonNull(exportResult, "exportResult");
    if (!exportResult.manifest().equals(exportResult.candidateBinding().manifest())) {
      throw new IllegalStateException("SELECTED_ASSET_EXPORT_CANDIDATE_CONFLICT");
    }
    if (candidateService == null) {
      throw new IllegalStateException("SELECTED_ASSET_EXPORT_ADAPTER_UNAVAILABLE");
    }
    candidateService.requireSelectedCandidateForFinalization(
        request, exportResult.candidateBinding());
  }

  private RuntimeException inventoryReadFailure(RuntimeException failure) {
    if (isInfrastructureUnavailable(failure)) {
      return new SelectedExportOutcomePendingException(
          "selected asset inventory read outcome is unavailable", failure);
    }
    return failure;
  }

  private RuntimeException candidateBoundaryFailure(String operation, RuntimeException failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if ("SELECTED_ASSET_EXPORT_CANDIDATE_CONFLICT".equals(current.getMessage())
          || "SELECTED_ASSET_EXPORT_CANDIDATE_NOT_FOUND".equals(current.getMessage())
          || "SELECTED_ASSET_EXPORT_CANDIDATE_WRITE_READBACK_MISMATCH"
              .equals(current.getMessage())) {
        return failure;
      }
    }
    if (isInfrastructureUnavailable(failure)) {
      return new SelectedExportOutcomePendingException(
          "selected asset candidate " + operation + " outcome is unresolved", failure);
    }
    return failure;
  }

  private static boolean isInfrastructureUnavailable(Throwable failure) {
    if (Status.fromThrowable(failure).getCode() == Status.Code.UNAVAILABLE) {
      return true;
    }
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof DataAccessException
          || current instanceof org.jooq.exception.DataAccessException
          || current instanceof SdkClientException) {
        return true;
      }
      if (current instanceof software.amazon.awssdk.awscore.exception.AwsServiceException service) {
        int status = service.statusCode();
        if (status == 401
            || status == 403
            || status == 404
            || status == 408
            || status == 429
            || status >= 500) {
          return true;
        }
      }
    }
    return false;
  }

  private SelectedInventoryIdentity selectedInventoryIdentity(byte[] inventoryBytes) {
    try {
      var inventory = objectMapper.readTree(inventoryBytes);
      String operationBase64 = inventory.path("operationBase64").textValue();
      String commitDigest = inventory.path("selectedCommitDigest").textValue();
      if (!SelectedDraftAssetInventory.SCHEMA.equals(inventory.path("schema").textValue())
          || operationBase64 == null
          || commitDigest == null
          || !commitDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalStateException("SELECTED_ASSET_INVENTORY_BINDING_INVALID");
      }
      byte[] operationBytes = java.util.Base64.getDecoder().decode(operationBase64);
      return new SelectedInventoryIdentity("sha256:" + sha256(operationBytes), commitDigest);
    } catch (RuntimeException invalid) {
      if ("SELECTED_ASSET_INVENTORY_BINDING_INVALID".equals(invalid.getMessage())) {
        throw invalid;
      }
      throw new IllegalStateException("SELECTED_ASSET_INVENTORY_BINDING_INVALID", invalid);
    }
  }

  private List<PreparedSelectedAsset> prepareSelectedAssets(SelectedDraftAssetInventory inventory) {
    ArrayList<PreparedSelectedAsset> result = new ArrayList<>();
    for (SelectedDraftAssetInventory.Asset asset : inventory.assets()) {
      byte[] bytes = asset.contentBytes();
      String actualDigest = "sha256:" + sha256(bytes);
      if (!actualDigest.equals(asset.contentDigest())) {
        throw new IllegalStateException("SELECTED_ASSET_SOURCE_BYTES_MISMATCH");
      }
      PublishedArtifactDigest proof =
          new PublishedArtifactDigest(
              asset.usageKey(),
              "BINARY",
              "artifacts/sha256/" + actualDigest.substring("sha256:".length()),
              actualDigest,
              asset.contentType(),
              1);
      result.add(new PreparedSelectedAsset(asset, bytes, proof));
    }
    return List.copyOf(result);
  }

  private byte[] serializeSelectedManifest(
      SelectedDraftAssetInventory inventory, List<PreparedSelectedAsset> assets) {
    List<Map<String, Object>> entries =
        assets.stream()
            .map(
                asset -> {
                  var source = asset.source();
                  var proof = asset.digest();
                  return Map.<String, Object>ofEntries(
                      Map.entry("usageKey", source.usageKey()),
                      Map.entry("family", source.family().name()),
                      Map.entry("role", source.role()),
                      Map.entry("requiredness", source.requiredness()),
                      Map.entry("artifactKind", proof.artifactKind()),
                      Map.entry("objectKey", proof.immutableObjectKey()),
                      Map.entry("contentDigest", proof.contentDigest()),
                      Map.entry("contentType", proof.contentType()),
                      Map.entry("artifactSchemaVersion", proof.artifactSchemaVersion()),
                      Map.entry("byteSize", asset.bytes().length));
                })
            .toList();
    Map<String, Object> manifest =
        Map.ofEntries(
            Map.entry("schema", "game-design-selected-asset-manifest/v1"),
            Map.entry("manifestSchemaVersion", 1),
            Map.entry("selectedInventorySchema", SelectedDraftAssetInventory.SCHEMA),
            Map.entry("selectedInventoryDigest", inventory.digest()),
            Map.entry("artifacts", entries));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(objectMapper.writeValueAsString(manifest));
    } catch (Exception invalid) {
      throw new IllegalStateException("SELECTED_ASSET_MANIFEST_SERIALIZATION_FAILED", invalid);
    }
  }

  private void writeAndVerifyImmutableObject(String key, byte[] bytes, String contentType) {
    RuntimeException putFailure = null;
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(key)
              .contentType(contentType)
              .ifNoneMatch("*")
              .build(),
          RequestBody.fromBytes(bytes));
    } catch (RuntimeException failure) {
      // A conditional collision or lost response is resolved only by an exact subsequent GET.
      putFailure = failure;
    }

    final ResponseBytes<GetObjectResponse> observed;
    try {
      observed =
          s3Client.getObjectAsBytes(
              GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
    } catch (RuntimeException unavailable) {
      if (unavailable
              instanceof software.amazon.awssdk.awscore.exception.AwsServiceException service
          && service.statusCode() >= 400
          && service.statusCode() < 500
          && !isInfrastructureUnavailable(service)) {
        throw new IllegalStateException("SELECTED_ASSET_OBJECT_READBACK_FAILED", unavailable);
      }
      if (!isInfrastructureUnavailable(unavailable)) {
        throw new IllegalStateException("SELECTED_ASSET_OBJECT_READBACK_FAILED", unavailable);
      }
      SelectedExportOutcomePendingException pending =
          new SelectedExportOutcomePendingException(
              "selected asset object readback is unresolved", unavailable);
      if (putFailure != null) {
        pending.addSuppressed(putFailure);
      }
      throw pending;
    }
    byte[] actual = observed.asByteArray();
    GetObjectResponse response = observed.response();
    if (actual.length != bytes.length
        || !Arrays.equals(actual, bytes)
        || !Objects.equals(response.contentLength(), (long) bytes.length)
        || !Objects.equals(response.contentType(), contentType)
        || !("sha256:" + sha256(actual)).equals("sha256:" + sha256(bytes))) {
      throw new IllegalStateException(
          putFailure == null
              ? "SELECTED_ASSET_OBJECT_READBACK_MISMATCH"
              : "SELECTED_ASSET_IMMUTABLE_OBJECT_CONFLICT",
          putFailure);
    }
  }

  private record PreparedSelectedAsset(
      SelectedDraftAssetInventory.Asset source, byte[] bytes, PublishedArtifactDigest digest) {
    private PreparedSelectedAsset {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record SelectedInventoryIdentity(String operationDigest, String selectedCommitDigest) {}

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
