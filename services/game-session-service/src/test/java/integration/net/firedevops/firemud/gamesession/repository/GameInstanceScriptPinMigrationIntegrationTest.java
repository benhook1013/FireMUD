package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameInstanceScriptPinMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void v10NormalizesLegacyPartialPinRowsAndEnforcesNewTupleWrites() {
    DriverManagerDataSource dataSource = dataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .target("7")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

    insertLegacyRows(dsl);

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .target("10")
        .load()
        .migrate();

    assertThat(
            dsl.fetchOne(
                "SELECT script_patch_version, script_pin_epoch, "
                    + "script_patch_pinned_control_plane_request_id FROM game_instances WHERE id = 101"))
        .extracting(
            record -> record.get("script_patch_version"),
            record -> record.get("script_pin_epoch"),
            record -> record.get("script_patch_pinned_control_plane_request_id"))
        .containsExactly("patch-coherent", 1L, "request-coherent");
    assertThat(
            dsl.fetchOne(
                "SELECT script_patch_version, script_pin_epoch, "
                    + "script_patch_pinned_control_plane_request_id FROM game_instances WHERE id = 102"))
        .extracting(
            record -> record.get("script_patch_version"),
            record -> record.get("script_pin_epoch"),
            record -> record.get("script_patch_pinned_control_plane_request_id"))
        .containsExactly(null, null, null);
    assertThat(
            dsl.fetchOne(
                "SELECT script_patch_version, script_pin_epoch, "
                    + "script_patch_pinned_control_plane_request_id FROM game_instances WHERE id = 103"))
        .extracting(
            record -> record.get("script_patch_version"),
            record -> record.get("script_pin_epoch"),
            record -> record.get("script_patch_pinned_control_plane_request_id"))
        .containsExactly(null, null, null);

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .target("13")
        .load()
        .migrate();
    dsl.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, script_patch_version, "
            + "script_pin_epoch, script_patch_pinned_control_plane_request_id, "
            + "script_patch_pinned_at, script_patch_pinned_by, script_patch_pinned_reason, "
            + "owner_account_id, status) VALUES "
            + "(107, 42, '1.0.0', NULL, NULL, NULL, "
            + "TIMESTAMP '2025-01-02 03:04:05', 'stale-owner', 'stale-reason', 107, 'STOPPED')");

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .target("14")
        .load()
        .migrate();
    assertThat(
            dsl.fetchOne(
                "SELECT script_patch_pinned_at, script_patch_pinned_by, "
                    + "script_patch_pinned_reason FROM game_instances WHERE id = 107"))
        .extracting(
            record -> record.get("script_patch_pinned_at"),
            record -> record.get("script_patch_pinned_by"),
            record -> record.get("script_patch_pinned_reason"))
        .containsExactly(null, null, null);

    dsl.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, script_patch_version, "
            + "script_pin_epoch, script_patch_pinned_control_plane_request_id, owner_account_id, status) "
            + "VALUES (104, 42, '1.0.0', 'patch-new', 2, 'request-new', 104, 'RUNNING')");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO game_instances (id, tenant_id, runtime_version, "
                        + "script_patch_version, owner_account_id, status) "
                        + "VALUES (105, 42, '1.0.0', 'patch-invalid', 105, 'RUNNING')"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("game_instances_script_pin_tuple_coherent");
    assertThat(dsl.fetch("SELECT * FROM game_instances WHERE id = 105")).isEmpty();

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .target("16")
        .load()
        .migrate();

    dsl.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, script_patch_version, "
            + "script_pin_epoch, script_patch_pinned_control_plane_request_id, owner_account_id, status) "
            + "VALUES (106, 42, '1.0.0', 'patch-after-validation', 106, 'request-after-validation', 106, 'RUNNING')");
    assertThat(dsl.fetch("SELECT * FROM game_instances WHERE id = 106")).hasSize(1);
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void insertLegacyRows(DSLContext dsl) {
    dsl.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, script_patch_version, "
            + "script_patch_pinned_control_plane_request_id, owner_account_id, status) VALUES "
            + "(101, 42, '1.0.0', 'patch-coherent', 'request-coherent', 100, 'RUNNING'), "
            + "(102, 42, '1.0.0', 'patch-only', NULL, 101, 'STOPPED'), "
            + "(103, 42, '1.0.0', NULL, 'owner-only', 102, 'STOPPED')");
  }
}
