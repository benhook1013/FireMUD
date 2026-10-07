package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SuppressWarnings("resource")
@Testcontainers(disabledWithoutDocker = true)
class GameplayCommandAccountUuidMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Test
  void migrationRetainsLegacyCommandRowsAndDoesNotInventAccountUuids() {
    try (PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            PostgresBackedServiceTestSupport.postgresImage("postgres:16-alpine"))) {
      postgres.start();
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName(postgres.getDriverClassName());
      dataSource.setUrl(postgres.getJdbcUrl());
      dataSource.setUsername(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());

      Flyway.configure()
          .dataSource(dataSource)
          .locations(MIGRATION_LOCATION)
          .target(MigrationVersion.fromVersion("10"))
          .load()
          .migrate();

      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      insertLegacyCommand(dsl, "legacy-account-101", 101L);
      insertLegacyCommand(dsl, "legacy-account-202", 202L);
      insertLegacyCommand(dsl, "legacy-account-null", null);
      List<String> beforeMigrationRows =
          dsl.fetch(
                  "SELECT to_jsonb(command_row)::text AS retained_row "
                      + "FROM gameplay_command AS command_row ORDER BY command_id")
              .getValues("retained_row", String.class);

      Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();

      List<Record> migratedRows =
          dsl.fetch(
              "SELECT command_id, account_id, account_uuid "
                  + "FROM gameplay_command ORDER BY command_id");
      assertThat(migratedRows)
          .extracting(
              row -> row.get("command_id", String.class),
              row -> row.get("account_id", Long.class),
              row -> row.get("account_uuid", UUID.class))
          .containsExactly(
              tuple("legacy-account-101", 101L, null),
              tuple("legacy-account-202", 202L, null),
              tuple("legacy-account-null", null, null));
      List<String> migratedLegacyRows =
          dsl.fetch(
                  "SELECT (to_jsonb(command_row) - 'account_uuid')::text AS retained_row "
                      + "FROM gameplay_command AS command_row ORDER BY command_id")
              .getValues("retained_row", String.class);
      assertThat(migratedLegacyRows).containsExactlyElementsOf(beforeMigrationRows);
    }
  }

  private static void insertLegacyCommand(DSLContext dsl, String commandId, Long accountId) {
    dsl.execute(
        "INSERT INTO gameplay_command (command_id, tenant_id, game_instance_id, session_id, "
            + "account_id, command_name, sanitized_command_text, requires_solo_tick, "
            + "execution_outcome, gameplay_result, accepted_at, staged_at, completed_at, "
            + "last_attempt_at, attempt_count, source_type, command_text, "
            + "playable_state_scope, world_slug, realm_slug) "
            + "VALUES (?, 1, 7, 11, ?, 'look', 'look', false, 'APPLIED', 'SUCCESS', "
            + "TIMESTAMP '2000-01-01 12:00:00', TIMESTAMP '2000-01-01 12:00:10', "
            + "TIMESTAMP '2000-01-01 12:01:00', TIMESTAMP '2000-01-01 12:01:00', 1, "
            + "'PLAYER', 'look', '', '', '')",
        commandId,
        accountId);
  }
}
