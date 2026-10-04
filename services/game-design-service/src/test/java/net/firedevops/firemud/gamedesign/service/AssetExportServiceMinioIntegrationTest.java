package net.firedevops.firemud.gamedesign.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Exercises the real Game Design exporter and AWS SDK against a run-owned MinIO server.
 *
 * <p>Without {@code MINIO_ASSET_EXPORT_ENDPOINT}, this integration test is skipped so ordinary Game
 * Design test runs do not require an object store. The pinned-source MinIO workflow must set the
 * endpoint and credentials explicitly before selecting this test.
 */
class AssetExportServiceMinioIntegrationTest {
  private static final String TENANT_ID = "minio-provider-proof-tenant";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("8b0c1a0f-b8c9-4c4b-9021-381f57699001");
  private static final String PUBLIC_BASE_URL = "https://assets.example.invalid/assets";

  private S3Client s3Client;
  private String bucket;

  @AfterEach
  void removeOnlyTheOwnedBucket() {
    if (s3Client == null) {
      return;
    }
    try {
      if (bucket != null) {
        for (var object :
            s3Client
                .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()) {
          s3Client.deleteObject(
              DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
        }
        s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
      }
    } finally {
      s3Client.close();
    }
  }

  @Test
  void publishesConditionallyReadsBackExactBytesAndRecoversCommittedPutAgainstMinio() {
    String endpoint = System.getenv("MINIO_ASSET_EXPORT_ENDPOINT");
    Assumptions.assumeTrue(
        endpoint != null && !endpoint.isBlank(),
        "set MINIO_ASSET_EXPORT_ENDPOINT to run the real-provider proof");

    String accessKey = requiredEnvironment("MINIO_ASSET_EXPORT_ACCESS_KEY");
    String secretKey = requiredEnvironment("MINIO_ASSET_EXPORT_SECRET_KEY");
    s3Client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .region(Region.US_EAST_1)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    String ownedBucket = "firemud-asset-export-" + UUID.randomUUID().toString().replace("-", "");
    s3Client.createBucket(CreateBucketRequest.builder().bucket(ownedBucket).build());
    bucket = ownedBucket;

    byte[] publishedBytes = "provider-backed asset bytes".getBytes(StandardCharsets.UTF_8);
    String publishedKey = "artifacts/sha256/" + sha256Hex(publishedBytes);
    ExportFixture first = fixture(1, publishedBytes, "image/png");
    S3Client observingClient = observingClient(s3Client, first.events());
    AssetExportServiceImpl exporter = newExporter(first, observingClient, bucket, endpoint);

    ExportedAssetManifest published = exporter.exportAssets(TENANT_ID, 1);
    assertEquals(
        "sha256:" + sha256Hex(publishedBytes), published.artifactDigests().get(0).contentDigest());
    assertEquals(publishedKey, published.artifactDigests().get(0).immutableObjectKey());
    assertStoredObject(publishedKey, publishedBytes, "image/png");
    String manifestKey =
        "manifests/sha256/" + published.manifestHash().substring("sha256:".length());
    assertStoredObject(manifestKey, manifestBytes(published), "application/json");
    assertEquals("sha256:" + sha256Hex(manifestBytes(published)), published.manifestHash());

    S3Exception conditionalConflict =
        assertThrows(
            S3Exception.class,
            () ->
                s3Client.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(publishedKey)
                        .contentType("image/png")
                        .ifNoneMatch("*")
                        .build(),
                    RequestBody.fromBytes(publishedBytes)));
    assertEquals(412, conditionalConflict.statusCode());
    assertStoredObject(publishedKey, publishedBytes, "image/png");

    assertTrue(
        first.events().indexOf("candidate-read") < first.events().indexOf("put"),
        "durable candidate readback must precede the first object-store write");
    assertEquals(published, exporter.exportAssets(TENANT_ID, 1));

    byte[] conflictingBytes = "pre-existing conflicting bytes".getBytes(StandardCharsets.UTF_8);
    byte[] requestedBytes = "requested bytes for conflict".getBytes(StandardCharsets.UTF_8);
    String conflictKey = "artifacts/sha256/" + sha256Hex(requestedBytes);
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(conflictKey)
            .contentType("application/octet-stream")
            .build(),
        RequestBody.fromBytes(conflictingBytes));
    AssetExportServiceImpl conflictingExporter =
        newExporter(fixture(2, requestedBytes, "image/png"), s3Client, bucket, endpoint);
    IllegalStateException conflict =
        assertThrows(
            IllegalStateException.class, () -> conflictingExporter.exportAssets(TENANT_ID, 2));
    assertTrue(conflict.getMessage().startsWith("IMMUTABLE_OBJECT_CONFLICT"));
    assertStoredObject(conflictKey, conflictingBytes, "application/octet-stream");

    byte[] acknowledgedLateBytes =
        "committed before acknowledgement loss".getBytes(StandardCharsets.UTF_8);
    String acknowledgedLateKey = "artifacts/sha256/" + sha256Hex(acknowledgedLateBytes);
    S3Client acknowledgementLostClient =
        Mockito.mock(S3Client.class, AdditionalAnswers.delegatesTo(s3Client));
    AtomicBoolean loseAcknowledgement = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              PutObjectRequest request = invocation.getArgument(0);
              var response =
                  s3Client.putObject(request, invocation.getArgument(1, RequestBody.class));
              if (acknowledgedLateKey.equals(request.key())
                  && loseAcknowledgement.compareAndSet(true, false)) {
                throw SdkClientException.builder()
                    .message("simulated lost SDK acknowledgement after successful MinIO PUT")
                    .build();
              }
              return response;
            })
        .when(acknowledgementLostClient)
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    ExportFixture acknowledgementFixture = fixture(3, acknowledgedLateBytes, "application/pdf");
    AssetExportServiceImpl acknowledgementExporter =
        newExporter(acknowledgementFixture, acknowledgementLostClient, bucket, endpoint);
    ExportedAssetManifest recovered = acknowledgementExporter.exportAssets(TENANT_ID, 3);
    assertFalse(loseAcknowledgement.get(), "the lost acknowledgement must actually be injected");
    assertEquals(acknowledgedLateKey, recovered.artifactDigests().get(0).immutableObjectKey());
    assertStoredObject(acknowledgedLateKey, acknowledgedLateBytes, "application/pdf");
  }

  private void assertStoredObject(String key, byte[] expectedBytes, String expectedContentType) {
    ResponseBytes<GetObjectResponse> response =
        s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
    assertArrayEquals(expectedBytes, response.asByteArray());
    assertEquals((long) expectedBytes.length, response.response().contentLength());
    assertEquals(expectedContentType, response.response().contentType());
    assertEquals(sha256Hex(expectedBytes), sha256Hex(response.asByteArray()));
  }

  private byte[] manifestBytes(ExportedAssetManifest manifest) {
    return s3Client
        .getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key("manifests/sha256/" + manifest.manifestHash().substring("sha256:".length()))
                .build())
        .asByteArray();
  }

  private static String requiredEnvironment(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(key + " must be set when MINIO_ASSET_EXPORT_ENDPOINT is set");
    }
    return value;
  }

  private static AssetExportServiceImpl newExporter(
      ExportFixture fixture, S3Client client, String bucket, String endpoint) {
    AssetStoreProperties properties = new AssetStoreProperties();
    properties.setEndpoint(endpoint);
    properties.setPublicBaseUrl(PUBLIC_BASE_URL);
    properties.setBucket(bucket);
    properties.setRegion("us-east-1");
    return new AssetExportServiceImpl(
        fixture.publicationService(), fixture.candidateService(), client, properties);
  }

  private static S3Client observingClient(S3Client delegate, List<String> events) {
    S3Client observer = Mockito.mock(S3Client.class, AdditionalAnswers.delegatesTo(delegate));
    doAnswer(
            invocation -> {
              events.add("put");
              return delegate.putObject(
                  invocation.getArgument(0, PutObjectRequest.class),
                  invocation.getArgument(1, RequestBody.class));
            })
        .when(observer)
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    return observer;
  }

  private static ExportFixture fixture(int versionNumber, byte[] bytes, String contentType) {
    ExportSnapshot snapshot =
        new ExportSnapshot(
            TENANT_ID,
            10_000L + versionNumber,
            versionNumber,
            1L,
            CANONICAL_TENANT_ID,
            UUID.nameUUIDFromBytes(
                ("minio-provider-proof-version-" + versionNumber).getBytes(StandardCharsets.UTF_8)),
            List.of(
                new AssetSelection(
                    "branding/provider-proof",
                    20_000L + versionNumber,
                    bytes,
                    contentType,
                    "sha256:" + sha256Hex(bytes))));
    AtomicReference<ExportedAssetManifest> candidate = new AtomicReference<>();
    List<String> events = new ArrayList<>();
    VersionAssetPublicationService publicationService =
        new VersionAssetPublicationService() {
          @Override
          public void associateDraftAsset(
              String tenantId, long versionId, long assetId, String usageType) {
            throw new UnsupportedOperationException("the provider proof uses a frozen fixture");
          }

          @Override
          public ExportSnapshot freezeOrReadSnapshot(String tenantId, int requestedVersionNumber) {
            assertEquals(TENANT_ID, tenantId);
            assertEquals(versionNumber, requestedVersionNumber);
            return copySnapshot(snapshot);
          }

          @Override
          public ExportSnapshot readFrozenSnapshot(String tenantId, int requestedVersionNumber) {
            assertEquals(TENANT_ID, tenantId);
            assertEquals(versionNumber, requestedVersionNumber);
            return copySnapshot(snapshot);
          }
        };
    VersionAssetExportCandidateService candidateService =
        new VersionAssetExportCandidateService() {
          @Override
          public ExportedAssetManifest recordExportCandidate(
              String tenantId, int requestedVersionNumber, ExportedAssetManifest requested) {
            events.add("candidate-write");
            assertEquals(TENANT_ID, tenantId);
            assertEquals(versionNumber, requestedVersionNumber);
            ExportedAssetManifest existing = candidate.get();
            if (existing == null && candidate.compareAndSet(null, requested)) {
              return requested;
            }
            existing = candidate.get();
            assertEquals(existing, requested, "candidate conflicts must fail before object writes");
            return existing;
          }

          @Override
          public ExportedAssetManifest readExportCandidate(
              String tenantId, int requestedVersionNumber) {
            events.add("candidate-read");
            assertEquals(TENANT_ID, tenantId);
            assertEquals(versionNumber, requestedVersionNumber);
            return candidate.get();
          }
        };
    return new ExportFixture(publicationService, candidateService, events);
  }

  private static ExportSnapshot copySnapshot(ExportSnapshot source) {
    return new ExportSnapshot(
        source.tenantId(),
        source.versionId(),
        source.versionNumber(),
        source.capturedVersionStateEpoch(),
        source.canonicalTenantId(),
        source.canonicalVersionId(),
        source.items().stream()
            .map(
                item ->
                    new AssetSelection(
                        item.usageKey(),
                        item.assetId(),
                        item.bytes(),
                        item.contentType(),
                        item.contentDigest()))
            .toList());
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
        hex.append(Character.forDigit(value & 0xf, 16));
      }
      return hex.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private record ExportFixture(
      VersionAssetPublicationService publicationService,
      VersionAssetExportCandidateService candidateService,
      List<String> events) {}
}
