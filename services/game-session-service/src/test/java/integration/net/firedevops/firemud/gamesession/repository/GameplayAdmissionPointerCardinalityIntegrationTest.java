package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameplayAdmissionPointerCardinalityIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Test
  void migrationRetainsDuplicateVisiblePublicPointersWhenUniqueIndexBuildFails() {
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
      postgres.start();
      DataSource dataSource = dataSource(postgres);
      migrateToVersion(dataSource, "6");
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      insertPointer(dsl, "demo", "production", 17L, 71L, true, true);
      insertPointer(dsl, "sandbox", "production", 17L, 72L, true, true);

      assertThatThrownBy(() -> migrateToLatest(dataSource))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("uq_gameplay_admission_pointer_visible_public_tenant")
          .hasStackTraceContaining("tenant_id")
          .hasStackTraceContaining("17");
      assertThat(
              dsl.fetchValue(
                  "SELECT count(*) FROM gameplay_admission_pointer "
                      + "WHERE tenant_id = 17 AND visible AND public_production_realm",
                  Long.class))
          .isEqualTo(2L);
      assertThat(
              dsl.fetchValue(
                  "SELECT count(*) FROM pg_indexes "
                      + "WHERE schemaname = 'public' "
                      + "AND indexname = 'uq_gameplay_admission_pointer_visible_public_tenant'",
                  Long.class))
          .isEqualTo(0L);
    }
  }

  @Test
  void concurrentVisiblePublicPointerWritersCannotBothCommitForOneTenant() throws Exception {
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
      postgres.start();
      DataSource dataSource = dataSource(postgres);
      migrateToLatest(dataSource);

      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<Boolean> first =
            executor.submit(() -> insertConcurrentPointer(dataSource, ready, start, "demo", 21L));
        Future<Boolean> second =
            executor.submit(
                () -> insertConcurrentPointer(dataSource, ready, start, "sandbox", 22L));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successfulWriters =
            (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
        assertThat(successfulWriters).isEqualTo(1);
      }

      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      insertPointer(dsl, "hidden", "production", 17L, 23L, false, true);
      insertPointer(dsl, "another-tenant", "production", 18L, 24L, true, true);
      assertThat(
              dsl.fetchValue(
                  "SELECT count(*) FROM gameplay_admission_pointer "
                      + "WHERE tenant_id = 17 AND public_production_realm",
                  Long.class))
          .isEqualTo(2L);
    }
  }

  private static DataSource dataSource(PostgreSQLContainer<?> postgres) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void migrateToVersion(DataSource dataSource, String version) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion(version))
        .load()
        .migrate();
  }

  private static void migrateToLatest(DataSource dataSource) {
    Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();
  }

  private static void insertPointer(
      DSLContext dsl,
      String worldSlug,
      String realmSlug,
      long tenantId,
      long gameInstanceId,
      boolean visible,
      boolean publicProductionRealm) {
    dsl.execute(
        "INSERT INTO gameplay_admission_pointer ("
            + "world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
            + "game_instance_id, pointer_version, visible, requires_character_selection, "
            + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
            + "public_production_realm, realm_id, playable_state_namespace_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, false, 'SHARED', 'ALLOW_NEW', "
            + "'integration-test', 'cardinality test', ?, ?, ?)",
        worldSlug,
        worldSlug,
        realmSlug,
        realmSlug,
        tenantId,
        gameInstanceId,
        visible,
        publicProductionRealm,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private static boolean insertConcurrentPointer(
      DataSource dataSource,
      CountDownLatch ready,
      CountDownLatch start,
      String worldSlug,
      long gameInstanceId)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for concurrent pointer start");
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO gameplay_admission_pointer ("
                    + "world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
                    + "game_instance_id, pointer_version, visible, requires_character_selection, "
                    + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
                    + "public_production_realm, realm_id, playable_state_namespace_id) "
                    + "VALUES (?, ?, 'production', 'Live Realm', 17, ?, 1, true, false, "
                    + "'SHARED', 'ALLOW_NEW', 'integration-test', 'concurrent cardinality test', "
                    + "true, ?, ?)")) {
      statement.setString(1, worldSlug);
      statement.setString(2, worldSlug);
      statement.setLong(3, gameInstanceId);
      statement.setObject(4, UUID.randomUUID());
      statement.setObject(5, UUID.randomUUID());
      statement.executeUpdate();
      return true;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) {
        return false;
      }
      throw exception;
    }
  }
}
