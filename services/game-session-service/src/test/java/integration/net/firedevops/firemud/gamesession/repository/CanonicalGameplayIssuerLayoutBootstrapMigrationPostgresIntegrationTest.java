package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL retention proof for adding the pending issuer-bootstrap intent schema. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerLayoutBootstrapMigrationPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final UUID CONTROLLED_ACCOUNT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final List<String> RETAINED_TABLES =
      List.of(
          "game_session_canonical_binding_inventory_clock",
          "game_session_canonical_binding_legacy_disposition",
          "game_session_canonical_account_coverage_control");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void addingIssuerBootstrapIntentTablesPreservesExistingRowsAndMetadataAcrossRestart()
      throws Exception {
    String schema = "gs_issuer_layout_v1030_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    try {
      Flyway v1029 = flyway(dataSource, schema, MigrationVersion.fromVersion("1029"));
      v1029.migrate();
      v1029.validate();
      assertThat(
              dsl.fetchSingle(
                      "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                  .get(0, String.class))
          .isEqualTo("1029");

      seedControlledRetainedAccountControl(dsl);
      Map<String, List<String>> rowsBefore = rowSnapshots(dsl);
      List<String> historyBefore = historySnapshot(dsl);
      Map<String, String> constraintsBefore = constraintSnapshot(dsl);
      Map<String, String> indexesBefore = indexSnapshot(dsl);
      assertThat(rowsBefore.get("game_session_canonical_binding_inventory_clock")).hasSize(1);
      assertThat(rowsBefore.get("game_session_canonical_binding_legacy_disposition")).hasSize(1);
      assertThat(rowsBefore.get("game_session_canonical_account_coverage_control")).hasSize(1);
      assertThat(
              dsl.fetchSingle(
                      "SELECT disposition_state FROM game_session_canonical_binding_legacy_disposition "
                          + "WHERE singleton_id = 1")
                  .get(0, String.class))
          .isEqualTo("REQUIRED");

      Flyway v1030 = flyway(dataSource, schema, MigrationVersion.fromVersion("1030"));
      v1030.migrate();
      v1030.validate();

      List<String> historyAfterMigration = historySnapshot(dsl);
      assertThat(historyAfterMigration).hasSize(historyBefore.size() + 1);
      assertThat(historyAfterMigration.subList(0, historyBefore.size()))
          .containsExactlyElementsOf(historyBefore);
      assertThat(
              dsl.fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1030'")
                  .get(0, String.class))
          .isEqualTo("V1030__canonical_issuer_layout_bootstrap_intent.sql");
      assertThat(rowSnapshots(dsl)).isEqualTo(rowsBefore);
      assertThat(constraintSnapshot(dsl)).containsAllEntriesOf(constraintsBefore);
      assertThat(indexSnapshot(dsl)).containsAllEntriesOf(indexesBefore);
      assertNewBootstrapTablesAreEmpty(dsl);

      Map<String, List<String>> rowsAfterMigration = rowSnapshots(dsl);
      Map<String, String> constraintsAfterMigration = constraintSnapshot(dsl);
      Map<String, String> indexesAfterMigration = indexSnapshot(dsl);
      Flyway restarted = flyway(dataSource, schema, MigrationVersion.fromVersion("1030"));
      restarted.migrate();
      restarted.validate();

      assertThat(historySnapshot(dsl)).containsExactlyElementsOf(historyAfterMigration);
      assertThat(rowSnapshots(dsl)).isEqualTo(rowsAfterMigration);
      assertThat(constraintSnapshot(dsl)).isEqualTo(constraintsAfterMigration);
      assertThat(indexSnapshot(dsl)).isEqualTo(indexesAfterMigration);
      assertNewBootstrapTablesAreEmpty(dsl);
    } finally {
      dropSchema(schema);
    }
  }

  private static void seedControlledRetainedAccountControl(DSLContext dsl) {
    // Controlled legal SQL row used only to prove V1030 retention. It is not owner-produced
    // no-active authority and does not represent an issuer bootstrap or gameplay admission.
    dsl.execute(
        "INSERT INTO game_session_canonical_account_coverage_control "
            + "(account_id, state, last_operation_fence) "
            + "VALUES (?, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0)",
        CONTROLLED_ACCOUNT_ID);
  }

  private static Map<String, List<String>> rowSnapshots(DSLContext dsl) {
    Map<String, List<String>> snapshots = new LinkedHashMap<>();
    for (String table : RETAINED_TABLES) {
      List<String> rows = new ArrayList<>();
      for (var row :
          dsl.fetch(
              "SELECT to_jsonb(retained_row)::text AS row_json FROM "
                  + table
                  + " retained_row ORDER BY to_jsonb(retained_row)::text")) {
        rows.add(row.get("row_json", String.class));
      }
      snapshots.put(table, List.copyOf(rows));
    }
    return Map.copyOf(snapshots);
  }

  private static List<String> historySnapshot(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(history_row)::text AS row_json FROM flyway_schema_history history_row "
                + "ORDER BY installed_rank")
        .getValues("row_json", String.class);
  }

  private static Map<String, String> constraintSnapshot(DSLContext dsl) {
    Map<String, String> constraints = new LinkedHashMap<>();
    for (var row :
        dsl.fetch(
            "SELECT relation.relname AS relation_name, constraint_row.conname AS constraint_name, "
                + "constraint_row.contype::text AS constraint_type, constraint_row.convalidated, "
                + "pg_get_constraintdef(constraint_row.oid, true) AS definition "
                + "FROM pg_constraint constraint_row "
                + "JOIN pg_class relation ON relation.oid = constraint_row.conrelid "
                + "WHERE constraint_row.connamespace = current_schema()::regnamespace "
                + "ORDER BY relation.relname, constraint_row.conname")) {
      String key =
          row.get("relation_name", String.class) + "." + row.get("constraint_name", String.class);
      String value =
          row.get("constraint_type", String.class)
              + "|"
              + row.get("convalidated", Boolean.class)
              + "|"
              + row.get("definition", String.class);
      constraints.put(key, value);
    }
    return Map.copyOf(constraints);
  }

  private static Map<String, String> indexSnapshot(DSLContext dsl) {
    Map<String, String> indexes = new LinkedHashMap<>();
    for (var row :
        dsl.fetch(
            "SELECT tablename, indexname, indexdef FROM pg_indexes "
                + "WHERE schemaname = current_schema() ORDER BY tablename, indexname")) {
      indexes.put(
          row.get("tablename", String.class) + "." + row.get("indexname", String.class),
          row.get("indexdef", String.class));
    }
    return Map.copyOf(indexes);
  }

  private static void assertNewBootstrapTablesAreEmpty(DSLContext dsl) {
    assertThat(dsl.fetchCount(DSL.table("game_session_canonical_issuer_layout_bootstrap_control")))
        .isZero();
    assertThat(dsl.fetchCount(DSL.table("game_session_canonical_issuer_layout_bootstrap_intent")))
        .isZero();
    assertThat(
            dsl.fetchCount(DSL.table("game_session_canonical_issuer_layout_bootstrap_cohort_row")))
        .isZero();
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(target)
        .load();
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
    try (Connection connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose issuer-layout migration schema", failure);
    }
  }
}
