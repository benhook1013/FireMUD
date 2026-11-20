package net.firedevops.firemud.loggingadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class ModerationAccountUuidMigrationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;

  @BeforeEach
  void legacySchema() {
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
    flyway("1002").migrate();
    jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .endsWith("1000", "1001", "1002");
    assertThat(
            jdbc.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version = '1002' AND success",
                String.class))
        .endsWith("V1002__saga_step_table.sql");
  }

  @Test
  void emptySchemaMigratesAndKeepsExactUuidWithoutChangingPolicyFields() {
    List<String> parentHistory = migrationHistory();
    flyway("1003").migrate();
    List<String> migratedHistory = migrationHistory();
    assertThat(migratedHistory).hasSize(parentHistory.size() + 1);
    assertThat(migratedHistory.subList(0, parentHistory.size())).isEqualTo(parentHistory);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1003' "
                    + "AND script = 'V1003__moderation_account_uuid.sql' AND success",
                Integer.class))
        .isOne();
    String account = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";
    jdbc.update(
        "INSERT INTO moderation_actions (tenant_id, account_id, action, reason) VALUES (1, ?::uuid, 'chat_mute', 'abuse')",
        account);
    assertThat(jdbc.queryForObject("SELECT account_id::text FROM moderation_actions", String.class))
        .isEqualTo(account);
    assertThat(
            jdbc.queryForObject(
                "SELECT action || ':' || reason FROM moderation_actions", String.class))
        .isEqualTo("chat_mute:abuse");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO moderation_actions (tenant_id, account_id, action) VALUES (1, '00000000-0000-0000-0000-000000000000', 'ban')"))
        .hasMessageContaining("moderation_actions_account_id_non_nil");
  }

  @Test
  void populatedLegacyRowsBlockMigrationWithoutChangingRowsOrMigrationHistory() {
    jdbc.update(
        "INSERT INTO moderation_actions (tenant_id, account_id, action, reason) VALUES (1, 9007199254740993, 'ban', 'retain exactly')");
    String before =
        jdbc.queryForObject(
            "SELECT md5(row_to_json(m)::text) FROM moderation_actions m", String.class);
    String history =
        jdbc.queryForObject(
            "SELECT md5(string_agg(row_to_json(h)::text, ',' ORDER BY installed_rank)) FROM flyway_schema_history h",
            String.class);
    assertThatThrownBy(() -> flyway("1003").migrate())
        .hasStackTraceContaining("Cannot migrate populated legacy moderation Account references");
    assertThat(
            jdbc.queryForObject(
                "SELECT md5(row_to_json(m)::text) FROM moderation_actions m", String.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT md5(string_agg(row_to_json(h)::text, ',' ORDER BY installed_rank)) FROM flyway_schema_history h",
                String.class))
        .isEqualTo(history);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1003'", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'moderation_actions' AND column_name = 'account_id'",
                String.class))
        .isEqualTo("bigint");
  }

  @Test
  void concurrentModerationWriterBlocksMigrationThenItsCommittedRowForcesRefusal()
      throws Exception {
    List<String> historyBefore = migrationHistory();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      int writerPid;
      String rowBefore;
      try (Statement statement = writer.createStatement()) {
        statement.setQueryTimeout(5);
        try (var result = statement.executeQuery("SELECT pg_backend_pid()")) {
          result.next();
          writerPid = result.getInt(1);
        }
        statement.executeUpdate(
            "INSERT INTO moderation_actions (tenant_id, account_id, action, reason) VALUES (17, 9007199254740993, 'ban', 'concurrent retained action')");
        try (var result =
            statement.executeQuery("SELECT row_to_json(m)::text FROM moderation_actions m")) {
          result.next();
          rowBefore = result.getString(1);
        }
      }
      Future<?> migration = executor.submit(() -> flyway("1003").migrate());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean blocked = false;
      try (Connection observer = dataSource.getConnection();
          var query =
              observer.prepareStatement(
                  "SELECT EXISTS (SELECT 1 FROM pg_locks l WHERE l.relation = 'public.moderation_actions'::regclass AND l.mode = 'AccessExclusiveLock' AND NOT l.granted AND ? = ANY(pg_blocking_pids(l.pid)))")) {
        query.setQueryTimeout(2);
        query.setInt(1, writerPid);
        while (System.nanoTime() < deadline && !migration.isDone()) {
          try (var result = query.executeQuery()) {
            result.next();
            blocked = result.getBoolean(1);
          }
          if (blocked) {
            break;
          }
          Thread.sleep(25);
        }
      }
      assertThat(blocked).as("V1003 must wait on the uncommitted moderation writer").isTrue();
      assertThat(migration.isDone()).isFalse();
      writer.commit();
      assertThatThrownBy(() -> migration.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasStackTraceContaining("Cannot migrate populated legacy moderation Account references");
      assertThat(migrationHistory()).isEqualTo(historyBefore);
      assertThat(
              jdbc.queryForList(
                  "SELECT row_to_json(m)::text FROM moderation_actions m", String.class))
          .containsExactly(rowBefore);
      assertThat(
              jdbc.queryForObject(
                  "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'moderation_actions' AND column_name = 'account_id'",
                  String.class))
          .isEqualTo("bigint");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(25, TimeUnit.SECONDS))
          .as("bounded migration task must finish before the fixture is reused")
          .isTrue();
    }
  }

  private List<String> migrationHistory() {
    return jdbc.queryForList(
        "SELECT row_to_json(h)::text FROM flyway_schema_history h ORDER BY installed_rank",
        String.class);
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .initSql("SET lock_timeout = '15s'; SET statement_timeout = '20s'")
        .locations("classpath:db/migration")
        .placeholders(Map.of("serviceSchema", "public"))
        .target(MigrationVersion.fromVersion(target))
        .load();
  }
}
