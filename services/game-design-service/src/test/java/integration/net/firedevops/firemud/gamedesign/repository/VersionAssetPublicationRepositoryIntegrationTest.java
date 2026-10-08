package integration.net.firedevops.firemud.gamedesign.repository;

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
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetPublicationServiceImpl;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class VersionAssetPublicationRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final MigrationVersion V35 = MigrationVersion.fromVersion("35");
  private static final MigrationVersion V37 = MigrationVersion.fromVersion("37");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @org.junit.jupiter.api.Test
  void v37LeavesRetainedVersionTupleAndXminUnchangedAndDeniesItsUnmappedIdentity() {
    Fixture fixture = fixture(V35);
    Game game = saveGame(fixture, "retained-publication-owner");
    long retainedVersionId = insertRetainedVersion(fixture.dsl(), game.getTenantId());
    byte[] retainedSourceBytes = bytes("retained asset source");
    GameAsset retainedSource =
        saveAsset(fixture, game.getTenantId(), "retained.png", "image/png", retainedSourceBytes);
    Map<String, Object> retainedTupleBefore =
        retainedVersionTuple(fixture.dsl(), retainedVersionId);
    Map<String, Object> sourceTupleBefore = assetTuple(fixture.dsl(), retainedSource.getId());
    String retainedXminBefore = versionXmin(fixture.dsl(), retainedVersionId);
    String sourceXminBefore = assetXmin(fixture.dsl(), retainedSource.getId());

    migrate(fixture.dataSource(), fixture.schema(), V37);
    Fixture migrated = fixtureFromSchema(fixture.schema(), fixture.dataSource());

    assertThat(retainedVersionTuple(migrated.dsl(), retainedVersionId))
        .isEqualTo(retainedTupleBefore);
    assertThat(assetTuple(migrated.dsl(), retainedSource.getId())).isEqualTo(sourceTupleBefore);
    assertThat(assetData(migrated.dsl(), retainedSource.getId())).isEqualTo(retainedSourceBytes);
    assertThat(versionXmin(migrated.dsl(), retainedVersionId)).isEqualTo(retainedXminBefore);
    assertThat(assetXmin(migrated.dsl(), retainedSource.getId())).isEqualTo(sourceXminBefore);
    assertThatThrownBy(
            () ->
                migrated
                    .service()
                    .associateDraftAsset(
                        game.getTenantId(), retainedVersionId, retainedSource.getId(), "logo"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("complete canonical owner identity");
    assertThat(mappingCount(migrated.dsl(), game.getTenantId(), retainedVersionId)).isZero();
    assertThat(versionXmin(migrated.dsl(), retainedVersionId)).isEqualTo(retainedXminBefore);
  }

  @org.junit.jupiter.api.Test
  void freezesOnlyExactMappedBytesAndRetriesFromCommittedSnapshotAfterNewAssetAndServiceRestart() {
    Fixture fixture = fixture(V37);
    Game game = saveGame(fixture, "snapshot-retry-owner");
    Version version = saveVersion(fixture, game, 1);
    byte[] selectedBytes = bytes("mapped bytes survive retry");
    GameAsset selected =
        saveAsset(fixture, game.getTenantId(), "logo-雪.png", "image/png", selectedBytes);
    String supplementaryUsageKey = new String(Character.toChars(0x10000)) + "-asset.bin";
    String privateUseUsageKey = "\uE000-asset.bin";
    GameAsset supplementary =
        saveAsset(
            fixture,
            game.getTenantId(),
            supplementaryUsageKey,
            "application/octet-stream",
            bytes("supplementary"));
    GameAsset privateUse =
        saveAsset(
            fixture,
            game.getTenantId(),
            privateUseUsageKey,
            "application/octet-stream",
            bytes("private use"));
    saveAsset(fixture, game.getTenantId(), "unmapped.css", "text/css", bytes("not selected"));

    fixture
        .service()
        .associateDraftAsset(game.getTenantId(), version.getId(), selected.getId(), "logo");
    fixture
        .service()
        .associateDraftAsset(game.getTenantId(), version.getId(), selected.getId(), "logo");
    fixture
        .service()
        .associateDraftAsset(game.getTenantId(), version.getId(), supplementary.getId(), "binary");
    fixture
        .service()
        .associateDraftAsset(game.getTenantId(), version.getId(), privateUse.getId(), "binary");
    ExportSnapshot first = fixture.service().freezeOrReadSnapshot(game.getTenantId(), 1);
    assertThat(first.tenantId()).isEqualTo(game.getTenantId());
    assertThat(first.versionId()).isEqualTo(version.getId());
    assertThat(first.versionNumber()).isEqualTo(1);
    assertThat(first.capturedVersionStateEpoch()).isEqualTo(1L);
    assertThat(first.canonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(first.canonicalVersionId()).isEqualTo(version.getCanonicalVersionId());
    assertThat(first.items()).hasSize(3);
    assertThat(first.items().get(0).usageKey()).isEqualTo("logo-雪.png");
    assertThat(first.items().get(0).assetId()).isEqualTo(selected.getId());
    assertThat(first.items().get(0).contentType()).isEqualTo("image/png");
    assertThat(first.items().get(0).contentDigest()).isEqualTo(sha256(selectedBytes));
    assertThat(first.items().get(0).bytes()).isEqualTo(selectedBytes);
    assertThat(first.items().get(1).usageKey()).isEqualTo(privateUseUsageKey);
    assertThat(first.items().get(2).usageKey()).isEqualTo(supplementaryUsageKey);
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT item_count FROM version_asset_export_snapshot WHERE tenant_id = ? AND version_id = ?",
                            game.getTenantId(),
                            version.getId()),
                    "Expected frozen snapshot header row")
                .get("item_count", Integer.class))
        .isEqualTo(3);

    saveAsset(
        fixture, game.getTenantId(), "added-after-freeze.svg", "image/svg+xml", bytes("late"));
    fixture
        .dsl()
        .execute(
            "UPDATE version SET version_state = 'RETIRED', version_state_epoch = 2 "
                + "WHERE tenant_id = ? AND id = ?",
            game.getTenantId(),
            version.getId());
    Fixture restarted = fixtureFromSchema(fixture.schema(), fixture.dataSource());
    ExportSnapshot retried = restarted.service().freezeOrReadSnapshot(game.getTenantId(), 1);
    assertSameSelection(first, retried);
    assertSameSelection(first, restarted.service().readFrozenSnapshot(game.getTenantId(), 1));
    assertThatThrownBy(
            () ->
                restarted
                    .service()
                    .associateDraftAsset(
                        game.getTenantId(), version.getId(), selected.getId(), "different"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Draft Version");
  }

  @org.junit.jupiter.api.Test
  void durableHeaderDistinguishesProvedEmptySelectionFromMissingSnapshot() {
    Fixture fixture = fixture(V37);
    Game game = saveGame(fixture, "empty-snapshot-owner");
    saveVersion(fixture, game, 1);

    assertThatThrownBy(() -> fixture.service().readFrozenSnapshot(game.getTenantId(), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No durable frozen");

    ExportSnapshot empty = fixture.service().freezeOrReadSnapshot(game.getTenantId(), 1);
    assertThat(empty.items()).isEmpty();
    assertThat(fixture.service().readFrozenSnapshot(game.getTenantId(), 1).items()).isEmpty();
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT item_count FROM version_asset_export_snapshot WHERE tenant_id = ? AND version_id = ?",
                            game.getTenantId(),
                            empty.versionId()),
                    "Expected proved empty snapshot header row")
                .get("item_count", Integer.class))
        .isZero();

    int versionNumber = 2;
    for (String state : List.of("PUBLISHED", "ACTIVE", "RETIRED")) {
      int retainedVersionNumber = versionNumber;
      Version retainedLifecycleVersion = saveVersion(fixture, game, retainedVersionNumber);
      fixture
          .dsl()
          .execute(
              "UPDATE version SET version_state = ?, version_state_epoch = 2 "
                  + "WHERE tenant_id = ? AND id = ?",
              state,
              game.getTenantId(),
              retainedLifecycleVersion.getId());
      assertThatThrownBy(
              () -> fixture.service().readFrozenSnapshot(game.getTenantId(), retainedVersionNumber))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No durable frozen");
      assertThatThrownBy(
              () ->
                  fixture.service().freezeOrReadSnapshot(game.getTenantId(), retainedVersionNumber))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Draft Version");
      versionNumber++;
    }
    int unexpectedVersionNumber = versionNumber;
    Version unexpectedState = saveVersion(fixture, game, unexpectedVersionNumber);
    fixture
        .dsl()
        .execute(
            "UPDATE version SET version_state = 'UNEXPECTED' WHERE tenant_id = ? AND id = ?",
            game.getTenantId(),
            unexpectedState.getId());
    assertThatThrownBy(
            () ->
                fixture.service().freezeOrReadSnapshot(game.getTenantId(), unexpectedVersionNumber))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unexpected lifecycle state");
  }

  @org.junit.jupiter.api.Test
  void deniesTenantIdentityAndUsageCollisionsWithoutMutatingOwnerRows() {
    Fixture fixture = fixture(V37);
    Game owner = saveGame(fixture, "association-owner");
    Game other = saveGame(fixture, "association-other");
    Version version = saveVersion(fixture, owner, 1);
    Version otherVersion = saveVersion(fixture, other, 1);
    GameAsset first =
        saveAsset(
            fixture, owner.getTenantId(), "same-name.bin", "application/octet-stream", bytes("a"));
    GameAsset duplicateKey =
        saveAsset(
            fixture, owner.getTenantId(), "same-name.bin", "application/octet-stream", bytes("b"));
    GameAsset foreignAsset =
        saveAsset(
            fixture, other.getTenantId(), "foreign.bin", "application/octet-stream", bytes("f"));

    fixture
        .service()
        .associateDraftAsset(owner.getTenantId(), version.getId(), first.getId(), null);
    String versionXminBefore = versionXmin(fixture.dsl(), version.getId());
    String ownerXminBefore = gameXmin(fixture.dsl(), owner.getId());
    String sourceXminBefore = assetXmin(fixture.dsl(), first.getId());
    List<ThrowingCallable> deniedWrites =
        List.of(
            () ->
                fixture
                    .service()
                    .associateDraftAsset(
                        owner.getTenantId(), version.getId(), duplicateKey.getId(), null),
            () ->
                fixture
                    .service()
                    .associateDraftAsset(
                        owner.getTenantId(), version.getId(), foreignAsset.getId(), null),
            () ->
                fixture
                    .service()
                    .associateDraftAsset(other.getTenantId(), version.getId(), first.getId(), null),
            () ->
                fixture
                    .service()
                    .associateDraftAsset(
                        owner.getTenantId(), version.getId(), Long.MAX_VALUE, null),
            () ->
                fixture
                    .service()
                    .associateDraftAsset(
                        owner.getTenantId(), otherVersion.getId(), first.getId(), null));
    for (ThrowingCallable deniedWrite : deniedWrites) {
      assertThatThrownBy(deniedWrite).isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, "
                            + "canonical_version_id, canonical_tenant_id, identity_source_game_row_id, "
                            + "identity_source_game_tenant_key, identity_source_provenance_kind) "
                            + "VALUES (?, 5, 'DRAFT', 1, ?, ?, ?, ?, 'NEW_GAME_ROW')",
                        owner.getTenantId(),
                        UUID.randomUUID(),
                        owner.getCanonicalTenantId(),
                        other.getId(),
                        other.getTenantId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("exact game row");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO version_asset "
                            + "(tenant_id, version_id, asset_id, usage_key, usage_type) VALUES (?, ?, ?, ?, ?)",
                        owner.getTenantId(),
                        version.getId(),
                        foreignAsset.getId(),
                        "foreign.bin",
                        "binary"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO version_asset "
                            + "(tenant_id, version_id, asset_id, usage_key, usage_type) VALUES (?, ?, ?, ?, ?)",
                        owner.getTenantId(),
                        otherVersion.getId(),
                        first.getId(),
                        "same-name.bin",
                        "binary"))
        .isInstanceOf(DataAccessException.class);

    assertThat(mappingCount(fixture.dsl(), owner.getTenantId(), version.getId())).isEqualTo(1);
    assertThat(versionXmin(fixture.dsl(), version.getId())).isEqualTo(versionXminBefore);
    assertThat(gameXmin(fixture.dsl(), owner.getId())).isEqualTo(ownerXminBefore);
    assertThat(assetXmin(fixture.dsl(), first.getId())).isEqualTo(sourceXminBefore);
    assertThat(mappingCount(fixture.dsl(), other.getTenantId(), otherVersion.getId())).isZero();
    assertThatThrownBy(() -> fixture.service().readFrozenSnapshot(other.getTenantId(), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No durable frozen");
  }

  @org.junit.jupiter.api.Test
  void concurrentDraftMappingAndFirstFreezeSerializeOnTheExactVersionRow() throws Exception {
    Fixture fixture = fixture(V37);
    Game game = saveGame(fixture, "mapping-freeze-race");
    Version version = saveVersion(fixture, game, 1);
    GameAsset asset =
        saveAsset(
            fixture, game.getTenantId(), "race.bin", "application/octet-stream", bytes("race"));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> mapping =
          executor.submit(
              () -> {
                await(start);
                fixture
                    .service()
                    .associateDraftAsset(
                        game.getTenantId(), version.getId(), asset.getId(), "race");
                return null;
              });
      Future<ExportSnapshot> freeze =
          executor.submit(
              () -> {
                await(start);
                return fixture.service().freezeOrReadSnapshot(game.getTenantId(), 1);
              });
      start.countDown();
      ExportSnapshot snapshot = freeze.get(20, TimeUnit.SECONDS);
      AtomicBoolean mappingCommitted = new AtomicBoolean(true);
      try {
        mapping.get(20, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException exception) {
        if (!(exception.getCause() instanceof IllegalStateException)) {
          throw exception;
        }
        mappingCommitted.set(false);
      }
      assertThat(snapshot.items()).hasSize(mappingCommitted.get() ? 1 : 0);
      assertThat(mappingCount(fixture.dsl(), game.getTenantId(), version.getId()))
          .isEqualTo(mappingCommitted.get() ? 1 : 0);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @org.junit.jupiter.api.Test
  void frozenSnapshotAndItsRepairSourcesRejectMutationAndSnapshotBuildRollsBackAtomically() {
    Fixture fixture = fixture(V37);
    Game game = saveGame(fixture, "immutable-snapshot-owner");
    Version version = saveVersion(fixture, game, 1);
    byte[] payload = bytes("retained exact bytes");
    GameAsset asset =
        saveAsset(
            fixture, game.getTenantId(), "immutable.bin", "application/octet-stream", payload);
    fixture
        .service()
        .associateDraftAsset(game.getTenantId(), version.getId(), asset.getId(), "binary");

    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(
                        status -> {
                          fixture
                              .dsl()
                              .execute(
                                  "INSERT INTO version_asset_export_item "
                                      + "(tenant_id, version_id, usage_key, asset_id, content_type, content_hash, byte_size) "
                                      + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                                  game.getTenantId(),
                                  version.getId(),
                                  "immutable.bin",
                                  asset.getId(),
                                  "application/octet-stream",
                                  sha256(payload),
                                  (long) payload.length);
                          fixture
                              .dsl()
                              .execute(
                                  "INSERT INTO version_asset_export_snapshot "
                                      + "(tenant_id, version_id, canonical_tenant_id, canonical_version_id, version_number, "
                                      + "captured_version_state_epoch, item_count) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                  game.getTenantId(),
                                  version.getId(),
                                  game.getCanonicalTenantId(),
                                  version.getCanonicalVersionId(),
                                  version.getVersionNumber(),
                                  1L,
                                  0);
                          return null;
                        }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("item count");
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM version_asset_export_snapshot WHERE tenant_id = ? AND version_id = ?",
                    game.getTenantId(),
                    version.getId()))
        .isNull();
    assertThat(fixture.dsl().fetchCount(DSL.table("version_asset_export_item"))).isZero();

    ExportSnapshot committed = fixture.service().freezeOrReadSnapshot(game.getTenantId(), 1);
    assertThat(committed.items()).hasSize(1);
    assertThatThrownBy(
            () ->
                fixture
                    .service()
                    .associateDraftAsset(
                        game.getTenantId(), version.getId(), asset.getId(), "binary"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frozen by the durable export snapshot");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_assets SET data = ? WHERE tenant_id = ? AND id = ?",
                        bytes("changed"),
                        game.getTenantId(),
                        asset.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_assets SET file_name = ? WHERE tenant_id = ? AND id = ?",
                        "renamed.bin",
                        game.getTenantId(),
                        asset.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_assets SET content_type = ? WHERE tenant_id = ? AND id = ?",
                        "application/x-other",
                        game.getTenantId(),
                        asset.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_assets SET tenant_id = ? WHERE tenant_id = ? AND id = ?",
                        "another-tenant",
                        game.getTenantId(),
                        asset.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM game_assets WHERE tenant_id = ? AND id = ?",
                        game.getTenantId(),
                        asset.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE version_asset_export_item SET content_hash = ? "
                            + "WHERE tenant_id = ? AND version_id = ?",
                        sha256(bytes("changed")),
                        game.getTenantId(),
                        version.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM version_asset_export_snapshot WHERE tenant_id = ? AND version_id = ?",
                        game.getTenantId(),
                        version.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE version_asset_export_snapshot SET item_count = 0 "
                            + "WHERE tenant_id = ? AND version_id = ?",
                        game.getTenantId(),
                        version.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE version_asset SET usage_type = 'changed' "
                            + "WHERE tenant_id = ? AND version_id = ?",
                        game.getTenantId(),
                        version.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("frozen");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM version_asset WHERE tenant_id = ? AND version_id = ?",
                        game.getTenantId(),
                        version.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("frozen");
    assertThat(fixture.service().readFrozenSnapshot(game.getTenantId(), 1).items().get(0).bytes())
        .isEqualTo(payload);
  }

  @org.junit.jupiter.api.Test
  void committedSnapshotSurvivesCallingTransactionRollback() {
    Fixture fixture = fixture(V37);
    Game game = saveGame(fixture, "requires-new-snapshot-owner");
    saveVersion(fixture, game, 1);
    TransactionTemplate outer = fixture.transactionTemplate();

    outer.execute(
        status -> {
          assertThat(fixture.service().freezeOrReadSnapshot(game.getTenantId(), 1).items())
              .isEmpty();
          status.setRollbackOnly();
          return null;
        });

    assertThat(fixture.service().readFrozenSnapshot(game.getTenantId(), 1).items()).isEmpty();
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM version_asset_export_snapshot WHERE tenant_id = ?",
                    game.getTenantId()))
        .isNotNull();
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "gd_vasp_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, target);
    return fixtureFromSchema(schema, dataSource);
  }

  private Fixture fixtureFromSchema(String schema, DriverManagerDataSource dataSource) {
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
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
    VersionAssetPublicationServiceImpl service =
        new VersionAssetPublicationServiceImpl(transactionManager, publicationRepository);
    return new Fixture(
        schema,
        dataSource,
        dsl,
        transactionTemplate,
        gameRepository,
        gameAssetRepository,
        versionRepository,
        service);
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
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private Game saveGame(Fixture fixture, String tenantId) {
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("Version asset publication owner");
    game.setDescription("Integration source row");
    return fixture.transactionTemplate().execute(status -> fixture.gameRepository().save(game));
  }

  private Version saveVersion(Fixture fixture, Game game, int versionNumber) {
    return fixture
        .transactionTemplate()
        .execute(
            status -> {
              // The V37 asset fixtures represent retained Draft rows without V52 source genesis.
              var inserted =
                  fixture
                      .dsl()
                      .fetchOne(
                          "INSERT INTO version (tenant_id, canonical_version_id, canonical_tenant_id, "
                              + "identity_source_game_row_id, identity_source_game_tenant_key, "
                              + "identity_source_provenance_kind, version_number, version_state, version_state_epoch, "
                              + "script_patch_version, base_version_id, is_script_only, notes, created_at, updated_at) "
                              + "SELECT g.tenant_id, ?, g.canonical_tenant_id, g.id, g.tenant_id, "
                              + "g.tenant_identity_provenance_kind, ?, 'DRAFT', 1, NULL, NULL, FALSE, "
                              + "'ISOLATED retained Version asset fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                              + "FROM game g WHERE g.id = ? RETURNING id",
                          UUID.randomUUID(),
                          versionNumber,
                          game.getId());
              if (inserted == null) {
                throw new IllegalStateException(
                    "ISOLATED retained Game Design owner row is absent");
              }
              return fixture
                  .versionRepository()
                  .findById(inserted.get("id", Long.class))
                  .orElseThrow();
            });
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

  private long insertRetainedVersion(DSLContext dsl, String tenantId) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, "
                    + "script_patch_version, base_version_id, is_script_only, notes) "
                    + "VALUES (?, 4, 'FAILED', 3, NULL, NULL, FALSE, 'retained history') RETURNING id",
                tenantId)
            .fetchOne(0, Long.class),
        "Expected inserted retained Version row");
  }

  private Map<String, Object> retainedVersionTuple(DSLContext dsl, long versionId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, tenant_id, version_number, version_state, version_state_epoch, "
                    + "script_patch_version, base_version_id, is_script_only, notes, created_at, updated_at "
                    + "FROM version WHERE id = ?",
                versionId),
            "Expected retained Version row")
        .intoMap();
  }

  private Map<String, Object> assetTuple(DSLContext dsl, long assetId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT id, tenant_id, file_name, content_type, created_at FROM game_assets WHERE id = ?",
                assetId),
            "Expected retained asset row")
        .intoMap();
  }

  private byte[] assetData(DSLContext dsl, long assetId) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT data FROM game_assets WHERE id = ?", assetId),
            "Expected retained asset data row")
        .get("data", byte[].class);
  }

  private String versionXmin(DSLContext dsl, long versionId) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT xmin::text AS xmin FROM version WHERE id = ?", versionId),
            "Expected retained Version xmin row")
        .get("xmin", String.class);
  }

  private String gameXmin(DSLContext dsl, long gameId) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT xmin::text AS xmin FROM game WHERE id = ?", gameId),
            "Expected retained Game xmin row")
        .get("xmin", String.class);
  }

  private String assetXmin(DSLContext dsl, long assetId) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT xmin::text AS xmin FROM game_assets WHERE id = ?", assetId),
            "Expected retained asset xmin row")
        .get("xmin", String.class);
  }

  private long mappingCount(DSLContext dsl, String tenantId, long versionId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT count(*) AS mapping_count FROM version_asset WHERE tenant_id = ? AND version_id = ?",
                tenantId,
                versionId),
            "Expected mapping count aggregate row")
        .get("mapping_count", Long.class);
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

  private static void assertSameSelection(ExportSnapshot expected, ExportSnapshot actual) {
    assertThat(actual.tenantId()).isEqualTo(expected.tenantId());
    assertThat(actual.versionId()).isEqualTo(expected.versionId());
    assertThat(actual.versionNumber()).isEqualTo(expected.versionNumber());
    assertThat(actual.capturedVersionStateEpoch()).isEqualTo(expected.capturedVersionStateEpoch());
    assertThat(actual.canonicalTenantId()).isEqualTo(expected.canonicalTenantId());
    assertThat(actual.canonicalVersionId()).isEqualTo(expected.canonicalVersionId());
    assertThat(actual.items()).hasSize(expected.items().size());
    for (int index = 0; index < expected.items().size(); index++) {
      AssetSelection expectedItem = expected.items().get(index);
      AssetSelection actualItem = actual.items().get(index);
      assertThat(actualItem.usageKey()).isEqualTo(expectedItem.usageKey());
      assertThat(actualItem.assetId()).isEqualTo(expectedItem.assetId());
      assertThat(actualItem.contentType()).isEqualTo(expectedItem.contentType());
      assertThat(actualItem.contentDigest()).isEqualTo(expectedItem.contentDigest());
      assertThat(actualItem.bytes()).isEqualTo(expectedItem.bytes());
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent publication test start signal timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent publication test was interrupted", exception);
    }
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transactionTemplate,
      GameRepository gameRepository,
      GameAssetRepository gameAssetRepository,
      VersionRepository versionRepository,
      VersionAssetPublicationServiceImpl service) {}
}
