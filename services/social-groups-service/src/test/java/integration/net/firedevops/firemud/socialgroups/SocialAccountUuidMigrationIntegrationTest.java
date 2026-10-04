package net.firedevops.firemud.socialgroups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SocialAccountUuidMigrationIntegrationTest {
  private static final String HISTORY_TABLE = "flyway_schema_history_social_uuid";
  private static final List<String> AFFECTED_TABLES =
      List.of(
          "guilds",
          "guild_members",
          "chat_messages",
          "mail_messages",
          "account_friend_links",
          "friend_links");
  private static final Map<String, List<String>> ACCOUNT_COLUMNS =
      Map.of(
          "guilds", List.of("owner_account_id"),
          "guild_members", List.of("account_id"),
          "chat_messages", List.of("sender_account_id", "recipient_account_id"),
          "mail_messages", List.of("sender_account_id", "recipient_account_id"),
          "account_friend_links", List.of("account_id", "friend_account_id"),
          "friend_links", List.of("account_id", "friend_account_id"));

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void freshAndEmptyV9UpgradeConvergeAllAccountColumnsWithoutChangingOtherIdentityTypes()
      throws Exception {
    Database fresh = newDatabase();
    flyway(fresh, null).migrate();
    assertIdentityTypes(fresh, "uuid");

    Database upgraded = newDatabase();
    flyway(upgraded, "9").migrate();
    assertIdentityTypes(upgraded, "bigint");
    List<String> constraintsBefore = constraints(upgraded);
    List<String> historyBefore = rows(upgraded, HISTORY_TABLE, "installed_rank");
    flyway(upgraded, null).migrate();
    assertIdentityTypes(upgraded, "uuid");
    assertThat(constraints(upgraded)).containsAll(constraintsBefore);
    assertThat(rows(upgraded, HISTORY_TABLE, "installed_rank").subList(0, historyBefore.size()))
        .containsExactlyElementsOf(historyBefore);
    assertThat(rows(upgraded, HISTORY_TABLE, "installed_rank")).hasSize(historyBefore.size() + 1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "guilds",
        "guild_members",
        "chat_messages",
        "mail_messages",
        "account_friend_links",
        "friend_links"
      })
  void anyRetainedAffectedTableRefusesWithoutChangingRowsSchemaOrMigrationHistory(String table)
      throws Exception {
    Database database = newDatabase();
    flyway(database, "9").migrate();
    try (Connection connection = database.dataSource().getConnection()) {
      insertRetainedRow(connection, database, table);
    }
    Map<String, List<String>> before = evidence(database);
    List<String> constraintsBefore = constraints(database);

    assertThatThrownBy(() -> flyway(database, null).migrate())
        .satisfies(SocialAccountUuidMigrationIntegrationTest::assertRetainedRefusal);

    assertThat(evidence(database)).isEqualTo(before);
    assertThat(constraints(database)).isEqualTo(constraintsBefore);
    assertIdentityTypes(database, "bigint");
  }

  @Test
  void concurrentWriterCommitsBeforeEmptyCheckAndItsEvidenceIsPreserved() throws Exception {
    Database database = newDatabase();
    flyway(database, "9").migrate();
    List<String> historyBefore = rows(database, HISTORY_TABLE, "installed_rank");
    Connection writer = database.dataSource().getConnection();
    writer.setAutoCommit(false);
    insertRetainedRow(writer, database, "mail_messages");
    List<String> writtenEvidence = rows(writer, database, "mail_messages", "id");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> migration = executor.submit(() -> flyway(database, null).migrate());
      assertThat(awaitExclusiveLockWait(database, "mail_messages", Duration.ofSeconds(10)))
          .isTrue();
      assertThat(migration.isDone()).isFalse();
      writer.commit();
      assertThatThrownBy(() -> migration.get(20, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .satisfies(SocialAccountUuidMigrationIntegrationTest::assertRetainedRefusal);
    } finally {
      try {
        writer.rollback();
      } finally {
        writer.close();
        executor.shutdownNow();
      }
    }
    assertThat(rows(database, "mail_messages", "id")).isEqualTo(writtenEvidence);
    assertThat(rows(database, HISTORY_TABLE, "installed_rank")).isEqualTo(historyBefore);
    assertIdentityTypes(database, "bigint");
  }

  private static void assertRetainedRefusal(Throwable failure) {
    Throwable cause = failure;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    if (cause == null) {
      throw new AssertionError("Migration refusal lacked the expected SQL cause", failure);
    }
    SQLException sql = (SQLException) cause;
    assertThat(sql.getSQLState()).isEqualTo("23514");
    assertThat(sql.getMessage()).contains("Account", "recovery");
  }

  private static boolean awaitExclusiveLockWait(Database database, String table, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    try (Connection connection = database.dataSource().getConnection();
        var query =
            connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE relation = ?::regclass AND mode = 'AccessExclusiveLock' AND NOT granted)")) {
      query.setString(1, qualified(database, table));
      while (System.nanoTime() < deadline) {
        try (ResultSet result = query.executeQuery()) {
          if (result.next() && result.getBoolean(1)) {
            return true;
          }
        }
        Thread.sleep(25L);
      }
      return false;
    }
  }

  private static void insertRetainedRow(Connection connection, Database database, String table)
      throws SQLException {
    String values =
        switch (table) {
          case "guilds" -> "(id,tenant_id,name,owner_account_id) VALUES(1,11,'Retained guild',42)";
          case "guild_members" -> {
            insertRetainedRow(connection, database, "guilds");
            yield "(id,tenant_id,guild_id,account_id,role) VALUES(1,11,1,42,'member')";
          }
          case "chat_messages" ->
              "(id,tenant_id,sender_account_id,recipient_account_id,content) VALUES(1,11,42,43,'unchanged retained chat')";
          case "mail_messages" ->
              "(id,tenant_id,sender_account_id,recipient_account_id,subject,content) VALUES(1,11,42,43,'retained','unchanged mail evidence')";
          case "account_friend_links", "friend_links" ->
              "(id,tenant_id,account_id,friend_account_id,status) VALUES(1,11,42,43,'ACTIVE')";
          default -> throw new IllegalArgumentException("Unknown proof table");
        };
    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO " + qualified(database, table) + " " + values);
    }
  }

  private static Map<String, List<String>> evidence(Database database) throws SQLException {
    Map<String, List<String>> snapshot = new LinkedHashMap<>();
    for (String table : AFFECTED_TABLES) {
      snapshot.put(table, rows(database, table, "id"));
    }
    snapshot.put(HISTORY_TABLE, rows(database, HISTORY_TABLE, "installed_rank"));
    return Map.copyOf(snapshot);
  }

  private static List<String> rows(Database database, String table, String order)
      throws SQLException {
    try (Connection connection = database.dataSource().getConnection()) {
      return rows(connection, database, table, order);
    }
  }

  private static List<String> rows(
      Connection connection, Database database, String table, String order) throws SQLException {
    try (Statement query = connection.createStatement();
        ResultSet result =
            query.executeQuery(
                "SELECT to_jsonb(t)::text FROM "
                    + qualified(database, table)
                    + " t ORDER BY t."
                    + order)) {
      List<String> rows = new ArrayList<>();
      while (result.next()) {
        rows.add(result.getString(1));
      }
      return List.copyOf(rows);
    }
  }

  private static List<String> constraints(Database database) throws SQLException {
    try (Connection connection = database.dataSource().getConnection();
        var query =
            connection.prepareStatement(
                "SELECT c.relname || ':' || x.conname || ':' || pg_get_constraintdef(x.oid) FROM pg_constraint x JOIN pg_class c ON c.oid = x.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? ORDER BY c.relname, x.conname")) {
      query.setString(1, database.schema());
      try (ResultSet result = query.executeQuery()) {
        List<String> constraints = new ArrayList<>();
        while (result.next()) {
          constraints.add(result.getString(1));
        }
        return List.copyOf(constraints);
      }
    }
  }

  private static void assertIdentityTypes(Database database, String expectedAccountType)
      throws SQLException {
    try (Connection connection = database.dataSource().getConnection();
        var query =
            connection.prepareStatement(
                "SELECT udt_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
      for (String table : AFFECTED_TABLES) {
        List<String> columns = new ArrayList<>(ACCOUNT_COLUMNS.get(table));
        columns.addAll(List.of("id", "tenant_id"));
        for (String column : columns) {
          query.setString(1, database.schema());
          query.setString(2, table);
          query.setString(3, column);
          try (ResultSet result = query.executeQuery()) {
            assertThat(result.next()).as("%s.%s", table, column).isTrue();
            String expected =
                ACCOUNT_COLUMNS.get(table).contains(column) ? expectedAccountType : "bigint";
            assertThat(result.getString(1))
                .as("%s.%s", table, column)
                .isEqualTo(expected.equals("bigint") ? "int8" : expected);
          }
        }
      }
    }
  }

  private static Flyway flyway(Database database, String target) {
    var config =
        Flyway.configure()
            .dataSource(database.dataSource())
            .schemas(database.schema())
            .defaultSchema(database.schema())
            .table(HISTORY_TABLE)
            .locations("classpath:db/migration");
    if (target != null) {
      config.target(MigrationVersion.fromVersion(target));
    }
    return config.load();
  }

  private static Database newDatabase() {
    return new Database(
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()),
        "social_uuid_" + UUID.randomUUID().toString().replace("-", ""));
  }

  private static String qualified(Database database, String table) {
    return '"' + database.schema() + "\".\"" + table + '"';
  }

  private record Database(DataSource dataSource, String schema) {}
}
