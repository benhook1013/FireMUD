package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class VersionCanonicalIdentityIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final MigrationVersion V35 = MigrationVersion.fromVersion("35");
  private static final Table<?> VERSION = DSL.table(DSL.name("version"));
  private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final Field<String> TENANT_ID = DSL.field(DSL.name("tenant_id"), String.class);
  private static final Field<Integer> VERSION_NUMBER =
      DSL.field(DSL.name("version_number"), Integer.class);
  private static final Field<String> VERSION_STATE =
      DSL.field(DSL.name("version_state"), String.class);
  private static final Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);
  private static final Field<String> SCRIPT_PATCH_VERSION =
      DSL.field(DSL.name("script_patch_version"), String.class);
  private static final Field<Long> BASE_VERSION_ID =
      DSL.field(DSL.name("base_version_id"), Long.class);
  private static final Field<Boolean> IS_SCRIPT_ONLY =
      DSL.field(DSL.name("is_script_only"), Boolean.class);
  private static final Field<String> NOTES = DSL.field(DSL.name("notes"), String.class);
  private static final Field<java.sql.Timestamp> CREATED_AT =
      DSL.field(DSL.name("created_at"), java.sql.Timestamp.class);
  private static final Field<java.sql.Timestamp> UPDATED_AT =
      DSL.field(DSL.name("updated_at"), java.sql.Timestamp.class);
  private static final Field<UUID> CANONICAL_VERSION_ID =
      DSL.field(DSL.name("canonical_version_id"), UUID.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<Long> IDENTITY_SOURCE_GAME_ROW_ID =
      DSL.field(DSL.name("identity_source_game_row_id"), Long.class);
  private static final Field<String> IDENTITY_SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("identity_source_game_tenant_key"), String.class);
  private static final Field<String> IDENTITY_SOURCE_PROVENANCE_KIND =
      DSL.field(DSL.name("identity_source_provenance_kind"), String.class);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void v35_1PreservesRetainedVersionHistoryAndLeavesIdentityUnmapped() {
    Fixture fixture = fixture(V35);
    Game game = saveGame(fixture, "retained-version-source");
    Long retainedVersionId = insertRetainedVersion(fixture.dsl(), game.getTenantId());
    Map<String, Object> retainedFieldsBefore = retainedFields(fixture.dsl(), retainedVersionId);
    String retainedXminBefore = versionXmin(fixture.dsl(), retainedVersionId);

    migrate(fixture.dataSource(), fixture.schema(), null);

    assertThat(retainedFields(fixture.dsl(), retainedVersionId)).isEqualTo(retainedFieldsBefore);
    assertThat(versionXmin(fixture.dsl(), retainedVersionId)).isEqualTo(retainedXminBefore);
    assertThat(
            fixture
                .dsl()
                .select(CANONICAL_VERSION_ID)
                .from(VERSION)
                .where(ID.eq(retainedVersionId))
                .fetchOne(CANONICAL_VERSION_ID))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .select(CANONICAL_TENANT_ID)
                .from(VERSION)
                .where(ID.eq(retainedVersionId))
                .fetchOne(CANONICAL_TENANT_ID))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .select(IDENTITY_SOURCE_GAME_ROW_ID)
                .from(VERSION)
                .where(ID.eq(retainedVersionId))
                .fetchOne(IDENTITY_SOURCE_GAME_ROW_ID))
        .isNull();
    assertThat(
            fixture
                .versionRepository()
                .findByCanonicalTenantIdAndCanonicalVersionId(
                    game.getCanonicalTenantId(), UUID.randomUUID()))
        .isEmpty();

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(VERSION)
                    .set(CANONICAL_VERSION_ID, UUID.randomUUID())
                    .set(CANONICAL_TENANT_ID, game.getCanonicalTenantId())
                    .set(IDENTITY_SOURCE_GAME_ROW_ID, game.getId())
                    .set(IDENTITY_SOURCE_GAME_TENANT_KEY, game.getTenantId())
                    .set(IDENTITY_SOURCE_PROVENANCE_KIND, "NEW_GAME_ROW")
                    .where(ID.eq(retainedVersionId))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("retained Version rows cannot be assigned a canonical identity");
  }

  @Test
  void savePersistsAndReadsBackFreshCanonicalIdentityAcrossUpdate() {
    Fixture fixture = fixture(null);
    Game game = saveGame(fixture, "fresh-version-source");

    Version inserted =
        fixture
            .transactionTemplate()
            .execute(status -> fixture.versionRepository().save(version(game)));
    assertThat(inserted).isNotNull();
    assertThat(inserted.getCanonicalVersionId()).isNotNull().isNotEqualTo(NIL_UUID);
    assertThat(inserted.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(inserted.getIdentitySourceGameRowId()).isEqualTo(game.getId());
    assertThat(inserted.getIdentitySourceGameTenantKey()).isEqualTo(game.getTenantId());
    assertThat(inserted.getIdentitySourceProvenanceKind()).isEqualTo("NEW_GAME_ROW");

    Version exactRead =
        fixture
            .versionRepository()
            .findByCanonicalTenantIdAndCanonicalVersionId(
                game.getCanonicalTenantId(), inserted.getCanonicalVersionId())
            .orElseThrow();
    assertIdentityMatches(exactRead, game, inserted.getCanonicalVersionId());
    assertIdentityMatches(
        fixture.versionRepository().findById(inserted.getId()).orElseThrow(),
        game,
        inserted.getCanonicalVersionId());

    inserted.setNotes("updated without replacing owner identity");
    Version updated =
        fixture.transactionTemplate().execute(status -> fixture.versionRepository().save(inserted));
    assertThat(updated).isNotNull();
    assertThat(updated.getNotes()).isEqualTo("updated without replacing owner identity");
    assertIdentityMatches(updated, game, inserted.getCanonicalVersionId());
    assertIdentityMatches(
        fixture
            .versionRepository()
            .findByCanonicalTenantIdAndCanonicalVersionId(
                game.getCanonicalTenantId(), inserted.getCanonicalVersionId())
            .orElseThrow(),
        game,
        inserted.getCanonicalVersionId());
  }

  @Test
  void newVersionIdentityUsesExactRetainedGameSourceProvenance() {
    Fixture fixture = fixtureWithRetainedGame();
    Game retainedGame = fixture.gameRepository().findByTenantId("retained-version-game-source");
    assertThat(retainedGame).isNotNull();

    Version saved =
        fixture
            .transactionTemplate()
            .execute(status -> fixture.versionRepository().save(version(retainedGame)));

    assertThat(saved).isNotNull();
    assertThat(saved.getCanonicalVersionId()).isNotNull().isNotEqualTo(NIL_UUID);
    assertThat(saved.getIdentitySourceProvenanceKind()).isEqualTo("RETAINED_GAME_V30");
    assertIdentityMatches(
        fixture
            .versionRepository()
            .findByCanonicalTenantIdAndCanonicalVersionId(
                retainedGame.getCanonicalTenantId(), saved.getCanonicalVersionId())
            .orElseThrow(),
        retainedGame,
        saved.getCanonicalVersionId(),
        "RETAINED_GAME_V30");
  }

  @Test
  void canonicalLookupRejectsWrongTenantMissingAndNilIdsWithoutMutation() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "lookup-version-owner");
    Game other = saveGame(fixture, "lookup-version-other");
    Version saved =
        fixture
            .transactionTemplate()
            .execute(status -> fixture.versionRepository().save(version(owner)));
    assertThat(saved).isNotNull();
    String versionXminBefore = versionXmin(fixture.dsl(), saved.getId());
    String ownerXminBefore = gameXmin(fixture.dsl(), owner.getId());
    String otherXminBefore = gameXmin(fixture.dsl(), other.getId());

    assertThat(
            fixture
                .versionRepository()
                .findByCanonicalTenantIdAndCanonicalVersionId(
                    other.getCanonicalTenantId(), saved.getCanonicalVersionId()))
        .isEmpty();
    assertThat(
            fixture
                .versionRepository()
                .findByCanonicalTenantIdAndCanonicalVersionId(
                    owner.getCanonicalTenantId(), UUID.randomUUID()))
        .isEmpty();
    assertThat(
            fixture
                .versionRepository()
                .findByCanonicalTenantIdAndCanonicalVersionId(owner.getCanonicalTenantId(), null))
        .isEmpty();
    assertThat(
            fixture
                .versionRepository()
                .findByCanonicalTenantIdAndCanonicalVersionId(null, saved.getCanonicalVersionId()))
        .isEmpty();
    assertThat(
            fixture
                .versionRepository()
                .findByCanonicalTenantIdAndCanonicalVersionId(
                    NIL_UUID, saved.getCanonicalVersionId()))
        .isEmpty();
    assertThat(
            fixture
                .versionRepository()
                .findByCanonicalTenantIdAndCanonicalVersionId(
                    owner.getCanonicalTenantId(), NIL_UUID))
        .isEmpty();
    assertIdentityMatches(
        fixture
            .versionRepository()
            .findByCanonicalTenantIdAndCanonicalVersionId(
                owner.getCanonicalTenantId(), saved.getCanonicalVersionId())
            .orElseThrow(),
        owner,
        saved.getCanonicalVersionId());

    assertThat(versionXmin(fixture.dsl(), saved.getId())).isEqualTo(versionXminBefore);
    assertThat(gameXmin(fixture.dsl(), owner.getId())).isEqualTo(ownerXminBefore);
    assertThat(gameXmin(fixture.dsl(), other.getId())).isEqualTo(otherXminBefore);
  }

  @Test
  void databaseRejectsIdentityMutationNilIdentityAndMismatchedSource() {
    Fixture fixture = fixture(null);
    Game owner = saveGame(fixture, "mutation-version-owner");
    Game other = saveGame(fixture, "mutation-version-other");
    Version saved =
        fixture
            .transactionTemplate()
            .execute(status -> fixture.versionRepository().save(version(owner)));
    assertThat(saved).isNotNull();

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(VERSION)
                    .set(CANONICAL_VERSION_ID, UUID.randomUUID())
                    .where(ID.eq(saved.getId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("canonical Version identity and its tenant source are immutable");
    assertThatThrownBy(
            () -> fixture.dsl().deleteFrom(VERSION).where(ID.eq(saved.getId())).execute())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("canonical Version identity is immutable");

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO version (tenant_id, version_number, version_state, "
                            + "version_state_epoch, canonical_version_id, canonical_tenant_id, "
                            + "identity_source_game_row_id, identity_source_game_tenant_key, "
                            + "identity_source_provenance_kind) "
                            + "VALUES (?, 2, 'DRAFT', 1, ?, ?, ?, ?, ?)",
                        owner.getTenantId(),
                        NIL_UUID,
                        owner.getCanonicalTenantId(),
                        owner.getId(),
                        owner.getTenantId(),
                        "NEW_GAME_ROW"))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO version (tenant_id, version_number, version_state, "
                            + "version_state_epoch, canonical_version_id, canonical_tenant_id, "
                            + "identity_source_game_row_id, identity_source_game_tenant_key, "
                            + "identity_source_provenance_kind) "
                            + "VALUES (?, 3, 'DRAFT', 1, ?, ?, ?, ?, ?)",
                        owner.getTenantId(),
                        UUID.randomUUID(),
                        owner.getCanonicalTenantId(),
                        other.getId(),
                        other.getTenantId(),
                        "NEW_GAME_ROW"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining(
            "canonical Version identity source does not match its exact game row");

    Version wrongTenantAlias = version(owner);
    wrongTenantAlias.setTenantId(owner.getCanonicalTenantId().toString());
    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(status -> fixture.versionRepository().save(wrongTenantAlias)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no exact Game Design source row");
  }

  @Test
  void canonicalIdentityRejectsTruncateWhileUnmappedRetainedVersionRemainsDeletable() {
    Fixture mappedFixture = fixture(null);
    Game mappedGame = saveGame(mappedFixture, "truncate-version-owner");
    Version mapped =
        mappedFixture
            .transactionTemplate()
            .execute(status -> mappedFixture.versionRepository().save(version(mappedGame)));
    assertThat(mapped).isNotNull();

    assertThatThrownBy(() -> mappedFixture.dsl().execute("TRUNCATE TABLE version CASCADE"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("canonical Version identities cannot be truncated");

    Fixture retainedFixture = fixture(V35);
    Game retainedGame = saveGame(retainedFixture, "delete-retained-version-owner");
    Long retainedVersionId =
        insertRetainedVersion(retainedFixture.dsl(), retainedGame.getTenantId());
    migrate(retainedFixture.dataSource(), retainedFixture.schema(), null);
    assertThat(retainedFixture.dsl().deleteFrom(VERSION).where(ID.eq(retainedVersionId)).execute())
        .isEqualTo(1);
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "game_design_version_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, target);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    GameRepository gameRepository = new GameRepository(dsl);
    PostgresProperties postgresProperties = new PostgresProperties();
    postgresProperties.setSchema(schema);
    VersionRepository versionRepository = new VersionRepository(dsl, postgresProperties);
    return new Fixture(
        schema, dataSource, dsl, gameRepository, versionRepository, transactionTemplate);
  }

  private Fixture fixtureWithRetainedGame() {
    String schema =
        "game_design_retained_version_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, MigrationVersion.fromVersion("29"));
    DSLContext legacyDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    legacyDsl.execute(
        "INSERT INTO game (tenant_id, name, description) VALUES (?, ?, ?)",
        "retained-version-game-source",
        "Retained Version Source",
        "Source row predating canonical tenant identity");
    migrate(dataSource, schema, null);
    return fixtureFromMigratedSchema(schema, dataSource);
  }

  private Fixture fixtureFromMigratedSchema(String schema, DriverManagerDataSource dataSource) {
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    GameRepository gameRepository = new GameRepository(dsl);
    PostgresProperties postgresProperties = new PostgresProperties();
    postgresProperties.setSchema(schema);
    VersionRepository versionRepository = new VersionRepository(dsl, postgresProperties);
    return new Fixture(
        schema, dataSource, dsl, gameRepository, versionRepository, transactionTemplate);
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

  private Game saveGame(Fixture fixture, String tenantKey) {
    Game game = new Game();
    game.setTenantId(tenantKey);
    game.setName("Version Identity Owner");
    game.setDescription("Source row for canonical Version identity");
    return fixture.transactionTemplate().execute(status -> fixture.gameRepository().save(game));
  }

  private Long insertRetainedVersion(DSLContext dsl, String tenantId) {
    return dsl.resultQuery(
            "INSERT INTO version (tenant_id, version_number, version_state, version_state_epoch, "
                + "script_patch_version, base_version_id, is_script_only, notes) "
                + "VALUES (?, 4, 'FAILED', 3, NULL, NULL, FALSE, 'retained history') RETURNING id",
            tenantId)
        .fetchOne(0, Long.class);
  }

  private Map<String, Object> retainedFields(DSLContext dsl, Long versionId) {
    return dsl.select(
            ID,
            TENANT_ID,
            VERSION_NUMBER,
            VERSION_STATE,
            VERSION_STATE_EPOCH,
            SCRIPT_PATCH_VERSION,
            BASE_VERSION_ID,
            IS_SCRIPT_ONLY,
            NOTES,
            CREATED_AT,
            UPDATED_AT)
        .from(VERSION)
        .where(ID.eq(versionId))
        .fetchSingle()
        .intoMap();
  }

  private String versionXmin(DSLContext dsl, Long versionId) {
    return dsl.fetchOne("SELECT xmin::text AS xmin FROM version WHERE id = ?", versionId)
        .get("xmin", String.class);
  }

  private String gameXmin(DSLContext dsl, Long gameId) {
    return dsl.fetchOne("SELECT xmin::text AS xmin FROM game WHERE id = ?", gameId)
        .get("xmin", String.class);
  }

  private Version version(Game game) {
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setVersionNumber(1);
    version.setNotes("fresh version");
    return version;
  }

  private void assertIdentityMatches(Version version, Game game, UUID canonicalVersionId) {
    assertIdentityMatches(version, game, canonicalVersionId, "NEW_GAME_ROW");
  }

  private void assertIdentityMatches(
      Version version, Game game, UUID canonicalVersionId, String provenanceKind) {
    assertThat(version.getTenantId()).isEqualTo(game.getTenantId());
    assertThat(version.getCanonicalVersionId()).isEqualTo(canonicalVersionId);
    assertThat(version.getCanonicalTenantId()).isEqualTo(game.getCanonicalTenantId());
    assertThat(version.getIdentitySourceGameRowId()).isEqualTo(game.getId());
    assertThat(version.getIdentitySourceGameTenantKey()).isEqualTo(game.getTenantId());
    assertThat(version.getIdentitySourceProvenanceKind()).isEqualTo(provenanceKind);
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      GameRepository gameRepository,
      VersionRepository versionRepository,
      TransactionTemplate transactionTemplate) {}
}
