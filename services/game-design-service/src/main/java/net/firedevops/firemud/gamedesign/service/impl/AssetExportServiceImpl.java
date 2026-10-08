package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
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
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.service.AssetExportOutcomePendingException;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionAssetPublicationService;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
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

    String publicBaseUrl = requireApprovedPublicBaseUrl();
    List<PreparedArtifact> artifacts =
        selections.stream()
            .map(
                selection ->
                    prepareArtifact(selection, publicBaseUrl, snapshot.canonicalVersionId()))
            .toList();
    byte[] manifestBytes = serializeManifest(artifacts);
    String manifestHash = "sha256:" + sha256Hex(manifestBytes);
    String manifestObjectKey = MANIFEST_OBJECT_ROOT + manifestHash.substring("sha256:".length());

    List<PublishedArtifactDigest> artifactDigests =
        artifacts.stream().map(PreparedArtifact::digest).toList();
    List<String> requiredManifestAssetKeys =
        artifactDigests.stream().map(PublishedArtifactDigest::usageKey).toList();
    ExportedAssetManifest candidate =
        new ExportedAssetManifest(manifestHash, 1, requiredManifestAssetKeys, artifactDigests);

    if (publishedBundle == null) {
      requireDurableCandidate(tenantId, versionNumber, candidate);
    } else {
      requireExistingCandidate(tenantId, versionNumber, candidate);
      PublishedReleaseBundleContract.requireExactRepairMatch(publishedBundle, candidate);
    }

    // Candidate metadata and object proofs are durable before any private object write.
    // This producer does not transition lifecycle state or expose a release.
    for (PreparedArtifact artifact : artifacts) {
      writeAndVerifyImmutableObject(
          artifact.digest().immutableObjectKey(),
          artifact.bytes(),
          artifact.digest().contentType());
    }
    writeAndVerifyImmutableObject(manifestObjectKey, manifestBytes, MANIFEST_CONTENT_TYPE);
    return candidate;
  }

  private void requireExistingCandidate(
      String tenantId, int versionNumber, ExportedAssetManifest candidate) {
    ExportedAssetManifest independentlyRead;
    try {
      independentlyRead = candidateService.readExportCandidate(tenantId, versionNumber);
    } catch (RuntimeException exception) {
      throw candidateFailure("read", exception);
    }
    requireCandidateMatch("read", candidate, independentlyRead);
  }

  private void requireDurableCandidate(
      String tenantId, int versionNumber, ExportedAssetManifest candidate) {
    ExportedAssetManifest recorded;
    try {
      recorded = candidateService.recordExportCandidate(tenantId, versionNumber, candidate);
    } catch (RuntimeException exception) {
      throw candidateFailure("record", exception);
    }
    requireCandidateMatch("record", candidate, recorded);

    ExportedAssetManifest independentlyRead;
    try {
      independentlyRead = candidateService.readExportCandidate(tenantId, versionNumber);
    } catch (RuntimeException exception) {
      throw candidateFailure("read", exception);
    }
    requireCandidateMatch("read", candidate, independentlyRead);
  }

  private RuntimeException candidateFailure(String operation, RuntimeException cause) {
    if (isCandidateConflict(cause)) {
      return cause;
    }
    return new AssetExportOutcomePendingException(
        "export candidate " + operation + " outcome is unresolved", cause);
  }

  private void requireCandidateMatch(
      String operation, ExportedAssetManifest expected, ExportedAssetManifest actual) {
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
          || selection.bytes() == null
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
      String actualDigest = "sha256:" + sha256Hex(selection.bytes());
      if (!actualDigest.equals(selection.contentDigest())) {
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
          || !Arrays.equals(left.bytes(), right.bytes())) {
        throw new IllegalStateException(SNAPSHOT_MISMATCH);
      }
    }
  }

  private PreparedArtifact prepareArtifact(
      AssetSelection selection, String publicBaseUrl, UUID canonicalVersionId) {
    byte[] bytes = selection.bytes();
    String contentHash = sha256Hex(bytes);
    String digest = "sha256:" + contentHash;
    String immutableObjectKey = ARTIFACT_OBJECT_ROOT + contentHash;
    String url = publicBaseUrl + "/" + immutableObjectKey;

    PublishedArtifactDigest artifactDigest =
        new PublishedArtifactDigest(
            selection.usageKey(), "BINARY", immutableObjectKey, digest, selection.contentType(), 1);
    return new PreparedArtifact(artifactDigest, bytes, canonicalVersionId, url);
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

  private String requireApprovedPublicBaseUrl() {
    String configured = properties.getPublicBaseUrl();
    try {
      if (configured == null || configured.isBlank()) {
        throw new IllegalArgumentException("missing public base URL");
      }
      URI uri = new URI(configured);
      String path = uri.getRawPath();
      if (!uri.isAbsolute()
          || !"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !("/assets".equals(path) || "/assets/".equals(path))) {
        throw new IllegalArgumentException(
            "public base URL must be an approved HTTPS /assets origin");
      }
      rejectPrivateEndpointAlias(uri);
      return "https://" + uri.getRawAuthority() + "/assets";
    } catch (URISyntaxException | IllegalArgumentException exception) {
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

  private void writeAndVerifyImmutableObject(String key, byte[] bytes, String contentType) {
    RuntimeException putFailure = null;
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.getBucket())
              .key(key)
              .contentType(contentType)
              .contentLength((long) bytes.length)
              .ifNoneMatch("*")
              .build(),
          RequestBody.fromBytes(bytes));
    } catch (RuntimeException exception) {
      putFailure = exception;
    }

    try {
      verifyReadback(key, bytes, contentType);
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

  private void verifyReadback(String key, byte[] expectedBytes, String expectedContentType) {
    ResponseBytes<GetObjectResponse> response =
        s3Client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
    byte[] actualBytes = response.asByteArray();
    Long actualSize = response.response().contentLength();
    String actualContentType = response.response().contentType();
    if (actualSize == null
        || actualSize.longValue() != expectedBytes.length
        || !Objects.equals(expectedContentType, actualContentType)
        || !Arrays.equals(expectedBytes, actualBytes)
        || !sha256Hex(expectedBytes).equals(sha256Hex(actualBytes))) {
      throw new ImmutableObjectConflictException(key);
    }
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
      PublishedArtifactDigest digest, byte[] bytes, UUID manifestVersionId, String url) {
    private PreparedArtifact {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
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
