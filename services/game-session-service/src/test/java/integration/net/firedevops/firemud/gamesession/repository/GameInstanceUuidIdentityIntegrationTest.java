package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SuppressWarnings("resource")
@Testcontainers(disabledWithoutDocker = true)
class GameInstanceUuidIdentityIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final long RETAINED_INSTANCE_ID = 4_242L;
  private static final long RETAINED_TENANT_ID = 77L;
  private static final long RETAINED_OWNER_ID = 9_007_199_254_740_993L;
  private static final String OWNER_UUID = "123e4567-e89b-12d3-a456-426614174000";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Test
  void freshRepositoryIdentityIsPersistedAndRetainedRowsRemainUnmapped() {
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
      postgres.start();
      DriverManagerDataSource dataSource = dataSource(postgres);
      Flyway.configure()
          .dataSource(dataSource)
          .locations(MIGRATION_LOCATION)
          .target(MigrationVersion.fromVersion("10"))
          .load()
          .migrate();

      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, script_patch_version, "
              + "script_pin_epoch, script_patch_pinned_control_plane_request_id, "
              + "owner_account_id, status, row_version) "
              + "VALUES (?, ?, 'retained-runtime', 'retained-patch', 3, 'retained-pin-request', "
              + "?, 'STOPPED', 17)",
          RETAINED_INSTANCE_ID,
          RETAINED_TENANT_ID,
          RETAINED_OWNER_ID);
      String retainedBeforeMigration =
          dsl.resultQuery(
                  "SELECT to_jsonb(game_instance)::text FROM game_instances AS game_instance "
                      + "WHERE id = ?",
                  RETAINED_INSTANCE_ID)
              .fetchOne(0, String.class);

      Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();

      String retainedAfterMigration =
          dsl.resultQuery(
                  "SELECT (to_jsonb(game_instance) - 'owner_account_uuid' "
                      + "- 'game_instance_uuid')::text "
                      + "FROM game_instances AS game_instance WHERE id = ?",
                  RETAINED_INSTANCE_ID)
              .fetchOne(0, String.class);
      assertThat(retainedAfterMigration).isEqualTo(retainedBeforeMigration);
      Record retainedUuid =
          dsl.fetchOne(
              "SELECT game_instance_uuid FROM game_instances WHERE id = ?", RETAINED_INSTANCE_ID);
      assertThat(retainedUuid.get("game_instance_uuid", UUID.class)).isNull();
      Record retainedOwnerUuid =
          dsl.fetchOne(
              "SELECT owner_account_uuid FROM game_instances WHERE id = ?", RETAINED_INSTANCE_ID);
      assertThat(retainedOwnerUuid.get("owner_account_uuid", UUID.class)).isNull();

      GameInstanceRepository repository = new GameInstanceRepository(dsl);
      GameInstance retained = repository.findById(RETAINED_INSTANCE_ID).orElseThrow();
      assertThat(retained.getGameInstanceUuid()).isNull();
      assertThatThrownBy(
              () ->
                  repository.requireCanonicalGameInstanceUuidByTenantIdAndPrivateId(
                      RETAINED_TENANT_ID, RETAINED_INSTANCE_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no canonical UUID mapping");
      assertThatThrownBy(
              () ->
                  repository.requireCanonicalGameInstanceUuidByTenantIdAndPrivateId(
                      RETAINED_TENANT_ID + 1, RETAINED_INSTANCE_ID))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not found in tenant scope");

      GameInstance fresh = new GameInstance();
      fresh.setTenantId(RETAINED_TENANT_ID);
      fresh.setRuntimeVersion("fresh-runtime");
      fresh.setOwnerAccountId(OWNER_UUID);
      fresh.setStatus("STOPPED");
      GameInstance saved = repository.save(fresh);
      UUID persistedUuid = saved.getGameInstanceUuid();
      assertThat(persistedUuid).isNotNull().isNotEqualTo(NIL_UUID);
      assertThat(persistedUuid.toString())
          .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
      assertThat(
              repository.requireCanonicalGameInstanceUuidByTenantIdAndPrivateId(
                  RETAINED_TENANT_ID, saved.getId()))
          .isEqualTo(persistedUuid.toString());
      assertThat(
              dsl.resultQuery(
                      "SELECT game_instance_uuid FROM game_instances WHERE id = ?", saved.getId())
                  .fetchOne(0, UUID.class))
          .isEqualTo(persistedUuid);

      saved.setStatus("RUNNING");
      GameInstance updated = repository.save(saved);
      assertThat(updated.getGameInstanceUuid()).isEqualTo(persistedUuid);
      assertThat(repository.findById(saved.getId()).orElseThrow().getGameInstanceUuid())
          .isEqualTo(persistedUuid);

      GameInstance attemptedIdentityRemoval = repository.findById(saved.getId()).orElseThrow();
      attemptedIdentityRemoval.setGameInstanceUuid(null);
      attemptedIdentityRemoval.setStatus("STOPPED");
      assertThatThrownBy(() -> repository.save(attemptedIdentityRemoval))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Failed to update game_instance");

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET game_instance_uuid = NULL WHERE id = ?",
                      saved.getId()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Game Session game_instance_uuid identity is immutable");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET game_instance_uuid = ? WHERE id = ?",
                      UUID.fromString("123e4567-e89b-12d3-a456-426614174099"),
                      saved.getId()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Game Session game_instance_uuid identity is immutable");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET game_instance_uuid = ? WHERE id = ?",
                      UUID.fromString("123e4567-e89b-12d3-a456-426614174099"),
                      RETAINED_INSTANCE_ID))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Game Session game_instance_uuid identity is immutable");
      assertThat(
              dsl.resultQuery(
                      "SELECT game_instance_uuid FROM game_instances WHERE id = ?", saved.getId())
                  .fetchOne(0, UUID.class))
          .isEqualTo(persistedUuid);
      assertThat(
              dsl.resultQuery(
                      "SELECT game_instance_uuid FROM game_instances WHERE id = ?",
                      RETAINED_INSTANCE_ID)
                  .fetchOne(0, UUID.class))
          .isNull();

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO game_instances (tenant_id, runtime_version, "
                          + "owner_account_uuid, status, game_instance_uuid) "
                          + "VALUES (?, 'duplicate-runtime', ?, 'STOPPED', ?)",
                      RETAINED_TENANT_ID,
                      UUID.fromString(OWNER_UUID),
                      persistedUuid))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("uq_game_instances_game_instance_uuid");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO game_instances (tenant_id, runtime_version, "
                          + "owner_account_uuid, status, game_instance_uuid) "
                          + "VALUES (?, 'nil-runtime', ?, 'STOPPED', ?)",
                      RETAINED_TENANT_ID,
                      UUID.fromString(OWNER_UUID),
                      NIL_UUID))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("game_instances_game_instance_uuid_non_nil");
    }
  }

  private static DriverManagerDataSource dataSource(PostgreSQLContainer<?> postgres) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }
}
