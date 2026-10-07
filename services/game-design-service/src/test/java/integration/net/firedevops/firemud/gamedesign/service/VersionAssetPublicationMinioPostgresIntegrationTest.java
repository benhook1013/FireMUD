package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetArtifactServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetExportCandidateServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetPublicationServiceImpl;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Composes the Flyway-backed owner rows and real publication services with the AWS SDK and a
 * run-owned MinIO provider. This proves the private PostgreSQL-to-object-store producer boundary;
 * it does not prove public delivery, creator authorization, release activation, or launch.
 */
@Testcontainers(disabledWithoutDocker = true)
class VersionAssetPublicationMinioPostgresIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String TENANT_ID = "minio-postgres-composition-tenant";
  private static final String PUBLIC_BASE_URL = "https://assets.example.invalid/assets";
  private static final String WORKFLOW_ID = "minio-postgres-composition-workflow";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
  void
      composesPostgresSnapshotAndCandidateWithMinioBeforeWriteAndRetriesAfterLostAcknowledgement() {
    String endpoint = System.getenv("MINIO_ASSET_EXPORT_ENDPOINT");
    Assumptions.assumeTrue(
        endpoint != null && !endpoint.isBlank(),
        "set MINIO_ASSET_EXPORT_ENDPOINT to run the PostgreSQL-to-MinIO composition proof");

    String accessKey = requiredEnvironment("MINIO_ASSET_EXPORT_ACCESS_KEY");
    String secretKey = requiredEnvironment("MINIO_ASSET_EXPORT_SECRET_KEY");
    s3Client = newS3Client(endpoint, accessKey, secretKey);
    String ownedBucket = "firemud-asset-pg-" + UUID.randomUUID().toString().replace("-", "");
    s3Client.createBucket(CreateBucketRequest.builder().bucket(ownedBucket).build());
    bucket = ownedBucket;

    Fixture fixture = fixture();
    Game game = saveGame(fixture);
    Version version = saveVersion(fixture, game, 1);
    byte[] publishedBytes = bytes("Flyway-backed mapped asset bytes");
    GameAsset asset =
        saveAsset(
            fixture, game.getTenantId(), "composition-proof-logo.png", "image/png", publishedBytes);
    fixture
        .publicationService()
        .associateDraftAsset(game.getTenantId(), version.getId(), asset.getId(), "logo");
    stageArtifact(fixture, game, version, WORKFLOW_ID);

    String publishedKey = "artifacts/sha256/" + sha256Hex(publishedBytes);
    AtomicBoolean losePutAcknowledgement = new AtomicBoolean(true);
    AtomicBoolean loseFirstReadback = new AtomicBoolean(true);
    AtomicReference<ExportedAssetManifest> candidateBeforePut = new AtomicReference<>();
    AtomicReference<String> artifactXminBeforePut = new AtomicReference<>();
    S3Client interruptedClient =
        observingClient(
            s3Client,
            request -> {
              assertThat(request.bucket()).isEqualTo(bucket);
              assertPersistedSnapshotAndCandidate(
                  fixture.schema(),
                  fixture.dataSource(),
                  game.getTenantId(),
                  version.getId(),
                  asset.getId(),
                  "composition-proof-logo.png",
                  publishedBytes,
                  "image/png",
                  publishedKey,
                  candidateBeforePut,
                  artifactXminBeforePut);
            },
            publishedKey,
            losePutAcknowledgement,
            loseFirstReadback);

    AssetExportServiceImpl interruptedExporter =
        newExporter(fixture, interruptedClient, bucket, endpoint);
    assertThatThrownBy(() -> interruptedExporter.exportAssets(game.getTenantId(), 1))
        .isInstanceOf(AssetExportOutcomePendingException.class)
        .hasMessageContaining("exact object readback is unavailable");
    assertThat(losePutAcknowledgement.get()).isFalse();
    assertThat(loseFirstReadback.get()).isFalse();

    assertStoredObject(publishedKey, publishedBytes, "image/png");
    assertThat(candidateBeforePut.get()).isNotNull();
    assertThat(artifactXminBeforePut.get()).isNotBlank();

    Fixture reconstructed = fixtureFromSchema(fixture.schema(), fixture.dataSource());
    ExportedAssetManifest retry =
        newExporter(reconstructed, s3Client, bucket, endpoint).exportAssets(game.getTenantId(), 1);
    assertThat(retry).isEqualTo(candidateBeforePut.get());
    assertStoredObject(publishedKey, publishedBytes, "image/png");
    String manifestKey = "manifests/sha256/" + retry.manifestHash().substring("sha256:".length());
    byte[] storedManifest = readObject(manifestKey, "application/json").asByteArray();
    assertThat(sha256Hex(storedManifest))
        .isEqualTo(retry.manifestHash().substring("sha256:".length()));
    assertThat(new String(storedManifest, StandardCharsets.UTF_8))
        .contains("composition-proof-logo.png");

    Fixture afterRetry = fixtureFromSchema(fixture.schema(), fixture.dataSource());
    assertThat(afterRetry.candidateService().readExportCandidate(game.getTenantId(), 1))
        .isEqualTo(candidateBeforePut.get());
    ExportSnapshot retriedSnapshot =
        afterRetry.publicationService().readFrozenSnapshot(game.getTenantId(), 1);
    assertThat(retriedSnapshot.versionId()).isEqualTo(version.getId());
    assertThat(retriedSnapshot.items()).hasSize(1);
    assertThat(retriedSnapshot.items().get(0).bytes()).isEqualTo(publishedBytes);
    assertThat(retriedSnapshot.items().get(0).contentDigest())
        .isEqualTo("sha256:" + sha256Hex(publishedBytes));
    assertThat(artifactXmin(afterRetry.dsl(), game.getTenantId(), version.getId()))
        .isEqualTo(artifactXminBeforePut.get());
    VersionAssetArtifact retainedCandidate =
        afterRetry
            .artifactRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), version.getId())
            .orElseThrow();
    assertThat(retainedCandidate.getStateEpoch()).isEqualTo(2L);
    assertThat(retainedCandidate.getManifestHash()).isEqualTo(retry.manifestHash());
    assertThat(
            afterRetry
                .versionRepository()
                .findByTenantIdAndVersionNumber(game.getTenantId(), 1)
                .orElseThrow()
                .getVersionState()
                .name())
        .isEqualTo("DRAFT");

    byte[] conflictingBytes = bytes("pre-existing conflicting object");
    byte[] requestedConflictBytes = bytes("requested immutable conflict bytes");
    String conflictKey = "artifacts/sha256/" + sha256Hex(requestedConflictBytes);
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(conflictKey)
            .contentType("application/octet-stream")
            .build(),
        RequestBody.fromBytes(conflictingBytes));

    Version conflictVersion = saveVersion(fixture, game, 2);
    GameAsset conflictAsset =
        saveAsset(
            fixture,
            game.getTenantId(),
            "immutable-conflict.png",
            "image/png",
            requestedConflictBytes);
    fixture
        .publicationService()
        .associateDraftAsset(
            game.getTenantId(), conflictVersion.getId(), conflictAsset.getId(), "logo");
    stageArtifact(fixture, game, conflictVersion, WORKFLOW_ID + "-conflict");

    Fixture conflictReadback = fixtureFromSchema(fixture.schema(), fixture.dataSource());
    S3Client conflictObservingClient =
        observingClient(
            s3Client,
            request -> {
              assertThat(request.bucket()).isEqualTo(bucket);
              assertPersistedSnapshotAndCandidate(
                  fixture.schema(),
                  fixture.dataSource(),
                  game.getTenantId(),
                  conflictVersion.getId(),
                  conflictAsset.getId(),
                  "immutable-conflict.png",
                  requestedConflictBytes,
                  "image/png",
                  conflictKey,
                  new AtomicReference<>(),
                  new AtomicReference<>());
            },
            null,
            new AtomicBoolean(false),
            new AtomicBoolean(false));
    assertThatThrownBy(
            () ->
                newExporter(conflictReadback, conflictObservingClient, bucket, endpoint)
                    .exportAssets(game.getTenantId(), 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("IMMUTABLE_OBJECT_CONFLICT");
    assertStoredObject(conflictKey, conflictingBytes, "application/octet-stream");
  }

  private void assertPersistedSnapshotAndCandidate(
      String schema,
      DriverManagerDataSource dataSource,
      String tenantId,
      long versionId,
      long assetId,
      String expectedUsageKey,
      byte[] sourceBytes,
      String contentType,
      String expectedArtifactKey,
      AtomicReference<ExportedAssetManifest> capturedCandidate,
      AtomicReference<String> capturedArtifactXmin) {
    Fixture independentlyReconstructed = fixtureFromSchema(schema, dataSource);
    Version version =
        independentlyReconstructed
            .versionRepository()
            .findByTenantIdAndId(tenantId, versionId)
            .orElseThrow();
    ExportSnapshot snapshot =
        independentlyReconstructed
            .publicationService()
            .readFrozenSnapshot(tenantId, version.getVersionNumber());
    assertThat(snapshot.versionId()).isEqualTo(versionId);
    assertThat(snapshot.canonicalTenantId()).isEqualTo(version.getCanonicalTenantId());
    assertThat(snapshot.canonicalVersionId()).isEqualTo(version.getCanonicalVersionId());
    assertThat(snapshot.items()).hasSize(1);
    assertThat(snapshot.items().get(0).assetId()).isEqualTo(assetId);
    assertThat(snapshot.items().get(0).bytes()).isEqualTo(sourceBytes);
    assertThat(snapshot.items().get(0).contentType()).isEqualTo(contentType);
    assertThat(snapshot.items().get(0).contentDigest())
        .isEqualTo("sha256:" + sha256Hex(sourceBytes));

    var header =
        independentlyReconstructed
            .dsl()
            .fetchOne(
                "SELECT item_count, captured_version_state_epoch FROM version_asset_export_snapshot "
                    + "WHERE tenant_id = ? AND version_id = ?",
                tenantId,
                versionId);
    assertThat(header).isNotNull();
    assertThat(header.get("item_count", Integer.class)).isEqualTo(1);
    assertThat(header.get("captured_version_state_epoch", Long.class)).isEqualTo(1L);
    var persistedItem =
        independentlyReconstructed
            .dsl()
            .fetchOne(
                "SELECT i.usage_key, i.asset_id, i.content_type, i.content_hash, i.byte_size, "
                    + "va.usage_key AS mapping_usage_key, va.usage_type, ga.file_name, ga.data, "
                    + "g.id AS source_game_id, "
                    + "g.canonical_tenant_id, v.identity_source_game_row_id, "
                    + "v.identity_source_game_tenant_key, v.identity_source_provenance_kind "
                    + "FROM version_asset_export_item i "
                    + "JOIN version_asset va ON va.tenant_id = i.tenant_id "
                    + "AND va.version_id = i.version_id AND va.asset_id = i.asset_id "
                    + "AND va.usage_key = i.usage_key "
                    + "JOIN game_assets ga ON ga.tenant_id = i.tenant_id AND ga.id = i.asset_id "
                    + "JOIN version v ON v.tenant_id = i.tenant_id AND v.id = i.version_id "
                    + "JOIN game g ON g.tenant_id = v.identity_source_game_tenant_key "
                    + "AND g.id = v.identity_source_game_row_id "
                    + "WHERE i.tenant_id = ? AND i.version_id = ?",
                tenantId,
                versionId);
    assertThat(persistedItem).isNotNull();
    assertThat(persistedItem.get("usage_key", String.class)).isEqualTo(expectedUsageKey);
    assertThat(persistedItem.get("mapping_usage_key", String.class)).isEqualTo(expectedUsageKey);
    assertThat(persistedItem.get("asset_id", Long.class)).isEqualTo(assetId);
    assertThat(persistedItem.get("content_type", String.class)).isEqualTo(contentType);
    assertThat(persistedItem.get("content_hash", String.class))
        .isEqualTo("sha256:" + sha256Hex(sourceBytes));
    assertThat(persistedItem.get("byte_size", Long.class)).isEqualTo((long) sourceBytes.length);
    assertThat(persistedItem.get("usage_type", String.class)).isEqualTo("logo");
    assertThat(persistedItem.get("file_name", String.class)).isEqualTo(expectedUsageKey);
    assertThat(persistedItem.get("data", byte[].class)).isEqualTo(sourceBytes);
    assertThat(persistedItem.get("source_game_id", Long.class))
        .isEqualTo(persistedItem.get("identity_source_game_row_id", Long.class));
    assertThat(persistedItem.get("identity_source_game_tenant_key", String.class))
        .isEqualTo(tenantId);
    assertThat(persistedItem.get("identity_source_provenance_kind", String.class))
        .isEqualTo("NEW_GAME_ROW");
    assertThat(persistedItem.get("canonical_tenant_id", UUID.class))
        .isEqualTo(snapshot.canonicalTenantId());

    ExportedAssetManifest candidate =
        independentlyReconstructed
            .candidateService()
            .readExportCandidate(tenantId, version.getVersionNumber());
    assertThat(candidate.manifestSchemaVersion()).isEqualTo(1);
    assertThat(candidate.artifactDigests()).hasSize(1);
    PublishedArtifactDigest digest = candidate.artifactDigests().get(0);
    assertThat(digest.usageKey()).isEqualTo(snapshot.items().get(0).usageKey());
    assertThat(digest.immutableObjectKey()).isEqualTo(expectedArtifactKey);
    assertThat(digest.contentDigest()).isEqualTo("sha256:" + sha256Hex(sourceBytes));
    assertThat(digest.contentType()).isEqualTo(contentType);
    assertThat(candidate.manifestHash()).matches("sha256:[0-9a-f]{64}");

    VersionAssetArtifact storedArtifact =
        independentlyReconstructed
            .artifactRepository()
            .findByTenantIdAndVersionId(tenantId, versionId)
            .orElseThrow();
    assertThat(storedArtifact.getArtifactState().name()).isEqualTo("STAGED");
    assertThat(storedArtifact.getStateEpoch()).isEqualTo(2L);
    assertThat(storedArtifact.getManifestHash()).isEqualTo(candidate.manifestHash());
    assertThat(storedArtifact.getManifestSchemaVersion()).isEqualTo(1);
    assertThat(storedArtifact.getArtifactDigestsJson())
        .contains(expectedArtifactKey, digest.contentDigest());
    assertThat(storedArtifact.getPublishedObjectProofsJson())
        .contains(expectedArtifactKey, "manifests/sha256/", candidate.manifestHash());
    assertThat(storedArtifact.getCandidateSnapshotVersionId()).isEqualTo(versionId);
    assertThat(storedArtifact.getExportedManifestAssetKeysJson()).contains(expectedUsageKey);
    var mappingCount =
        independentlyReconstructed
            .dsl()
            .fetchOne(
                "SELECT count(*) AS mapping_count FROM version_asset "
                    + "WHERE tenant_id = ? AND version_id = ?",
                tenantId,
                versionId);
    assertThat(mappingCount.get("mapping_count", Long.class)).isEqualTo(1L);

    String rowXmin = artifactXmin(independentlyReconstructed.dsl(), tenantId, versionId);
    if (capturedCandidate != null) {
      capturedCandidate.compareAndSet(null, candidate);
    }
    if (capturedArtifactXmin != null) {
      capturedArtifactXmin.compareAndSet(null, rowXmin);
    }
  }

  private Fixture fixture() {
    String schema = "gd_minio_pg_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema);
    return fixtureFromSchema(schema, dataSource);
  }

  private Fixture fixtureFromSchema(String schema, DriverManagerDataSource dataSource) {
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    transactionTemplate.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    GameRepository gameRepository = new GameRepository(dsl);
    GameAssetRepository gameAssetRepository = new GameAssetRepository(dsl);
    PostgresProperties postgresProperties = new PostgresProperties();
    postgresProperties.setSchema(schema);
    VersionRepository versionRepository = new VersionRepository(dsl, postgresProperties);
    VersionAssetPublicationRepository publicationRepository =
        new VersionAssetPublicationRepository(dsl);
    VersionAssetPublicationServiceImpl publicationService =
        new VersionAssetPublicationServiceImpl(transactionManager, publicationRepository);
    VersionAssetArtifactRepository artifactRepository = new VersionAssetArtifactRepository(dsl);
    VersionAssetExportCandidateServiceImpl candidateService =
        new VersionAssetExportCandidateServiceImpl(
            versionRepository,
            gameRepository,
            new net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository(dsl),
            artifactRepository,
            publicationService,
            transactionManager,
            new ObjectMapper());
    return new Fixture(
        schema,
        dataSource,
        dsl,
        transactionManager,
        transactionTemplate,
        gameRepository,
        gameAssetRepository,
        versionRepository,
        publicationService,
        artifactRepository,
        candidateService);
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private void migrate(DriverManagerDataSource dataSource, String schema) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private Game saveGame(Fixture fixture) {
    Game game = new Game();
    game.setTenantId(TENANT_ID);
    game.setName("PostgreSQL MinIO composition owner");
    game.setDescription("Canonical Game Design source row");
    return fixture.transactionTemplate().execute(status -> fixture.gameRepository().save(game));
  }

  private Version saveVersion(Fixture fixture, Game game, int versionNumber) {
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setVersionNumber(versionNumber);
    version.setNotes("PostgreSQL MinIO composition fixture");
    return fixture
        .transactionTemplate()
        .execute(status -> fixture.versionRepository().save(version));
  }

  private GameAsset saveAsset(
      Fixture fixture, String tenantId, String fileName, String contentType, byte[] data) {
    GameAsset asset = new GameAsset();
    asset.setTenantId(tenantId);
    asset.setFileName(fileName);
    asset.setContentType(contentType);
    asset.setData(data);
    return fixture
        .transactionTemplate()
        .execute(status -> fixture.gameAssetRepository().save(asset));
  }

  private void stageArtifact(Fixture fixture, Game game, Version version, String workflowId) {
    VersionAssetArtifactServiceImpl stageService =
        new VersionAssetArtifactServiceImpl(
            fixture.artifactRepository(),
            null,
            fixture.versionRepository(),
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper());
    fixture
        .transactionTemplate()
        .execute(
            status ->
                stageService.stageExport(
                    game.getTenantId(), version.getId(), version.getVersionNumber(), workflowId));
  }

  private AssetExportServiceImpl newExporter(
      Fixture fixture, S3Client client, String bucket, String endpoint) {
    AssetStoreProperties properties = new AssetStoreProperties();
    properties.setEndpoint(endpoint);
    properties.setPublicBaseUrl(PUBLIC_BASE_URL);
    properties.setBucket(bucket);
    properties.setRegion("us-east-1");
    return new AssetExportServiceImpl(
        fixture.publicationService(), fixture.candidateService(), client, properties);
  }

  private S3Client newS3Client(String endpoint, String accessKey, String secretKey) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .region(Region.US_EAST_1)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  private S3Client observingClient(
      S3Client delegate,
      Consumer<PutObjectRequest> beforePut,
      String loseAcknowledgementKey,
      AtomicBoolean losePutAcknowledgement,
      AtomicBoolean loseFirstReadback) {
    S3Client observer = Mockito.mock(S3Client.class, AdditionalAnswers.delegatesTo(delegate));
    doAnswer(
            invocation -> {
              PutObjectRequest request = invocation.getArgument(0, PutObjectRequest.class);
              beforePut.accept(request);
              var response =
                  delegate.putObject(request, invocation.getArgument(1, RequestBody.class));
              if (request.key().equals(loseAcknowledgementKey)
                  && losePutAcknowledgement.compareAndSet(true, false)) {
                throw SdkClientException.builder()
                    .message("simulated lost SDK acknowledgement after successful MinIO PUT")
                    .build();
              }
              return response;
            })
        .when(observer)
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    doAnswer(
            invocation -> {
              GetObjectRequest request = invocation.getArgument(0, GetObjectRequest.class);
              if (request.key().equals(loseAcknowledgementKey)
                  && loseFirstReadback.compareAndSet(true, false)) {
                throw SdkClientException.builder()
                    .message("simulated unavailable readback after lost MinIO PUT acknowledgement")
                    .build();
              }
              return delegate.getObjectAsBytes(request);
            })
        .when(observer)
        .getObjectAsBytes(any(GetObjectRequest.class));
    return observer;
  }

  private ResponseBytes<GetObjectResponse> readObject(String key, String contentType) {
    ResponseBytes<GetObjectResponse> response =
        s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
    assertThat(response.response().contentType()).isEqualTo(contentType);
    assertThat(response.response().contentLength()).isEqualTo((long) response.asByteArray().length);
    return response;
  }

  private void assertStoredObject(String key, byte[] expectedBytes, String expectedContentType) {
    ResponseBytes<GetObjectResponse> response = readObject(key, expectedContentType);
    assertThat(response.asByteArray()).isEqualTo(expectedBytes);
    assertThat(sha256Hex(response.asByteArray())).isEqualTo(sha256Hex(expectedBytes));
  }

  private String artifactXmin(DSLContext dsl, String tenantId, long versionId) {
    var artifactRow =
        dsl.fetchOne(
            "SELECT xmin::text AS xmin FROM version_asset_artifact "
                + "WHERE tenant_id = ? AND version_id = ?",
            tenantId,
            versionId);
    if (artifactRow == null) {
      throw new IllegalStateException("Expected persisted Version asset artifact xmin row");
    }
    return artifactRow.get("xmin", String.class);
  }

  private static String requiredEnvironment(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(key + " must be set when MINIO_ASSET_EXPORT_ENDPOINT is set");
    }
    return value;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256Hex(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transactionTemplate,
      GameRepository gameRepository,
      GameAssetRepository gameAssetRepository,
      VersionRepository versionRepository,
      VersionAssetPublicationServiceImpl publicationService,
      VersionAssetArtifactRepository artifactRepository,
      VersionAssetExportCandidateServiceImpl candidateService) {}
}
