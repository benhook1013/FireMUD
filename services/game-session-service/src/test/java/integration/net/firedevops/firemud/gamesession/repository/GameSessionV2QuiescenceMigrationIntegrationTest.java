package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionV2QuiescenceMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private String schema;

  @AfterEach
  void dropIsolatedSchema() {
    if (schema != null) {
      adminDsl().execute("DROP SCHEMA " + schema + " CASCADE");
      schema = null;
    }
  }

  @Test
  void migrationFailsClosedAndPreservesClaimedRemoteFollowup() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute(
        "insert into remote_followup "
            + "(followup_id, tenant_id, origin_game_instance_id, origin_region_id, "
            + "origin_region_epoch, target_game_instance_id, target_region_id, "
            + "target_region_epoch, due_tick_id, effect_key, status, created_at, updated_at, "
            + "claim_target_aggregate) "
            + "values ('followup-active', 1, 10, 'region-a', 1, 11, 'region-b', 1, 1, "
            + "'effect-a', 'CLAIMED', now(), now(), 'entity:11')");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("claimed remote follow-ups to drain");
    assertThat(
            dsl.fetch(
                    "select count(*) from remote_followup "
                        + "where followup_id = 'followup-active' and status = 'CLAIMED'")
                .get(0)
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetch(
                    "select count(*) from pg_indexes "
                        + "where schemaname = current_schema() "
                        + "and indexname in (" +
                        "'idx_gameplay_command_command_id', " +
                        "'idx_remote_command_coordinator_command_id', " +
                        "'uq_gameplay_admission_pointer_world_realm')")
                .get(0)
                .get(0, Long.class))
        .isEqualTo(3L);
  }

  @Test
  void migrationFailsClosedWhenLegacyUniqueIndexShapeDrifts() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute("drop index idx_gameplay_command_command_id");
    dsl.execute(
        "create unique index idx_gameplay_command_command_id "
            + "on gameplay_command (tenant_id)");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("three exact V1 pre-migration unique indexes");
    String indexDefinition =
        (String)
            dsl.fetchValue(
            "select pg_get_indexdef(indexrelid) from pg_index "
                + "where indexrelid = 'idx_gameplay_command_command_id'::regclass",
            String.class);
    assertThat(indexDefinition).contains("(tenant_id)");
    assertThat(
            dsl.fetchValue(
                "select count(*) from pg_indexes where schemaname = current_schema() "
                    + "and indexname = 'idx_remote_command_coordinator_command_id'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void migrationFailsClosedWhenLegacyUniqueIndexHasWrongOwningTable() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute("drop index idx_gameplay_command_command_id");
    dsl.execute(
        "create unique index idx_gameplay_command_command_id "
            + "on remote_command_coordinator (tenant_id, command_id)");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("three exact V1 pre-migration unique indexes");
    assertThat(
            dsl.fetchValue(
                "select tablename from pg_indexes where schemaname = current_schema() "
                    + "and indexname = 'idx_gameplay_command_command_id'",
                String.class))
        .isEqualTo("remote_command_coordinator");
  }

  @Test
  void migrationFailsClosedWhenLegacyCommandConstraintColumnsDrift() {
    DSLContext dsl = migrateToVersionOne();
    dsl.execute("alter table gameplay_command drop constraint gameplay_command_command_id_key");
    dsl.execute(
        "alter table gameplay_command add constraint gameplay_command_command_id_key "
            + "unique (tenant_id)");

    assertThatThrownBy(this::migrateExistingSchemaToLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("gameplay_command_command_id_key unique constraint");
    assertThat(
            dsl.fetchValue(
                "select pg_get_constraintdef(oid) from pg_constraint "
                    + "where conrelid = 'gameplay_command'::regclass "
                    + "and conname = 'gameplay_command_command_id_key'",
                String.class))
        .isEqualTo("UNIQUE (tenant_id)");
  }

  private DSLContext migrateToVersionOne() {
    schema = "gamesession_v2_quiescence_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .target("1")
        .load()
        .migrate();
    return DSL.using(dataSource(schema), SQLDialect.POSTGRES);
  }

  private void migrateExistingSchemaToLatest() {
    Flyway.configure()
        .dataSource(dataSource(schema))
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
  }

  private DSLContext adminDsl() {
    return DSL.using(dataSource(null), SQLDialect.POSTGRES);
  }

  private DriverManagerDataSource dataSource(String schemaName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    String baseUrl = postgres.getJdbcUrl();
    dataSource.setUrl(
        schemaName == null
            ? baseUrl
            : baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schemaName);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }
}
