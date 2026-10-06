package integration.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetArtifactServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetExportCandidateServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetPublicationServiceImpl;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class VersionAssetExportCandidateServiceIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final MigrationVersion V37 = MigrationVersion.fromVersion("37");
  private static final MigrationVersion V38 = MigrationVersion.fromVersion("38");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void durableCandidateIsSnapshotBoundRetryableAndIncludesManifestAndBinaryProofs() {
    Fixture fixture = fixture(null);
    Game game = saveGame(fixture, "candidate-owner");
    Version version = saveVersion(fixture, game, 1);
    byte[] selectedBytes = bytes("retained candidate source bytes");
    GameAsset selected =
        saveAsset(fixture, game.getTenantId(), "logo雪.png", "image/png", selectedBytes);
    fixture
        .publicationService()
        .associateDraftAsset(game.getTenantId(), version.getId(), selected.getId(), "logo");
    ExportSnapshot snapshot =
        fixture.publicationService().freezeOrReadSnapshot(game.getTenantId(), 1);
    stageArtifact(fixture, game.getTenantId(), version.getId(), 1, "stable-publish-workflow");
    ExportedAssetManifest candidate = candidate(snapshot, "manifest bytes bound to version one");

    ExportedAssetManifest committed =
        fixture.candidateService().recordExportCandidate(game.getTenantId(), 1, candidate);
    VersionAssetArtifact afterWrite =
        fixture
            .artifactRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), version.getId())
            .orElseThrow();
    String firstXmin = artifactXmin(fixture.dsl(), game.getTenantId(), version.getId());
    assertThat(committed).isEqualTo(candidate);
    assertThat(afterWrite.getArtifactState()).isEqualTo(VersionAssetArtifactState.STAGED);
    assertThat(afterWrite.getStateEpoch()).isEqualTo(2L);
    assertThat(afterWrite.getLastWorkflowId()).isEqualTo("stable-publish-workflow");
    assertThat(afterWrite.getManifestHash()).isEqualTo(candidate.manifestHash());
    assertThat(afterWrite.getManifestSchemaVersion()).isEqualTo(1);
    assertThat(afterWrite.getCandidateSnapshotVersionId()).isEqualTo(version.getId());
    assertThat(afterWrite.getExportedManifestAssetKeysJson()).isEqualTo("[\"logo雪.png\"]");
    assertThat(afterWrite.getArtifactDigestsJson()).contains("logo雪.png", "BINARY", "image/png");
    assertThat(afterWrite.getPublishedObjectProofsJson())
        .contains("artifacts/sha256/", "manifests/sha256/", candidate.manifestHash());
    VersionAssetExportCandidateServiceImpl restartedService =
        candidateService(
            fixture.dsl(),
            fixture.versionRepository(),
            fixture.artifactRepository(),
            fixture.publicationService(),
            fixture.transactionManager());
    assertThat(restartedService.readExportCandidate(game.getTenantId(), 1)).isEqualTo(candidate);

    ExportedAssetManifest conflictingManifest =
        new ExportedAssetManifest(
            "sha256:" + "a".repeat(64),
            candidate.manifestSchemaVersion(),
            candidate.requiredManifestAssetKeys(),
            candidate.artifactDigests());
    assertThatThrownBy(
            () ->
                fixture
                    .candidateService()
                    .recordExportCandidate(game.getTenantId(), 1, conflictingManifest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");
    assertThat(artifactXmin(fixture.dsl(), game.getTenantId(), version.getId()))
        .isEqualTo(firstXmin);

    saveAsset(
        fixture,
        game.getTenantId(),
        "unrelated-after-freeze.css",
        "text/css",
        bytes("must not enter the candidate"));
    assertThat(restartedService.recordExportCandidate(game.getTenantId(), 1, candidate))
        .isEqualTo(candidate);
    assertThat(artifactXmin(fixture.dsl(), game.getTenantId(), version.getId()))
        .isEqualTo(firstXmin);

    fixture
        .dsl()
        .execute(
            "UPDATE version SET version_state = 'PUBLISHED', version_state_epoch = 2 "
                + "WHERE tenant_id = ? AND id = ?",
            game.getTenantId(),
            version.getId());
    fixture
        .dsl()
        .execute(
            "UPDATE version_asset_artifact SET artifact_state = 'PUBLISHED', state_epoch = 3 "
                + "WHERE tenant_id = ? AND version_id = ?",
            game.getTenantId(),
            version.getId());
    String publishedXmin = artifactXmin(fixture.dsl(), game.getTenantId(), version.getId());
    assertThat(restartedService.recordExportCandidate(game.getTenantId(), 1, candidate))
        .isEqualTo(candidate);
    assertThat(restartedService.readExportCandidate(game.getTenantId(), 1)).isEqualTo(candidate);
    assertThat(artifactXmin(fixture.dsl(), game.getTenantId(), version.getId()))
        .isEqualTo(publishedXmin);
  }

  @Test
  void emptyFrozenSnapshotIsExplicitAndMissingSnapshotOrArtifactIsNeverInferred() {
    Fixture fixture = fixture(null);
    Game game = saveGame(fixture, "candidate-empty-owner");
    Version emptyVersion = saveVersion(fixture, game, 1);
    ExportSnapshot emptySnapshot =
        fixture.publicationService().freezeOrReadSnapshot(game.getTenantId(), 1);
    stageArtifact(fixture, game.getTenantId(), emptyVersion.getId(), 1, "empty-workflow");
    ExportedAssetManifest emptyCandidate = candidate(emptySnapshot, "empty selection manifest");
    assertThat(emptyCandidate.artifactDigests()).isEmpty();
    assertThat(
            fixture.candidateService().recordExportCandidate(game.getTenantId(), 1, emptyCandidate))
        .isEqualTo(emptyCandidate);
    VersionAssetArtifact emptyArtifact =
        fixture
            .artifactRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), emptyVersion.getId())
            .orElseThrow();
    assertThat(emptyArtifact.getPublishedObjectProofsJson())
        .contains("manifests/sha256/")
        .doesNotContain("artifacts/sha256/");

    Version noSnapshotVersion = saveVersion(fixture, game, 2);
    stageArtifact(
        fixture, game.getTenantId(), noSnapshotVersion.getId(), 2, "no-snapshot-workflow");
    assertThatThrownBy(() -> fixture.candidateService().readExportCandidate(game.getTenantId(), 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No durable frozen Version asset snapshot");

    Version noArtifactVersion = saveVersion(fixture, game, 3);
    fixture.publicationService().freezeOrReadSnapshot(game.getTenantId(), 3);
    assertThatThrownBy(
            () ->
                fixture
                    .candidateService()
                    .recordExportCandidate(
                        game.getTenantId(), 3, candidateForEmptyVersion(noArtifactVersion)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_NOT_FOUND");
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM version_asset_artifact WHERE tenant_id = ? AND version_id = ?",
                    game.getTenantId(),
                    noArtifactVersion.getId()))
        .isNull();
  }

  @Test
  void retainedNullCandidateFieldsRemainAbsentAcrossV38WithoutReadInference() {
    Fixture fixture = fixture(V37);
    Game game = saveGame(fixture, "candidate-retained-owner");
    Version version = saveVersion(fixture, game, 1);
    GameAsset asset =
        saveAsset(
            fixture,
            game.getTenantId(),
            "retained.bin",
            "application/octet-stream",
            bytes("retained"));
    fixture
        .publicationService()
        .associateDraftAsset(game.getTenantId(), version.getId(), asset.getId(), "binary");
    fixture.publicationService().freezeOrReadSnapshot(game.getTenantId(), 1);
    insertLegacyStagedArtifact(fixture, game.getTenantId(), version.getId(), 1);
    String oldXmin = artifactXmin(fixture.dsl(), game.getTenantId(), version.getId());

    migrate(fixture.dataSource(), fixture.schema(), V38);
    VersionAssetExportCandidateServiceImpl candidateService =
        candidateService(
            fixture.dsl(),
            fixture.versionRepository(),
            fixture.artifactRepository(),
            fixture.publicationService(),
            fixture.transactionManager());

    assertThatThrownBy(() -> candidateService.readExportCandidate(game.getTenantId(), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_NOT_FOUND");
    VersionAssetArtifact artifact =
        fixture
            .artifactRepository()
            .findByTenantIdAndVersionId(game.getTenantId(), version.getId())
            .orElseThrow();
    assertThat(artifact.getManifestSchemaVersion()).isNull();
    assertThat(artifact.getArtifactDigestsJson()).isNull();
    assertThat(artifact.getPublishedObjectProofsJson()).isNull();
    assertThat(artifact.getCandidateSnapshotVersionId()).isNull();
    assertThat(artifactXmin(fixture.dsl(), game.getTenantId(), version.getId())).isEqualTo(oldXmin);
  }

  @Test
  void failedDatabaseCandidateWriteRollsBackAllEvidenceAndEpoch() {
    Fixture fixture = candidateFixture("candidate-rollback-owner");
    String functionName = "fail_candidate_update";
    String triggerName = "trg_fail_candidate_update";
    fixture
        .dsl()
        .execute(
            "CREATE FUNCTION "
                + functionName
                + "() RETURNS TRIGGER AS $$ BEGIN "
                + "RAISE EXCEPTION 'test candidate persistence failure'; END; $$ LANGUAGE plpgsql");
    fixture
        .dsl()
        .execute(
            "CREATE TRIGGER "
                + triggerName
                + " BEFORE UPDATE ON version_asset_artifact "
                + "FOR EACH ROW EXECUTE FUNCTION "
                + functionName
                + "()");
    try {
      assertThatThrownBy(
              () ->
                  fixture
                      .candidateService()
                      .recordExportCandidate(fixture.tenantId(), 1, fixture.candidate()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("test candidate persistence failure");
      VersionAssetArtifact afterFailure =
          fixture
              .artifactRepository()
              .findByTenantIdAndVersionId(fixture.tenantId(), fixture.versionId())
              .orElseThrow();
      assertThat(afterFailure.getStateEpoch()).isEqualTo(1L);
      assertThat(afterFailure.getManifestHash()).isNull();
      assertThat(afterFailure.getManifestSchemaVersion()).isNull();
      assertThat(afterFailure.getArtifactDigestsJson()).isNull();
      assertThat(afterFailure.getPublishedObjectProofsJson()).isNull();
      assertThat(afterFailure.getCandidateSnapshotVersionId()).isNull();
    } finally {
      fixture.dsl().execute("DROP TRIGGER IF EXISTS " + triggerName + " ON version_asset_artifact");
      fixture.dsl().execute("DROP FUNCTION IF EXISTS " + functionName + "()");
    }
  }

  @Test
  void concurrentExactCandidateRecordsCommitOneEpochAndCallerRollbackCannotUndoIt()
      throws Exception {
    Fixture fixture = candidateFixture("candidate-race-owner");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ExportedAssetManifest> first =
          executor.submit(
              () -> {
                await(start);
                return fixture
                    .candidateService()
                    .recordExportCandidate(fixture.tenantId(), 1, fixture.candidate());
              });
      Future<ExportedAssetManifest> second =
          executor.submit(
              () -> {
                await(start);
                return fixture
                    .candidateService()
                    .recordExportCandidate(fixture.tenantId(), 1, fixture.candidate());
              });
      start.countDown();
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(fixture.candidate());
      assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(fixture.candidate());
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            fixture
                .artifactRepository()
                .findByTenantIdAndVersionId(fixture.tenantId(), fixture.versionId())
                .orElseThrow()
                .getStateEpoch())
        .isEqualTo(2L);

    fixture
        .transactionTemplate()
        .execute(
            status -> {
              assertThat(
                      fixture
                          .candidateService()
                          .recordExportCandidate(fixture.tenantId(), 1, fixture.candidate()))
                  .isEqualTo(fixture.candidate());
              status.setRollbackOnly();
              return null;
            });
    assertThat(fixture.candidateService().readExportCandidate(fixture.tenantId(), 1))
        .isEqualTo(fixture.candidate());
  }

  @Test
  void databaseFreezesAllCandidateProofFieldsAndEpochOverflowWritesNothing() {
    Fixture fixture = candidateFixture("candidate-immutable-owner");
    fixture.candidateService().recordExportCandidate(fixture.tenantId(), 1, fixture.candidate());
    String proofXmin = artifactXmin(fixture.dsl(), fixture.tenantId(), fixture.versionId());

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE version_asset_artifact SET manifest_hash = ?, "
                            + "manifest_schema_version = 2, artifact_digests_json = '[]', "
                            + "published_object_proofs_json = '[]', candidate_snapshot_version_id = NULL, "
                            + "exported_manifest_asset_keys_json = '[]' "
                            + "WHERE tenant_id = ? AND version_id = ?",
                        "sha256:" + "f".repeat(64),
                        fixture.tenantId(),
                        fixture.versionId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ASSET_EXPORT_CANDIDATE_IMMUTABLE");
    assertThat(artifactXmin(fixture.dsl(), fixture.tenantId(), fixture.versionId()))
        .isEqualTo(proofXmin);
    assertThat(fixture.candidateService().readExportCandidate(fixture.tenantId(), 1))
        .isEqualTo(fixture.candidate());

    Fixture overflow = candidateFixture("candidate-overflow-owner");
    overflow
        .dsl()
        .execute(
            "UPDATE version_asset_artifact SET state_epoch = ? WHERE tenant_id = ? AND version_id = ?",
            Long.MAX_VALUE,
            overflow.tenantId(),
            overflow.versionId());
    assertThatThrownBy(
            () ->
                overflow
                    .candidateService()
                    .recordExportCandidate(overflow.tenantId(), 1, overflow.candidate()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_EXPORT_CANDIDATE_CONFLICT");
    VersionAssetArtifact unchanged =
        overflow
            .artifactRepository()
            .findByTenantIdAndVersionId(overflow.tenantId(), overflow.versionId())
            .orElseThrow();
    assertThat(unchanged.getStateEpoch()).isEqualTo(Long.MAX_VALUE);
    assertThat(unchanged.getManifestSchemaVersion()).isNull();
    assertThat(unchanged.getPublishedObjectProofsJson()).isNull();
  }

  private Fixture candidateFixture(String tenantId) {
    Fixture fixture = fixture(null);
    Game game = saveGame(fixture, tenantId);
    Version version = saveVersion(fixture, game, 1);
    byte[] bytes = bytes("integration exact asset bytes");
    GameAsset asset = saveAsset(fixture, tenantId, "candidate.png", "image/png", bytes);
    fixture
        .publicationService()
        .associateDraftAsset(tenantId, version.getId(), asset.getId(), "logo");
    ExportSnapshot snapshot = fixture.publicationService().freezeOrReadSnapshot(tenantId, 1);
    stageArtifact(fixture, tenantId, version.getId(), 1, "publish-workflow-candidate");
    return fixture.withCandidate(
        tenantId, version.getId(), candidate(snapshot, "integration manifest bytes"));
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "gd_vac_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, target);
    return fixtureFromSchema(schema, dataSource);
  }

  private Fixture fixtureFromSchema(String schema, DriverManagerDataSource dataSource) {
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
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
        candidateService(
            dsl, versionRepository, artifactRepository, publicationService, transactionManager));
  }

  private VersionAssetExportCandidateServiceImpl candidateService(
      DSLContext dsl,
      VersionRepository versionRepository,
      VersionAssetArtifactRepository artifactRepository,
      VersionAssetPublicationServiceImpl publicationService,
      PlatformTransactionManager transactionManager) {
    return new VersionAssetExportCandidateServiceImpl(
        versionRepository,
        new GameRepository(dsl),
        new net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository(dsl),
        artifactRepository,
        publicationService,
        transactionManager,
        new ObjectMapper());
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private void migrate(DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private Game saveGame(Fixture fixture, String tenantId) {
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("Version asset candidate owner");
    game.setDescription("Candidate integration source row");
    return fixture.transactionTemplate().execute(status -> fixture.gameRepository().save(game));
  }

  private Version saveVersion(Fixture fixture, Game game, int versionNumber) {
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setVersionNumber(versionNumber);
    version.setNotes("Candidate integration fixture");
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

  private void stageArtifact(
      Fixture fixture, String tenantId, long versionId, int versionNumber, String workflowId) {
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
            status -> stageService.stageExport(tenantId, versionId, versionNumber, workflowId));
  }

  private void insertLegacyStagedArtifact(
      Fixture fixture, String tenantId, long versionId, int versionNumber) {
    fixture
        .dsl()
        .execute(
            "INSERT INTO version_asset_artifact "
                + "(tenant_id, version_id, exported_version_number, artifact_state, state_epoch, "
                + "manifest_hash, last_workflow_id, last_error_code, last_error_message, "
                + "exported_manifest_asset_keys_json, updated_at) "
                + "VALUES (?, ?, ?, 'STAGED', 1, NULL, 'retained-workflow', NULL, NULL, '[]', CURRENT_TIMESTAMP)",
            tenantId,
            versionId,
            versionNumber);
  }

  private ExportedAssetManifest candidate(ExportSnapshot snapshot, String manifestSource) {
    List<PublishedArtifactDigest> digests =
        snapshot.items().stream()
            .map(
                source ->
                    new PublishedArtifactDigest(
                        source.usageKey(),
                        "BINARY",
                        "artifacts/sha256/" + source.contentDigest().substring(7),
                        source.contentDigest(),
                        source.contentType(),
                        1))
            .toList();
    return new ExportedAssetManifest(
        sha256(bytes(manifestSource)),
        1,
        digests.stream().map(PublishedArtifactDigest::usageKey).toList(),
        digests);
  }

  private ExportedAssetManifest candidateForEmptyVersion(Version version) {
    return new ExportedAssetManifest(
        sha256(bytes("version:" + version.getId() + ":empty-manifest")), 1, List.of(), List.of());
  }

  private String artifactXmin(DSLContext dsl, String tenantId, long versionId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT xmin::text AS xmin FROM version_asset_artifact "
                    + "WHERE tenant_id = ? AND version_id = ?",
                tenantId,
                versionId),
            "Expected Version asset artifact xmin row")
        .get("xmin", String.class);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent candidate test start signal timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent candidate test was interrupted", exception);
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
      VersionAssetExportCandidateServiceImpl candidateService,
      String tenantId,
      long versionId,
      ExportedAssetManifest candidate) {
    private Fixture(
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
        VersionAssetExportCandidateServiceImpl candidateService) {
      this(
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
          candidateService,
          null,
          0L,
          null);
    }

    private Fixture withCandidate(
        String tenantId, long versionId, ExportedAssetManifest candidate) {
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
          candidateService,
          tenantId,
          versionId,
          candidate);
    }
  }
}
