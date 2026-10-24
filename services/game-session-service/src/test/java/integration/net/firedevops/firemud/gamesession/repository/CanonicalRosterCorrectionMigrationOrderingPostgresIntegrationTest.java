package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL migration-history proof for the current GS and Entity roster prefixes. This
 * proves migration ordering and retained fixture bytes only; owner runtime suites prove their
 * respective retention behavior.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalRosterCorrectionMigrationOrderingPostgresIntegrationTest {
  private static final String GS_MIGRATIONS =
      filesystem(Path.of("src/main/resources/db/migration"));
  private static final String ENTITY_MIGRATIONS =
      filesystem(Path.of("../entity-management-service/src/main/resources/db/migration"));
  private static final String SAGA_MIGRATIONS =
      filesystem(Path.of("../common-saga/src/main/resources/db/migration/saga"));

  private static final MigrationSet GAME_SESSION =
      new MigrationSet("game-session", GS_MIGRATIONS, "1017", "1020", "1020", "1021");
  private static final MigrationSet ENTITY =
      new MigrationSet("entity-management", ENTITY_MIGRATIONS, "1014", "1017", "1017", "1018");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private final List<String> schemas = new ArrayList<>();

  @AfterEach
  void dropOwnedSchemas() throws java.sql.SQLException {
    try (java.sql.Connection connection =
        java.sql.DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      DSLContext admin = DSL.using(connection, SQLDialect.POSTGRES);
      for (String schema : schemas) {
        admin.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
    }
  }

  @Test
  void currentPrefixesUpgradeCleanAndRetainedPublishedHistoriesWithoutRewritingHistory() {
    for (MigrationSet migrations : List.of(GAME_SESSION, ENTITY)) {
      proveCleanInstall(migrations);
      proveRetainedParentUpgradeAndNoOp(migrations);
      provePublishedChildUpgradeAndNoOp(migrations);
    }
  }

  private void proveCleanInstall(MigrationSet migrations) {
    String schema = newSchema(migrations.name() + "_clean_");
    DriverManagerDataSource dataSource = dataSource(schema);

    migrate(dataSource, schema, migrations.locations(), null);

    List<String> installed = history(dataSource);
    assertMigrationPresent(installed, migrations.childVersion(), migrations.childScript());
    assertMigrationPresent(installed, migrations.frontVersion(), migrations.frontScript());
    assertThat(installed).allSatisfy(row -> assertThat(row).contains("success=true"));
  }

  private void proveRetainedParentUpgradeAndNoOp(MigrationSet migrations) {
    String schema = newSchema(migrations.name() + "_parent_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, migrations.locations(), migrations.parentVersion());
    seedRetainedOwnerRow(migrations, schema, dataSource);

    List<String> parentHistory = history(dataSource);
    List<String> retainedBytes = retainedOwnerRows(migrations, dataSource);
    assertThat(parentHistory).isNotEmpty();

    migrate(dataSource, schema, migrations.locations(), null);

    List<String> upgradedHistory = history(dataSource);
    assertHistoryPrefix(upgradedHistory, parentHistory, migrations.name() + " retained parent");
    assertMigrationPresent(upgradedHistory, migrations.childVersion(), migrations.childScript());
    assertMigrationPresent(upgradedHistory, migrations.frontVersion(), migrations.frontScript());
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);

    migrate(dataSource, schema, migrations.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(upgradedHistory);
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);
  }

  private void provePublishedChildUpgradeAndNoOp(MigrationSet migrations) {
    String schema = newSchema(migrations.name() + "_child_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, migrations.locations(), migrations.publishedChildVersion());
    seedRetainedOwnerRow(migrations, schema, dataSource);

    List<String> childHistory = history(dataSource);
    List<String> retainedBytes = retainedOwnerRows(migrations, dataSource);
    assertMigrationPresent(childHistory, migrations.childVersion(), migrations.childScript());

    migrate(dataSource, schema, migrations.locations(), null);

    List<String> upgradedHistory = history(dataSource);
    assertHistoryPrefix(upgradedHistory, childHistory, migrations.name() + " published child");
    assertMigrationPresent(upgradedHistory, migrations.frontVersion(), migrations.frontScript());
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);

    migrate(dataSource, schema, migrations.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(upgradedHistory);
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);
  }

  private void seedRetainedOwnerRow(
      MigrationSet migrations, String schema, DriverManagerDataSource dataSource) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    if (migrations == GAME_SESSION) {
      UUID fence = UUID.randomUUID();
      dsl.execute(
          "INSERT INTO game_session_canonical_roster_target_fences "
              + "(target_fence_uuid, target_binding_digest, target_binding, "
              + "minimum_retention_millis, replay_horizon_millis) VALUES (?, ?, ?, 1000, 500)",
          fence,
          "a".repeat(64),
          new byte[] {1});
      dsl.execute(
          "INSERT INTO game_session_canonical_roster_selection "
              + "(operation_uuid, target_fence_uuid, original_request) VALUES (?, ?, ?)",
          UUID.randomUUID(),
          fence,
          new byte[] {2, 3});
      return;
    }
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions "
            + "(operation_uuid, admission_uuid, request_binding) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        new byte[] {4, 5});
  }

  private List<String> retainedOwnerRows(
      MigrationSet migrations, DriverManagerDataSource dataSource) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    if (migrations == GAME_SESSION) {
      return dsl
          .fetch(
              "SELECT retained_row FROM ("
                  + "SELECT jsonb_build_array('fence', target_fence_uuid::text, target_binding_digest, "
                  + "encode(target_binding, 'hex'), owner_fence, fence_state, minimum_retention_millis, "
                  + "replay_horizon_millis)::text AS retained_row "
                  + "FROM game_session_canonical_roster_target_fences UNION ALL "
                  + "SELECT jsonb_build_array('selection', operation_uuid::text, target_fence_uuid::text, "
                  + "owner_fence, encode(original_request, 'hex'), recorded_at::text)::text "
                  + "FROM game_session_canonical_roster_selection) retained ORDER BY retained_row")
          .getValues(0, String.class)
          .stream()
          .toList();
    }
    return dsl
        .fetch(
            "SELECT jsonb_build_array(operation_uuid::text, admission_uuid::text, "
                + "encode(request_binding, 'hex'), admission_state, snapshot_uuid::text, "
                + "recorded_at::text)::text FROM entity_canonical_roster_admissions "
                + "ORDER BY operation_uuid")
        .getValues(0, String.class)
        .stream()
        .toList();
  }

  private void migrate(
      DriverManagerDataSource dataSource, String schema, List<String> locations, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(locations.toArray(String[]::new))
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .placeholders(Map.of("serviceSchema", schema));
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    configuration
        .getPluginRegister()
        .getPlugin(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
  }

  private List<String> history(DriverManagerDataSource dataSource) {
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetch(
            "SELECT version, script, checksum, installed_rank, success "
                + "FROM flyway_schema_history ORDER BY installed_rank")
        .map(row -> row.intoMap().toString());
  }

  private void assertHistoryPrefix(List<String> current, List<String> original, String cohort) {
    assertThat(current)
        .as("%s keeps every installed rank, script, checksum, and success flag", cohort)
        .hasSizeGreaterThan(original.size());
    assertThat(current.subList(0, original.size())).containsExactlyElementsOf(original);
  }

  private void assertMigrationPresent(List<String> history, String version, String script) {
    assertThat(history)
        .anySatisfy(row -> assertThat(row).contains(version, script, "success=true"));
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private String newSchema(String prefix) {
    String schema = prefix.replace('-', '_') + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    return schema;
  }

  private static String filesystem(Path path) {
    return "filesystem:" + path.toAbsolutePath().normalize();
  }

  private static List<String> locations(String serviceMigrations) {
    return List.of(serviceMigrations, SAGA_MIGRATIONS);
  }

  private record MigrationSet(
      String name,
      String migrationLocation,
      String parentVersion,
      String publishedChildVersion,
      String childVersion,
      String frontVersion) {
    private List<String> locations() {
      return CanonicalRosterCorrectionMigrationOrderingPostgresIntegrationTest.locations(
          migrationLocation);
    }

    private String childScript() {
      return this == GAME_SESSION
          ? "V1020__player_created_roster_reference.sql"
          : "V1017__player_created_actor_quarantine.sql";
    }

    private String frontScript() {
      return this == GAME_SESSION
          ? "V1021__canonical_roster_recovery_attempt_scheduling.sql"
          : "V1018__canonical_roster_staged_retirement.sql";
    }
  }
}
