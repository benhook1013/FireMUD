package integration.net.firedevops.firemud.loggingadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class LoggingAccountUuidMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION = "classpath:db/migration";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void freshSchemaMigratesWithCanonicalAccountUuidsAndNumericLocalIdentifiers() throws Exception {
    String schema = createSchema();

    migrateToLatest(schema);

    assertThat(columnType(schema, "moderation_actions", "account_id")).isEqualTo("uuid");
    assertThat(columnType(schema, "player_reports", "reporter_account_id")).isEqualTo("uuid");
    assertThat(columnType(schema, "player_reports", "target_account_id")).isEqualTo("uuid");
    assertThat(columnType(schema, "moderation_actions", "tenant_id")).isEqualTo("int8");
    assertThat(columnType(schema, "player_reports", "tenant_id")).isEqualTo("int8");
    assertThat(columnType(schema, "moderation_actions", "id")).isEqualTo("int8");
    assertThat(columnType(schema, "player_reports", "id")).isEqualTo("int8");
    assertThat(columnType(schema, "log_events", "account_id")).isEqualTo("int8");
    // Runtime discovery includes Saga migrations V1000–V1002 after service migration V6.
    assertThat(history(schema)).hasSize(9);
  }

  @Test
  void emptyAffectedTablesUpgradeFromV5AndGenericLogAccountIdRemainsNumeric() throws Exception {
    String schema = createSchema();
    migrateToVersion(schema, "5");
    execute(
        schema,
        "INSERT INTO log_events (tenant_id, type, message, account_id) VALUES (1, 'INFO', 'preserve generic log', 42)");

    migrateToLatest(schema);

    assertThat(columnType(schema, "moderation_actions", "account_id")).isEqualTo("uuid");
    assertThat(columnType(schema, "player_reports", "reporter_account_id")).isEqualTo("uuid");
    assertThat(columnType(schema, "player_reports", "target_account_id")).isEqualTo("uuid");
    assertThat(columnType(schema, "player_reports", "tenant_id")).isEqualTo("int8");
    assertThat(columnType(schema, "moderation_actions", "id")).isEqualTo("int8");
    assertThat(columnType(schema, "player_reports", "id")).isEqualTo("int8");
    assertThat(columnType(schema, "log_events", "account_id")).isEqualTo("int8");
    assertThat(
            scalarLong(
                schema, "SELECT account_id FROM log_events WHERE message = 'preserve generic log'"))
        .isEqualTo(42L);
    // The fixture applies the same Saga migrations as the full runtime discovery.
    assertThat(history(schema)).hasSize(9);
  }

  @Test
  void retainedModerationActionRefusesWithoutChangingRowsOrMigrationHistory() throws Exception {
    String schema = createSchema();
    migrateToVersion(schema, "5");
    execute(
        schema,
        "INSERT INTO moderation_actions (tenant_id, account_id, action, reason) VALUES (17, 29, 'warning', 'retained action')");
    List<String> historyBefore = history(schema);
    List<String> rowsBefore = rows(schema, "moderation_actions");

    FlywayException failure = assertThrows(FlywayException.class, () -> migrateToLatest(schema));

    assertThat(failure).hasStackTraceContaining("Account-owned recovery is required");
    assertThat(history(schema)).isEqualTo(historyBefore);
    assertThat(rows(schema, "moderation_actions")).isEqualTo(rowsBefore);
    assertThat(columnType(schema, "moderation_actions", "account_id")).isEqualTo("int8");
    assertThat(
            scalarLong(
                schema,
                "SELECT tenant_id FROM moderation_actions WHERE reason = 'retained action'"))
        .isEqualTo(17L);
    assertThat(
            scalarLong(schema, "SELECT count(*) FROM moderation_actions WHERE action = 'warning'"))
        .isEqualTo(1L);
    assertThat(
            scalarLong(
                schema,
                "SELECT account_id FROM moderation_actions WHERE reason = 'retained action'"))
        .isEqualTo(29L);
    assertThat(scalarLong(schema, "SELECT count(*) FROM player_reports")).isZero();
    assertThat(columnType(schema, "log_events", "account_id")).isEqualTo("int8");
  }

  @Test
  void retainedPlayerReportRefusesWithoutChangingRowsOrMigrationHistory() throws Exception {
    String schema = createSchema();
    migrateToVersion(schema, "5");
    execute(
        schema,
        "INSERT INTO player_reports (tenant_id, reporter_account_id, target_account_id, type, description) VALUES (41, 43, 47, 'BUG', 'retained report')");
    List<String> historyBefore = history(schema);
    List<String> rowsBefore = rows(schema, "player_reports");

    FlywayException failure = assertThrows(FlywayException.class, () -> migrateToLatest(schema));

    assertThat(failure).hasStackTraceContaining("Account-owned recovery is required");
    assertThat(history(schema)).isEqualTo(historyBefore);
    assertThat(rows(schema, "player_reports")).isEqualTo(rowsBefore);
    assertThat(columnType(schema, "player_reports", "reporter_account_id")).isEqualTo("int8");
    assertThat(columnType(schema, "player_reports", "target_account_id")).isEqualTo("int8");
    assertThat(
            scalarLong(
                schema,
                "SELECT tenant_id FROM player_reports WHERE description = 'retained report'"))
        .isEqualTo(41L);
    assertThat(scalarLong(schema, "SELECT count(*) FROM player_reports WHERE type = 'BUG'"))
        .isEqualTo(1L);
    assertThat(
            scalarLong(
                schema,
                "SELECT reporter_account_id FROM player_reports WHERE description = 'retained report'"))
        .isEqualTo(43L);
    assertThat(
            scalarLong(
                schema,
                "SELECT target_account_id FROM player_reports WHERE description = 'retained report'"))
        .isEqualTo(47L);
    assertThat(scalarLong(schema, "SELECT count(*) FROM moderation_actions")).isZero();
    assertThat(columnType(schema, "log_events", "account_id")).isEqualTo("int8");
  }

  @Test
  void migrationWaitsForConcurrentWriterAndRefusesAfterItsRowCommits() throws Exception {
    String schema = createSchema();
    migrateToVersion(schema, "5");
    List<String> historyBefore = history(schema);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection writer = connection()) {
      writer.setAutoCommit(false);
      try (Statement statement = writer.createStatement()) {
        statement.executeUpdate(
            inSchema(
                schema,
                "INSERT INTO moderation_actions (tenant_id, account_id, action, reason) VALUES (7, 11, 'warning', 'concurrent writer')"));
      }

      CountDownLatch migrationStarted = new CountDownLatch(1);
      Future<?> migration =
          executor.submit(
              () -> {
                migrationStarted.countDown();
                migrateToLatest(schema);
              });

      assertTrue(migrationStarted.await(5, TimeUnit.SECONDS));
      awaitBlockedMigrationLock(schema);
      assertFalse(migration.isDone());
      writer.commit();

      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> migration.get(10, TimeUnit.SECONDS));
      assertThat(failure).hasStackTraceContaining("Account-owned recovery is required");
      assertThat(history(schema)).isEqualTo(historyBefore);
      assertThat(history(schema)).hasSize(5);
      assertThat(columnType(schema, "moderation_actions", "account_id")).isEqualTo("int8");
      assertThat(scalarLong(schema, "SELECT count(*) FROM moderation_actions")).isEqualTo(1L);
      assertThat(
              scalarLong(
                  schema,
                  "SELECT account_id FROM moderation_actions WHERE reason = 'concurrent writer'"))
          .isEqualTo(11L);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static String createSchema() throws SQLException {
    String schema = "logging_account_uuid_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + quote(schema));
    }
    return schema;
  }

  private static void migrateToVersion(String schema, String version) {
    Flyway.configure()
        .dataSource(dataSource())
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target(MigrationVersion.fromVersion(version))
        .load()
        .migrate();
  }

  private static void migrateToLatest(String schema) {
    Flyway.configure()
        .dataSource(dataSource())
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
  }

  private static void execute(String schema, String sql) throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute(inSchema(schema, sql));
    }
  }

  private static String columnType(String schema, String table, String column) throws SQLException {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT udt_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "expected column " + schema + "." + table + "." + column);
        return result.getString(1);
      }
    }
  }

  private static long scalarLong(String schema, String sql) throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(inSchema(schema, sql))) {
      assertTrue(result.next(), "expected scalar query result");
      return result.getLong(1);
    }
  }

  private static List<String> history(String schema) throws SQLException {
    List<String> entries = new ArrayList<>();
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                inSchema(
                    schema,
                    "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"))) {
      while (result.next()) {
        entries.add(
            result.getString("version")
                + ":"
                + result.getString("checksum")
                + ":"
                + result.getBoolean("success"));
      }
    }
    return entries;
  }

  private static List<String> rows(String schema, String table) throws SQLException {
    List<String> records = new ArrayList<>();
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT to_jsonb(row_data)::text FROM (SELECT * FROM "
                    + quote(schema)
                    + "."
                    + quote(table)
                    + " ORDER BY id) AS row_data")) {
      while (result.next()) {
        records.add(result.getString(1));
      }
    }
    return records;
  }

  private static void awaitBlockedMigrationLock(String schema) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      try (Connection connection = connection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT count(*) FROM pg_locks AS waiting_lock "
                      + "JOIN pg_class AS locked_relation ON locked_relation.oid = waiting_lock.relation "
                      + "JOIN pg_namespace AS locked_schema ON locked_schema.oid = locked_relation.relnamespace "
                      + "WHERE waiting_lock.pid <> pg_backend_pid() "
                      + "AND waiting_lock.locktype = 'relation' "
                      + "AND waiting_lock.mode = 'AccessExclusiveLock' "
                      + "AND NOT waiting_lock.granted "
                      + "AND locked_schema.nspname = ? "
                      + "AND locked_relation.relname IN ('moderation_actions', 'player_reports')")) {
        statement.setString(1, schema);
        try (ResultSet result = statement.executeQuery()) {
          if (result.next() && result.getLong(1) > 0L) {
            return;
          }
        }
      }
      Thread.sleep(25L);
    }
    throw new AssertionError("Flyway migration did not block on the concurrent writer");
  }

  private static DataSource dataSource() {
    return new org.springframework.jdbc.datasource.DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static String inSchema(String schema, String sql) {
    return sql.replace("INTO ", "INTO " + quote(schema) + ".")
        .replace("FROM ", "FROM " + quote(schema) + ".");
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
