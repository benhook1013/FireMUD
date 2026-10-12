package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.service.AssetExportOutcomePendingException;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.FrozenAssetExportCandidate;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionAssetPublicationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Spring rejects missing collaborators before publication; this bean has no finalizer")
public class AssetExportServiceImpl implements AssetExportService {
  private static final Logger logger = LoggingUtil.getLogger(AssetExportServiceImpl.class);
  private static final String ASSET_USAGE_KEY_COLLISION = "ASSET_USAGE_KEY_COLLISION";
  private static final String SNAPSHOT_UNAVAILABLE = "VERSION_ASSET_SNAPSHOT_UNAVAILABLE";
  private static final String SNAPSHOT_MISMATCH = "VERSION_ASSET_SNAPSHOT_MISMATCH";
  private static final String SNAPSHOT_INVALID = "VERSION_ASSET_SNAPSHOT_INVALID";
  private static final String SOURCE_BYTES_MISMATCH = "ASSET_SOURCE_BYTES_MISMATCH";
  private static final String PUBLIC_BASE_URL_INVALID = "ASSET_STORE_PUBLIC_BASE_URL_INVALID";
  private static final String IMMUTABLE_OBJECT_CONFLICT = "IMMUTABLE_OBJECT_CONFLICT";
  private static final String MANIFEST_CONTENT_TYPE = "application/json";
  private static final String MANIFEST_OBJECT_ROOT = "manifests/sha256/";
  private static final String ARTIFACT_OBJECT_ROOT = "artifacts/sha256/";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private static final Comparator<AssetSelection> USAGE_KEY_ORDER =
      (left, right) ->
          PublishedArtifactDigest.compareUsageKeysUtf8(left.usageKey(), right.usageKey());

  private final VersionAssetPublicationService publicationService;

  private final VersionAssetExportCandidateService candidateService;

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "S3Client is thread-safe")
  private final S3Client s3Client;

  private final AssetStoreProperties properties;

  public AssetExportServiceImpl(
      VersionAssetPublicationService publicationService,
      VersionAssetExportCandidateService candidateService,
      S3Client s3Client,
      AssetStoreProperties properties) {
    this.publicationService = Objects.requireNonNull(publicationService);
    this.candidateService = Objects.requireNonNull(candidateService);
    this.s3Client = Objects.requireNonNull(s3Client);
    this.properties = copyProperties(Objects.requireNonNull(properties));
  }

  private static AssetStoreProperties copyProperties(AssetStoreProperties source) {
    AssetStoreProperties copy = new AssetStoreProperties();
    copy.setEndpoint(source.getEndpoint());
    copy.setPublicBaseUrl(source.getPublicBaseUrl());
    copy.setBucket(source.getBucket());
    copy.setRegion(source.getRegion());
    copy.setAccessKey(source.getAccessKey());
    copy.setSecretKey(source.getSecretKey());
    return copy;
  }

  @Override
  @Timed("gamedesign.asset.export")
  public ExportedAssetManifest exportAssets(String tenantId, int versionNumber) {
    requireExportScope(tenantId, versionNumber);
    ExportSnapshot frozen = publicationService.freezeOrReadSnapshot(tenantId, versionNumber);
    if (frozen == null) {
      throw new IllegalStateException(SNAPSHOT_UNAVAILABLE);
    }
    ExportSnapshot independentlyRead =
        publicationService.readFrozenSnapshot(tenantId, versionNumber);
    if (independentlyRead == null) {
      throw new IllegalStateException(SNAPSHOT_UNAVAILABLE);
    }
    requireSameSnapshot(frozen, independentlyRead);
    return exportVerifiedSnapshot(tenantId, versionNumber, independentlyRead, null);
  }

  @Override
  @Timed("gamedesign.asset.repair")
  public ExportedAssetManifest repairPublishedAssets(
      String tenantId, int versionNumber, PublishedReleaseBundleDto publishedBundle) {
    requireExportScope(tenantId, versionNumber);
    if (publishedBundle == null) {
      throw new IllegalStateException(PublishedReleaseBundleContract.REPAIR_ATTESTATION_MISMATCH);
    }
    ExportSnapshot frozen = publicationService.readFrozenSnapshot(tenantId, versionNumber);
    if (frozen == null) {
      throw new IllegalStateException(SNAPSHOT_UNAVAILABLE);
    }
    ExportSnapshot independentlyRead =
        publicationService.readFrozenSnapshot(tenantId, versionNumber);
    if (independentlyRead == null) {
      throw new IllegalStateException(SNAPSHOT_UNAVAILABLE);
    }
    requireSameSnapshot(frozen, independentlyRead);
    return exportVerifiedSnapshot(tenantId, versionNumber, independentlyRead, publishedBundle);
  }

  private void requireExportScope(String tenantId, int versionNumber) {
    if (tenantId == null || tenantId.isBlank() || versionNumber <= 0) {
      throw new IllegalArgumentException("VERSION_ASSET_SNAPSHOT_INVALID: invalid requested scope");
    }
    requireObjectStoreBucket();
  }

  private ExportedAssetManifest exportVerifiedSnapshot(
      String tenantId,
      int versionNumber,
      ExportSnapshot snapshot,
      PublishedReleaseBundleDto publishedBundle) {
    List<AssetSelection> selections = validateAndOrderSnapshot(snapshot, tenantId, versionNumber);

    FrozenAssetExportCandidate existing =
        readCandidate(tenantId, versionNumber, publishedBundle == null);
    String publicBaseUrl =
        requireApprovedPublicBaseUrl(
            existing == null ? properties.getPublicBaseUrl() : existing.publicBaseUrl());
    try (PrivateStaging staging = new PrivateStaging()) {
      List<PreparedArtifact> artifacts = new ArrayList<>(selections.size());
      for (AssetSelection selection : selections) {
        artifacts.add(
            stageArtifact(
                tenantId,
                versionNumber,
                selection,
                publicBaseUrl,
                snapshot.canonicalVersionId(),
                staging));
      }
      byte[] manifestBytes = serializeManifest(artifacts);
      String manifestHash = "sha256:" + sha256Hex(manifestBytes);
      String manifestObjectKey = MANIFEST_OBJECT_ROOT + manifestHash.substring("sha256:".length());
      List<PublishedArtifactDigest> artifactDigests =
          artifacts.stream().map(PreparedArtifact::digest).toList();
      ExportedAssetManifest manifest =
          new ExportedAssetManifest(
              manifestHash,
              1,
              artifactDigests.stream().map(PublishedArtifactDigest::usageKey).toList(),
              artifactDigests);
      FrozenAssetExportCandidate candidate =
          new FrozenAssetExportCandidate(manifest, publicBaseUrl);
      if (existing != null) {
        requireCandidateMatch("read", candidate, existing);
      }
      if (publishedBundle == null) {
        requireDurableCandidate(tenantId, versionNumber, candidate);
      } else {
        PublishedReleaseBundleContract.requireExactRepairMatch(publishedBundle, manifest);
      }
      // The complete source selection is verified in private staging and candidate evidence is
      // independently committed before any shared content-addressed object is written.
      for (PreparedArtifact artifact : artifacts) {
        writeAndVerifyImmutableObject(
            artifact.digest().immutableObjectKey(),
            RequestBody.fromFile(artifact.path()),
            artifact.path(),
            artifact.byteSize(),
            artifact.digest().contentType());
      }
      writeAndVerifyImmutableObject(
          manifestObjectKey,
          RequestBody.fromBytes(manifestBytes),
          manifestBytes,
          MANIFEST_CONTENT_TYPE);
      return manifest;
    } catch (IOException exception) {
      throw new IllegalStateException("ASSET_PRIVATE_STAGING_UNAVAILABLE", exception);
    }
  }

  private FrozenAssetExportCandidate readCandidate(
      String tenantId, int versionNumber, boolean allowAbsent) {
    try {
      FrozenAssetExportCandidate existing =
          candidateService.readExportCandidate(tenantId, versionNumber);
      if (existing == null) {
        throw new AssetExportOutcomePendingException("export candidate readback is missing");
      }
      return existing;
    } catch (RuntimeException exception) {
      if (allowAbsent && "ASSET_EXPORT_CANDIDATE_NOT_FOUND".equals(exception.getMessage())) {
        return null;
      }
      throw candidateFailure("read", exception);
    }
  }

  private void requireDurableCandidate(
      String tenantId, int versionNumber, FrozenAssetExportCandidate candidate) {
    FrozenAssetExportCandidate recorded;
    try {
      recorded = candidateService.recordExportCandidate(tenantId, versionNumber, candidate);
    } catch (RuntimeException exception) {
      throw candidateFailure("record", exception);
    }
    requireCandidateMatch("record", candidate, recorded);
    requireCandidateMatch("read", candidate, readCandidate(tenantId, versionNumber, false));
  }

  private RuntimeException candidateFailure(String operation, RuntimeException cause) {
    if (isCandidateConflict(cause)) {
      return cause;
    }
    return new AssetExportOutcomePendingException(
        "export candidate " + operation + " outcome is unresolved", cause);
  }

  private void requireCandidateMatch(
      String operation, FrozenAssetExportCandidate expected, FrozenAssetExportCandidate actual) {
    if (actual == null) {
      throw new AssetExportOutcomePendingException(
          "export candidate " + operation + " readback is missing");
    }
    if (!expected.equals(actual)) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_CONFLICT");
    }
  }

  private static boolean isCandidateConflict(RuntimeException exception) {
    for (Throwable current = exception; current != null; current = current.getCause()) {
      if ("ASSET_EXPORT_CANDIDATE_CONFLICT".equals(current.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private List<AssetSelection> validateAndOrderSnapshot(
      ExportSnapshot snapshot, String requestedTenantId, int requestedVersionNumber) {
    if (!Objects.equals(requestedTenantId, snapshot.tenantId())
        || requestedVersionNumber != snapshot.versionNumber()
        || snapshot.versionId() <= 0
        || snapshot.capturedVersionStateEpoch() <= 0
        || !isCanonicalUuid(snapshot.canonicalTenantId())
        || !isCanonicalUuid(snapshot.canonicalVersionId())
        || snapshot.items() == null) {
      throw new IllegalStateException(SNAPSHOT_INVALID + ": incomplete or out-of-scope snapshot");
    }

    Set<String> usageKeys = new HashSet<>();
    Set<Long> sourceAssetIds = new HashSet<>();
    ArrayList<AssetSelection> ordered = new ArrayList<>(snapshot.items().size());
    for (AssetSelection selection : snapshot.items()) {
      if (selection == null) {
        throw new IllegalStateException(SNAPSHOT_INVALID + ": null selection");
      }
      String usageKey = selection.usageKey();
      if (usageKey == null || usageKey.isBlank()) {
        throw new IllegalStateException(ASSET_USAGE_KEY_COLLISION);
      }
      requireCanonicalText(usageKey, "usageKey");
      if ("manifest.json".equals(usageKey) || !usageKeys.add(usageKey)) {
        throw new IllegalStateException(ASSET_USAGE_KEY_COLLISION);
      }
      if (selection.assetId() <= 0
          || selection.byteSize() < 0
          || selection.byteSize() > 25L * 1024 * 1024
          || selection.contentType() == null
          || selection.contentType().isBlank()
          || !selection.contentType().equals(selection.contentType().trim())
          || selection.contentType().contains("\r")
          || selection.contentType().contains("\n")) {
        throw new IllegalStateException(SNAPSHOT_INVALID + ": incomplete source asset identity");
      }
      if (!sourceAssetIds.add(selection.assetId())) {
        throw new IllegalStateException(SNAPSHOT_INVALID + ": duplicate source asset identity");
      }
      requireCanonicalText(selection.contentType(), "contentType");
      requireCanonicalText(selection.contentDigest(), "contentDigest");
      if (!selection.contentDigest().matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalStateException(SOURCE_BYTES_MISMATCH);
      }
      ordered.add(selection);
    }
    ordered.sort(USAGE_KEY_ORDER);
    return List.copyOf(ordered);
  }

  private static boolean isCanonicalUuid(UUID value) {
    return value != null && !NIL_UUID.equals(value);
  }

  private void requireObjectStoreBucket() {
    if (properties.getBucket() == null || properties.getBucket().isBlank()) {
      throw new IllegalStateException("ASSET_STORE_BUCKET_INVALID");
    }
  }

  private static void requireCanonicalText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(SNAPSHOT_INVALID + ": " + fieldName + " is blank");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalStateException(
          SNAPSHOT_INVALID + ": " + fieldName + " has control characters");
    }
    try {
      StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException(
          SNAPSHOT_INVALID + ": " + fieldName + " is not valid UTF-8", exception);
    }
  }

  private void requireSameSnapshot(ExportSnapshot frozen, ExportSnapshot readback) {
    boolean sameScope =
        Objects.equals(frozen.tenantId(), readback.tenantId())
            && frozen.versionId() == readback.versionId()
            && frozen.versionNumber() == readback.versionNumber()
            && frozen.capturedVersionStateEpoch() == readback.capturedVersionStateEpoch()
            && Objects.equals(frozen.canonicalTenantId(), readback.canonicalTenantId())
            && Objects.equals(frozen.canonicalVersionId(), readback.canonicalVersionId());
    if (!sameScope || frozen.items() == null || readback.items() == null) {
      throw new IllegalStateException(SNAPSHOT_MISMATCH);
    }
    List<AssetSelection> frozenItems = frozen.items();
    List<AssetSelection> readbackItems = readback.items();
    if (frozenItems.size() != readbackItems.size()) {
      throw new IllegalStateException(SNAPSHOT_MISMATCH);
    }
    for (int index = 0; index < frozenItems.size(); index++) {
      AssetSelection left = frozenItems.get(index);
      AssetSelection right = readbackItems.get(index);
      if (left == null
          || right == null
          || !Objects.equals(left.usageKey(), right.usageKey())
          || left.assetId() != right.assetId()
          || !Objects.equals(left.contentType(), right.contentType())
          || !Objects.equals(left.contentDigest(), right.contentDigest())
          || left.byteSize() != right.byteSize()) {
        throw new IllegalStateException(SNAPSHOT_MISMATCH);
      }
    }
  }

  private PreparedArtifact stageArtifact(
      String tenantId,
      int versionNumber,
      AssetSelection selection,
      String publicBaseUrl,
      UUID canonicalVersionId,
      PrivateStaging staging)
      throws IOException {
    byte[] bytes = publicationService.readFrozenAssetBytes(tenantId, versionNumber, selection);
    if (bytes == null
        || bytes.length != selection.byteSize()
        || !("sha256:" + sha256Hex(bytes)).equals(selection.contentDigest())) {
      throw new IllegalStateException(SOURCE_BYTES_MISMATCH);
    }
    Path path = staging.newFile();
    Files.write(path, bytes);
    String contentHash = selection.contentDigest().substring("sha256:".length());
    String immutableObjectKey = ARTIFACT_OBJECT_ROOT + contentHash;
    PublishedArtifactDigest digest =
        new PublishedArtifactDigest(
            selection.usageKey(),
            "BINARY",
            immutableObjectKey,
            selection.contentDigest(),
            selection.contentType(),
            1);
    return new PreparedArtifact(
        digest,
        path,
        selection.byteSize(),
        canonicalVersionId,
        publicBaseUrl + "/" + immutableObjectKey);
  }

  private static byte[] serializeManifest(List<PreparedArtifact> artifacts) {
    StringBuilder manifest = new StringBuilder("{\"schemaVersion\":1,\"assets\":{");
    boolean first = true;
    for (PreparedArtifact artifact : artifacts) {
      if (!first) {
        manifest.append(',');
      }
      first = false;
      appendJsonString(manifest, artifact.digest().usageKey());
      manifest.append(":{");
      appendJsonField(manifest, "usageKey", artifact.digest().usageKey(), true);
      appendJsonField(manifest, "artifactKind", artifact.digest().artifactKind(), false);
      appendJsonField(
          manifest, "immutableObjectKey", artifact.digest().immutableObjectKey(), false);
      appendJsonField(manifest, "contentDigest", artifact.digest().contentDigest(), false);
      appendJsonField(manifest, "contentType", artifact.digest().contentType(), false);
      manifest
          .append(",\"artifactSchemaVersion\":")
          .append(artifact.digest().artifactSchemaVersion());
      appendJsonField(manifest, "producerService", "game-design-service", false);
      appendJsonField(manifest, "versionId", artifact.manifestVersionId().toString(), false);
      appendJsonField(manifest, "url", artifact.url(), false);
      manifest.append('}');
    }
    manifest.append("}}");
    return manifest.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendJsonField(
      StringBuilder json, String key, String value, boolean firstInObject) {
    if (!firstInObject) {
      json.append(',');
    }
    appendJsonString(json, key);
    json.append(':');
    appendJsonString(json, value);
  }

  private static void appendJsonString(StringBuilder json, String value) {
    json.append('"');
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      switch (codePoint) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (codePoint < 0x20) {
            json.append("\\u00");
            json.append(Character.forDigit((codePoint >>> 4) & 0xf, 16));
            json.append(Character.forDigit(codePoint & 0xf, 16));
          } else {
            json.appendCodePoint(codePoint);
          }
        }
      }
      offset += Character.charCount(codePoint);
    }
    json.append('"');
  }

  private String requireApprovedPublicBaseUrl(String configured) {
    try {
      String normalized = FrozenAssetExportCandidate.normalizePublicBaseUrl(configured);
      rejectPrivateEndpointAlias(URI.create(normalized));
      return normalized;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(PUBLIC_BASE_URL_INVALID, exception);
    }
  }

  private void rejectPrivateEndpointAlias(URI publicUri) {
    String endpoint = properties.getEndpoint();
    if (endpoint == null || endpoint.isBlank()) {
      return;
    }
    try {
      URI privateUri = new URI(endpoint);
      if (normalizeHost(publicUri.getHost()).equals(normalizeHost(privateUri.getHost()))) {
        throw new IllegalArgumentException(
            "public base URL aliases the private object-store endpoint");
      }
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("invalid private object-store endpoint", exception);
    }
  }

  private static String normalizeHost(String host) {
    if (host == null) {
      return "";
    }
    String normalized = host.toLowerCase(java.util.Locale.ROOT);
    return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  private void writeAndVerifyImmutableObject(
      String key, RequestBody body, Path path, long size, String contentType) {
    writeAndVerifyImmutableObject(key, body, size, contentType, () -> Files.newInputStream(path));
  }

  private void writeAndVerifyImmutableObject(
      String key, RequestBody body, byte[] bytes, String contentType) {
    writeAndVerifyImmutableObject(
        key, body, bytes.length, contentType, () -> new ByteArrayInputStream(bytes));
  }

  private void writeAndVerifyImmutableObject(
      String key, RequestBody body, long size, String contentType, ExpectedStream expected) {
    RuntimeException putFailure = null;
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(key)
              .contentType(contentType)
              .contentLength(size)
              .ifNoneMatch("*")
              .build(),
          body);
    } catch (RuntimeException exception) {
      putFailure = exception;
    }
    try {
      verifyReadback(key, size, expected);
    } catch (ImmutableObjectConflictException conflict) {
      if (putFailure != null) {
        conflict.addSuppressed(putFailure);
      }
      throw conflict;
    } catch (RuntimeException readFailure) {
      AssetExportOutcomePendingException pending =
          new AssetExportOutcomePendingException(
              "exact object readback is unavailable for " + key, readFailure);
      if (putFailure != null) {
        pending.addSuppressed(putFailure);
      }
      throw pending;
    }
  }

  private void verifyReadback(String key, long size, ExpectedStream expected) {
    try (ResponseInputStream<GetObjectResponse> actual =
            s3Client.getObject(
                GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
        InputStream source = expected.open()) {
      Long reportedSize = actual.response().contentLength();
      if (reportedSize == null || reportedSize.longValue() != size) {
        actual.abort();
        throw new ImmutableObjectConflictException(key);
      }
      byte[] wanted = new byte[8192];
      byte[] received = new byte[8192];
      long remaining = size;
      while (remaining > 0) {
        int count = (int) Math.min(wanted.length, remaining);
        if (source.readNBytes(wanted, 0, count) != count
            || actual.readNBytes(received, 0, count) != count
            || !Arrays.equals(wanted, 0, count, received, 0, count)) {
          actual.abort();
          throw new ImmutableObjectConflictException(key);
        }
        remaining -= count;
      }
      if (source.read() != -1 || actual.read() != -1) {
        actual.abort();
        throw new ImmutableObjectConflictException(key);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Object readback failed", exception);
    }
  }

  @FunctionalInterface
  private interface ExpectedStream {
    InputStream open() throws IOException;
  }

  @Override
  @Timed("gamedesign.asset.delete")
  public void deleteExportedAssets(String tenantId, int version, List<String> manifestAssetKeys) {
    throw new IllegalStateException(
        "CONTENT_ADDRESSED_PURGE_PROOF_REQUIRED: version-scoped deletion cannot select shared immutable objects");
  }

  private static String sha256Hex(byte[] value) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
      StringBuilder result = new StringBuilder(hash.length * 2);
      for (byte current : hash) {
        result.append(Character.forDigit((current >>> 4) & 0xf, 16));
        result.append(Character.forDigit(current & 0xf, 16));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private record PreparedArtifact(
      PublishedArtifactDigest digest,
      Path path,
      long byteSize,
      UUID manifestVersionId,
      String url) {}

  private static final class PrivateStaging implements AutoCloseable {
    private final List<Path> files = new ArrayList<>();

    private Path newFile() throws IOException {
      Path path = Files.createTempFile("firemud-asset-candidate-", ".bin");
      files.add(path);
      return path;
    }

    @Override
    public void close() {
      for (Path path : files) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException exception) {
          logger.warn("Failed to clean up private asset staging file {}", path, exception);
        }
      }
    }
  }

  private static final class ImmutableObjectConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private ImmutableObjectConflictException(String key) {
      super(
          IMMUTABLE_OBJECT_CONFLICT + ": existing object does not match requested bytes at " + key);
    }
  }
}
