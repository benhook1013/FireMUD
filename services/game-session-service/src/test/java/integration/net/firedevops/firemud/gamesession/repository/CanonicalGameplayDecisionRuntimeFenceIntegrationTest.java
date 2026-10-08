package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof that legacy runtime mutations execute the V34 typed-identity fence. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayDecisionRuntimeFenceIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final long LEGACY_TENANT_ID = 41L;
  private static final long LEGACY_INSTANCE_ID = 7L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void legacyRuntimeMutationAndDeletionEvaluateTheTypedCanonicalTenantFence() {
    String schema = "gs_runtime_fence_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();

      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var schemaReadback = dsl.fetchOne("SELECT current_schema() AS actual_schema");
      assertThat(schemaReadback.get("actual_schema", String.class)).isEqualTo(schema);
      var triggerReadback =
          dsl.fetchOne(
              "SELECT EXISTS (SELECT 1 FROM pg_trigger trigger_row"
                  + " JOIN pg_class relation ON relation.oid = trigger_row.tgrelid"
                  + " JOIN pg_namespace trigger_schema ON trigger_schema.oid = relation.relnamespace"
                  + " WHERE trigger_schema.nspname = ? AND relation.relname = ?"
                  + " AND trigger_row.tgname = ?) AS trigger_exists",
              schema,
              "game_instances",
              "game_instances_lease_bound_binding_runtime_fence");
      assertThat(triggerReadback.get("trigger_exists", Boolean.class)).isTrue();

      // This historical numeric-tenant row has no canonical launch association or candidate.
      // V34 must still evaluate its candidate query without comparing UUID tenant IDs to bigint.
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id,"
              + " status, row_version, game_instance_uuid)"
              + " VALUES (?, ?, 'legacy-runtime', 99, 'RUNNING', 0, ?)",
          LEGACY_INSTANCE_ID,
          LEGACY_TENANT_ID,
          UUID.randomUUID());

      assertThat(
              dsl.execute(
                  "UPDATE game_instances SET status = 'STOPPED'"
                      + " WHERE tenant_id = ? AND id = ?",
                  LEGACY_TENANT_ID,
                  LEGACY_INSTANCE_ID))
          .isEqualTo(1);
      var statusReadback =
          dsl.fetchOne(
              "SELECT status AS actual_status FROM game_instances"
                  + " WHERE tenant_id = ? AND id = ?",
              LEGACY_TENANT_ID,
              LEGACY_INSTANCE_ID);
      assertThat(statusReadback.get("actual_status", String.class)).isEqualTo("STOPPED");
      assertThat(
              dsl.execute(
                  "DELETE FROM game_instances WHERE tenant_id = ? AND id = ?",
                  LEGACY_TENANT_ID,
                  LEGACY_INSTANCE_ID))
          .isEqualTo(1);
    } finally {
      dropSchema(schema);
    }
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose runtime-fence test schema", failure);
    }
  }
}
