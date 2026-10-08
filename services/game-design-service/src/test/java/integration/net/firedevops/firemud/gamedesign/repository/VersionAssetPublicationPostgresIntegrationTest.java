package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Persistence proof for version-scoped asset selection. Fixtures create synthetic Game and Version
 * rows through Game Design repositories; those fixture values are test inputs, not authenticated
 * Account or Gameplay source authority.
 */
@Testcontainers(disabledWithoutDocker = true)
class VersionAssetPublicationPostgresIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final MigrationVersion V35 = MigrationVersion.fromVersion("35");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void exactTenantVersionMappingsExcludeUnmappedAssetsAndSnapshotSurvivesRepositoryRestart() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "a");
    Game otherOwner = saveGame(fixture, "b");
    Version selectedVersion = saveDraftVersion(fixture, owner, 1);
    Version siblingVersion = saveDraftVersion(fixture, owner, 2);
    Version foreignVersion = saveDraftVersion(fixture, otherOwner, 1);
    GameAsset selected = saveAsset(fixture, owner, "selected.png", "selected-bytes");
    GameAsset sibling = saveAsset(fixture, owner, "sibling.png", "sibling-bytes");
    saveAsset(fixture, owner, "unmapped.png", "unmapped-bytes");
    GameAsset foreign = saveAsset(fixture, otherOwner, "foreign.png", "foreign-bytes");

    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), foreignVersion.getId(), selected.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact tenant Version row was not found");
    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), selectedVersion.getId(), foreign.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact tenant game asset row was not found");
    assertThat(mappingCount(fixture, owner.getTenantId(), selectedVersion.getId())).isZero();
    associate(fixture, owner.getTenantId(), selectedVersion.getId(), selected.getId());
    associate(fixture, owner.getTenantId(), siblingVersion.getId(), sibling.getId());

    VersionAssetPublicationRepository repository = fixture.publicationRepository();
    VersionAssetPublicationRepository.ExportSnapshot frozen =
        inTransaction(
            fixture,
            () ->
                repository.freezeOrReadSnapshot(
                    owner.getTenantId(), selectedVersion.getVersionNumber()));

    assertThat(frozen.canonicalTenantId()).isEqualTo(owner.getCanonicalTenantId());
    assertThat(frozen.canonicalVersionId()).isEqualTo(selectedVersion.getCanonicalVersionId());
    assertThat(frozen.items())
        .extracting(VersionAssetPublicationRepository.AssetSelection::usageKey)
        .containsExactly("selected.png");
    assertThat(frozen.items().get(0).assetId()).isEqualTo(selected.getId());
    assertThat(frozen.items().get(0).bytes()).containsExactly(selected.getData());
    assertThat(frozen.items().get(0).contentDigest()).matches("sha256:[0-9a-f]{64}");

    VersionAssetPublicationRepository restartedRepository =
        new VersionAssetPublicationRepository(fixture.dsl());
    VersionAssetPublicationRepository.ExportSnapshot reloaded =
        restartedRepository.readFrozenSnapshot(
            owner.getTenantId(), selectedVersion.getVersionNumber());
    assertThat(reloaded.canonicalTenantId()).isEqualTo(frozen.canonicalTenantId());
    assertThat(reloaded.canonicalVersionId()).isEqualTo(frozen.canonicalVersionId());
    assertThat(reloaded.items()).hasSize(1);
    assertThat(reloaded.items().get(0).usageKey()).isEqualTo("selected.png");
    assertThat(reloaded.items().get(0).assetId()).isEqualTo(selected.getId());
    assertThat(reloaded.items().get(0).bytes()).containsExactly(selected.getData());
    assertThat(reloaded.items().get(0).contentDigest())
        .isEqualTo(frozen.items().get(0).contentDigest());

    VersionAssetPublicationRepository.ExportSnapshot retried =
        inTransaction(
            fixture,
            () ->
                restartedRepository.freezeOrReadSnapshot(
                    owner.getTenantId(), selectedVersion.getVersionNumber()));
    assertThat(retried.items()).hasSize(1);
    assertThat(retried.items().get(0).assetId()).isEqualTo(selected.getId());
    assertThat(retried.items().get(0).contentDigest())
        .isEqualTo(frozen.items().get(0).contentDigest());
  }

  @Test
  void retainedVersionWithoutCanonicalSourceCannotReceiveAssetMapping() {
    Fixture fixture = fixture(V35);
    Game owner = saveGame(fixture, "c");
    Long retainedVersionId = insertUnqualifiedRetainedVersion(fixture.dsl(), owner.getTenantId());
    migrate(fixture.dataSource(), fixture.schema(), null);
    GameAsset asset = saveAsset(fixture, owner, "retained.png", "retained-bytes");

    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), retainedVersionId, asset.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Version lacks a complete canonical owner identity");
    assertThat(mappingCount(fixture, owner.getTenantId(), retainedVersionId)).isZero();
  }

  @Test
  void changedSourceBytesInvalidateCommittedSnapshotReadback() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "d");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset asset = saveAsset(fixture, owner, "mutable-source.png", "content-original");
    associate(fixture, owner.getTenantId(), version.getId(), asset.getId());
    inTransaction(
        fixture,
        () -> fixture.publicationRepository().freezeOrReadSnapshot(owner.getTenantId(), 1));

    fixture
        .dsl()
        .execute(
            "UPDATE game_assets SET data = ? WHERE tenant_id = ? AND id = ?",
            "content-replaced".getBytes(StandardCharsets.UTF_8),
            owner.getTenantId(),
            asset.getId());

    assertThatThrownBy(
            () ->
                fixture
                    .publicationRepository()
                    .readFrozenSnapshot(owner.getTenantId(), version.getVersionNumber()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Frozen Version asset source bytes or mapping failed verification");
  }

  @Test
  void associationAfterSnapshotFreezeIsRejected() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "e");
    Version version = saveDraftVersion(fixture, owner, 1);
    GameAsset first = saveAsset(fixture, owner, "first.png", "first-bytes");
    GameAsset second = saveAsset(fixture, owner, "second.png", "second-bytes");
    associate(fixture, owner.getTenantId(), version.getId(), first.getId());
    inTransaction(
        fixture,
        () -> fixture.publicationRepository().freezeOrReadSnapshot(owner.getTenantId(), 1));

    assertThatThrownBy(
            () -> associate(fixture, owner.getTenantId(), version.getId(), second.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Version asset mappings are frozen by the durable export snapshot");
    assertThat(mappingCount(fixture, owner.getTenantId(), version.getId())).isEqualTo(1);
  }

  @Test
  void absentSnapshotIsNotReadAsAnEmptySelection() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "f");
    Version version = saveDraftVersion(fixture, owner, 1);

    assertThatThrownBy(
            () ->
                fixture
                    .publicationRepository()
                    .readFrozenSnapshot(owner.getTenantId(), version.getVersionNumber()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No durable frozen Version asset snapshot exists");
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "gd_asset_publication_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, target);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Fixture(
        schema,
        dataSource,
        dsl,
        transaction,
        new GameRepository(dsl),
        new VersionRepository(dsl),
        new GameAssetRepository(dsl),
        new VersionAssetPublicationRepository(dsl));
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
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

  private Game saveGame(Fixture fixture, String tenantPrefix) {
    Game game = new Game();
    game.setTenantId(tenantPrefix + "-" + UUID.randomUUID().toString().replace("-", ""));
    game.setName("Version asset publication fixture");
    game.setDescription("Synthetic Game Design source row for persistence proof");
    return Objects.requireNonNull(
        fixture.transaction().execute(status -> fixture.games().save(game)));
  }

  private Version saveDraftVersion(Fixture fixture, Game game, int versionNumber) {
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setVersionNumber(versionNumber);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("Synthetic version asset publication fixture");
    return Objects.requireNonNull(
        fixture.transaction().execute(status -> fixture.versions().save(version)));
  }

  private GameAsset saveAsset(Fixture fixture, Game game, String fileName, String contents) {
    GameAsset asset = new GameAsset();
    asset.setTenantId(game.getTenantId());
    asset.setFileName(fileName);
    asset.setContentType("image/png");
    asset.setData(contents.getBytes(StandardCharsets.UTF_8));
    return Objects.requireNonNull(
        fixture.transaction().execute(status -> fixture.assets().save(asset)));
  }

  private void associate(Fixture fixture, String tenantId, long versionId, long assetId) {
    fixture
        .transaction()
        .execute(
            status -> {
              fixture
                  .publicationRepository()
                  .associateDraftAsset(tenantId, versionId, assetId, null);
              return null;
            });
  }

  private <T> T inTransaction(Fixture fixture, java.util.function.Supplier<T> action) {
    return Objects.requireNonNull(fixture.transaction().execute(status -> action.get()));
  }

  private long mappingCount(Fixture fixture, String tenantId, long versionId) {
    Record countRecord =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) AS mapping_count FROM version_asset "
                        + "WHERE tenant_id = ? AND version_id = ?",
                    tenantId,
                    versionId));
    return Objects.requireNonNull(countRecord.get("mapping_count", Long.class));
  }

  private long insertUnqualifiedRetainedVersion(DSLContext dsl, String tenantId) {
    Record insertedVersion =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, "
                        + "script_patch_version, base_version_id, is_script_only, notes) "
                        + "VALUES (?, 9, 'FAILED', 3, NULL, NULL, FALSE, 'retained history') RETURNING id",
                    tenantId)
                .fetchSingle());
    return Objects.requireNonNull(insertedVersion.get("id", Long.class));
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction,
      GameRepository games,
      VersionRepository versions,
      GameAssetRepository assets,
      VersionAssetPublicationRepository publicationRepository) {}
}
