package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GameTenantIdentityRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final org.jooq.Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final org.jooq.Field<String> PROVENANCE_KIND =
      DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class);
  private static final org.jooq.Field<Long> SOURCE_GAME_ID =
      DSL.field(DSL.name("tenant_identity_source_game_id"), Long.class);
  private static final org.jooq.Field<String> SOURCE_LEGACY_TENANT_ID =
      DSL.field(DSL.name("tenant_identity_source_legacy_tenant_id"), String.class);

  private static final GameDesignPostgresIntegrationFixture postgres =
      new GameDesignPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void migratesOnlyExactOwnerKeysAndReadsImmutableGameRowProvenance() throws Exception {
    String serviceSchema = "game_design_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource();
    migrate(dataSource, MigrationVersion.fromVersion("28"), serviceSchema);

    long retainedGameId;
    long quarantinedGameId;
    try (Connection connection = dataSource.getConnection()) {
      setSearchPath(connection, serviceSchema);
      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      retainedGameId = insertLegacyGame(dsl, "legacy-owner-alpha", "Alpha");
      quarantinedGameId = insertLegacyGame(dsl, "   ", "Unproven");
    }

    migrate(dataSource, null, serviceSchema);

    try (Connection connection = dataSource.getConnection()) {
      setSearchPath(connection, serviceSchema);
      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      GameRepository repository = new GameRepository(dsl);
      UUID retainedTenantId =
          dsl.select(CANONICAL_TENANT_ID)
              .from(GAME)
              .where(ID.eq(retainedGameId))
              .fetchOne(CANONICAL_TENANT_ID);

      assertThat(retainedTenantId).isNotNull();
      assertThat(repository.findRuntimeTenantIdentityByCanonicalTenantId(retainedTenantId))
          .contains(
              new GameTenantIdentity(
                  retainedTenantId,
                  GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V29,
                  retainedGameId,
                  "legacy-owner-alpha"));
      assertThat(repository.findRuntimeTenantIdentityByTenantKey("legacy-owner-alpha"))
          .contains(
              new GameTenantIdentity(
                  retainedTenantId,
                  GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V29,
                  retainedGameId,
                  "legacy-owner-alpha"));
      assertThat(repository.findRuntimeTenantIdentityByTenantKey("missing-owner-key")).isEmpty();
      assertThat(
              dsl.select(
                      CANONICAL_TENANT_ID, PROVENANCE_KIND, SOURCE_GAME_ID, SOURCE_LEGACY_TENANT_ID)
                  .from(GAME)
                  .where(ID.eq(quarantinedGameId))
                  .fetchOne())
          .satisfies(
              row -> {
                assertThat(row.get(CANONICAL_TENANT_ID)).isNull();
                assertThat(row.get(PROVENANCE_KIND)).isNull();
                assertThat(row.get(SOURCE_GAME_ID)).isNull();
                assertThat(row.get(SOURCE_LEGACY_TENANT_ID)).isNull();
              });
      assertThat(repository.findRuntimeTenantIdentityByCanonicalTenantId(UUID.randomUUID()))
          .isEmpty();

      Game newGame = new Game();
      newGame.setTenantId("new-owner-key");
      newGame.setName("New");
      Game saved = repository.save(newGame);
      assertThat(saved.getCanonicalTenantId()).isNotNull();
      assertThat(saved.getId()).isPositive();
      assertThat(
              repository.findRuntimeTenantIdentityByCanonicalTenantId(saved.getCanonicalTenantId()))
          .contains(
              new GameTenantIdentity(
                  saved.getCanonicalTenantId(),
                  GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                  saved.getId(),
                  "new-owner-key"));
      assertThat(repository.findRuntimeTenantIdentityByTenantKey("new-owner-key"))
          .contains(
              new GameTenantIdentity(
                  saved.getCanonicalTenantId(),
                  GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                  saved.getId(),
                  "new-owner-key"));

      Game forgedGame = new Game();
      forgedGame.setTenantId("forged-owner-key");
      forgedGame.setName("Forged");
      forgedGame.setCanonicalTenantId(UUID.randomUUID());
      assertThatThrownBy(() -> repository.save(forgedGame))
          .isInstanceOf(IllegalArgumentException.class);

      assertThatThrownBy(
              () ->
                  dsl.update(GAME)
                      .set(TENANT_ID, "renamed-owner-key")
                      .where(ID.eq(retainedGameId))
                      .execute())
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  dsl.update(GAME)
                      .set(SOURCE_GAME_ID, SOURCE_GAME_ID.plus(1L))
                      .where(ID.eq(retainedGameId))
                      .execute())
          .isInstanceOf(DataAccessException.class);
      assertThat(dsl.fetchCount(GAME)).isEqualTo(3);
    }
  }

  private DriverManagerDataSource dataSource() {
    return postgres.dataSource();
  }

  private void migrate(
      DriverManagerDataSource dataSource, MigrationVersion target, String serviceSchema) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(serviceSchema)
            .defaultSchema(serviceSchema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", serviceSchema))
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize());
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private void setSearchPath(Connection connection, String serviceSchema) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + serviceSchema);
    }
  }

  private long insertLegacyGame(DSLContext dsl, String tenantId, String name) {
    return Objects.requireNonNull(
            dsl.insertInto(GAME)
                .set(TENANT_ID, tenantId)
                .set(NAME, name)
                .returning(ID)
                .fetchOne(ID))
        .longValue();
  }
}
