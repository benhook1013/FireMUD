package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GameTenantIdentityRepositoryIntegrationTest {
  private static final String SERVICE_SCHEMA = "game_design_service";
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final org.jooq.Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<String> DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
  private static final org.jooq.Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final org.jooq.Field<String> PROVENANCE_KIND =
      DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class);
  private static final org.jooq.Field<Long> SOURCE_GAME_ID =
      DSL.field(DSL.name("tenant_identity_source_game_id"), Long.class);
  private static final org.jooq.Field<String> SOURCE_LEGACY_TENANT_ID =
      DSL.field(DSL.name("tenant_identity_source_legacy_tenant_id"), String.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migratesRetainedGamesAndIssuesImmutableProvenanceForNewGames() throws Exception {
    DriverManagerDataSource dataSource = dataSource();
    migrate(dataSource, MigrationVersion.fromVersion("29"));

    long retainedAlphaId;
    long retainedBetaId;
    try (Connection connection = dataSource.getConnection()) {
      setSearchPath(connection);
      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      retainedAlphaId = insertLegacyGame(dsl, "legacy-tenant-alpha", "Alpha", "alpha-data");
      retainedBetaId = insertLegacyGame(dsl, "legacy-tenant-beta", "Beta", "beta-data");
    }

    migrate(dataSource, null);

    UUID retainedAlphaCanonicalId;
    UUID retainedBetaCanonicalId;
    try (Connection connection = dataSource.getConnection()) {
      setSearchPath(connection);
      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      var retainedRows =
          dsl.select(
                  ID,
                  TENANT_ID,
                  NAME,
                  DESCRIPTION,
                  CANONICAL_TENANT_ID,
                  PROVENANCE_KIND,
                  SOURCE_GAME_ID,
                  SOURCE_LEGACY_TENANT_ID)
              .from(GAME)
              .orderBy(ID.asc())
              .fetch();

      assertThat(retainedRows).hasSize(2);
      assertThat(retainedRows)
          .extracting(row -> row.get(TENANT_ID))
          .containsExactly("legacy-tenant-alpha", "legacy-tenant-beta");
      assertThat(retainedRows).extracting(row -> row.get(NAME)).containsExactly("Alpha", "Beta");
      assertThat(retainedRows)
          .extracting(row -> row.get(DESCRIPTION))
          .containsExactly("alpha-data", "beta-data");
      assertThat(retainedRows)
          .extracting(row -> row.get(PROVENANCE_KIND))
          .containsOnly("RETAINED_GAME_V30");
      assertThat(retainedRows)
          .extracting(row -> row.get(SOURCE_GAME_ID))
          .containsExactly(retainedAlphaId, retainedBetaId);
      assertThat(retainedRows)
          .extracting(row -> row.get(SOURCE_LEGACY_TENANT_ID))
          .containsExactly("legacy-tenant-alpha", "legacy-tenant-beta");

      retainedAlphaCanonicalId = retainedRows.get(0).get(CANONICAL_TENANT_ID);
      retainedBetaCanonicalId = retainedRows.get(1).get(CANONICAL_TENANT_ID);
      assertThat(retainedAlphaCanonicalId).isNotNull().isNotEqualTo(retainedBetaCanonicalId);

      migrate(dataSource, null);
      assertThat(
              dsl.select(CANONICAL_TENANT_ID)
                  .from(GAME)
                  .where(ID.eq(retainedAlphaId))
                  .fetchOne(CANONICAL_TENANT_ID))
          .isEqualTo(retainedAlphaCanonicalId);
      assertThat(
              dsl.select(CANONICAL_TENANT_ID)
                  .from(GAME)
                  .where(ID.eq(retainedBetaId))
                  .fetchOne(CANONICAL_TENANT_ID))
          .isEqualTo(retainedBetaCanonicalId);

      GameRepository repository = new GameRepository(dsl);
      assertThat(repository.findTenantIdentityByLegacyTenantId("legacy-tenant-alpha"))
          .contains(
              new GameTenantIdentity(
                  retainedAlphaCanonicalId,
                  GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                  retainedAlphaId,
                  "legacy-tenant-alpha"));
      Game newGame = new Game();
      newGame.setTenantId("legacy-tenant-new");
      newGame.setName("New");
      newGame.setDescription("new-data");
      Game saved = repository.save(newGame);

      assertThat(saved.getCanonicalTenantId())
          .isNotNull()
          .isNotEqualTo(retainedAlphaCanonicalId)
          .isNotEqualTo(retainedBetaCanonicalId);
      assertThat(repository.findByTenantId("legacy-tenant-new").getCanonicalTenantId())
          .isEqualTo(saved.getCanonicalTenantId());
      assertThat(repository.findTenantIdentityByLegacyTenantId("legacy-tenant-new"))
          .contains(
              new GameTenantIdentity(
                  saved.getCanonicalTenantId(),
                  GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                  saved.getId(),
                  "legacy-tenant-new"));
      assertThat(repository.findTenantIdentityByLegacyTenantId("unknown-legacy-tenant")).isEmpty();

      Game existingGame = repository.findByTenantId("legacy-tenant-new");
      existingGame.setDescription("updated-data");
      Game updatedGame = repository.save(existingGame);
      assertThat(updatedGame.getCanonicalTenantId()).isEqualTo(saved.getCanonicalTenantId());
      assertThat(repository.findTenantIdentityByLegacyTenantId("legacy-tenant-new"))
          .contains(
              new GameTenantIdentity(
                  saved.getCanonicalTenantId(),
                  GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                  saved.getId(),
                  "legacy-tenant-new"));

      Game mismatchedUpdate = new Game();
      mismatchedUpdate.setId(saved.getId());
      mismatchedUpdate.setTenantId("different-legacy-tenant");
      mismatchedUpdate.setName("Must not apply");
      mismatchedUpdate.setDescription("must-not-apply");
      assertThatThrownBy(() -> repository.save(mismatchedUpdate))
          .isInstanceOf(IllegalStateException.class);
      assertThat(repository.findByTenantId("legacy-tenant-new").getName()).isEqualTo("New");
      assertThat(repository.findByTenantId("legacy-tenant-new").getDescription())
          .isEqualTo("updated-data");

      Game mismatchedCanonicalUpdate = new Game();
      mismatchedCanonicalUpdate.setId(saved.getId());
      mismatchedCanonicalUpdate.setTenantId(saved.getTenantId());
      mismatchedCanonicalUpdate.setCanonicalTenantId(UUID.randomUUID());
      mismatchedCanonicalUpdate.setName("Must not apply");
      mismatchedCanonicalUpdate.setDescription("must-not-apply");
      assertThatThrownBy(() -> repository.save(mismatchedCanonicalUpdate))
          .isInstanceOf(IllegalStateException.class);
      assertThat(repository.findByTenantId("legacy-tenant-new").getDescription())
          .isEqualTo("updated-data");

      assertThatThrownBy(
              () ->
                  dsl.update(GAME)
                      .set(CANONICAL_TENANT_ID, UUID.randomUUID())
                      .where(ID.eq(retainedAlphaId))
                      .execute())
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  dsl.update(GAME)
                      .set(SOURCE_GAME_ID, SOURCE_GAME_ID.plus(1L))
                      .where(ID.eq(retainedAlphaId))
                      .execute())
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  dsl.update(GAME)
                      .set(TENANT_ID, "legacy-tenant-renamed")
                      .where(ID.eq(retainedAlphaId))
                      .execute())
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  dsl.insertInto(GAME)
                      .set(TENANT_ID, "legacy-tenant-duplicate-canonical")
                      .set(CANONICAL_TENANT_ID, retainedAlphaCanonicalId)
                      .set(NAME, "Duplicate")
                      .execute())
          .isInstanceOf(DataAccessException.class);

      dsl.insertInto(GAME)
          .set(TENANT_ID, "legacy-tenant-database-issued")
          .set(NAME, "Database issued")
          .execute();
      GameTenantIdentity databaseIssuedIdentity =
          repository
              .findTenantIdentityByLegacyTenantId("legacy-tenant-database-issued")
              .orElseThrow();
      assertThat(databaseIssuedIdentity.canonicalTenantId())
          .isNotNull()
          .isNotEqualTo(retainedAlphaCanonicalId)
          .isNotEqualTo(retainedBetaCanonicalId)
          .isNotEqualTo(saved.getCanonicalTenantId());
      assertThat(databaseIssuedIdentity.provenanceKind())
          .isEqualTo(GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW);
      assertThat(databaseIssuedIdentity.sourceLegacyTenantId())
          .isEqualTo("legacy-tenant-database-issued");
      assertThat(dsl.fetchCount(GAME)).isEqualTo(4);
    }
  }

  private DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private void migrate(DriverManagerDataSource dataSource, MigrationVersion target) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(SERVICE_SCHEMA)
            .defaultSchema(SERVICE_SCHEMA)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", SERVICE_SCHEMA))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private void setSearchPath(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO game_design_service");
    }
  }

  private long insertLegacyGame(
      DSLContext dsl, String legacyTenantId, String name, String description) {
    return Objects.requireNonNull(
            dsl.insertInto(GAME)
                .set(TENANT_ID, legacyTenantId)
                .set(NAME, name)
                .set(DESCRIPTION, description)
                .returning(ID)
                .fetchOne(ID))
        .longValue();
  }
}
