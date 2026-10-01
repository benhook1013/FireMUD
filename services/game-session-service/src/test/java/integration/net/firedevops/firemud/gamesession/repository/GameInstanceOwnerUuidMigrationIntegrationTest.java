package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapperImpl;
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
class GameInstanceOwnerUuidMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final long LEGACY_OWNER_ACCOUNT_ID = 9_007_199_254_740_993L;
  private static final String OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174000";

  @Test
  void migrationPreservesNumericHistoryAndStoresCanonicalOwnerUuid() {
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
      postgres.start();
      DriverManagerDataSource dataSource = dataSource(postgres);
      Flyway.configure()
          .dataSource(dataSource)
          .locations(MIGRATION_LOCATION)
          .target(MigrationVersion.fromVersion("9"))
          .load()
          .migrate();

      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id, status) "
              + "VALUES (7001, 77, '1.0.0', ?, 'RUNNING')",
          LEGACY_OWNER_ACCOUNT_ID);
      String legacyRowBeforeMigration =
          dsl.resultQuery(
                  "SELECT to_jsonb(game_instance)::text FROM game_instances AS game_instance "
                      + "WHERE id = 7001")
              .fetchOne(0, String.class);

      Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();

      String legacyRowAfterMigration =
          dsl.resultQuery(
                  "SELECT (to_jsonb(game_instance) - 'owner_account_uuid')::text "
                      + "FROM game_instances AS game_instance WHERE id = 7001")
              .fetchOne(0, String.class);
      assertThat(legacyRowAfterMigration).isEqualTo(legacyRowBeforeMigration);
      Record legacyStorage =
          dsl.fetchOne(
              "SELECT owner_account_id, owner_account_uuid FROM game_instances WHERE id = 7001");
      assertThat(legacyStorage.get("owner_account_id", Long.class))
          .isEqualTo(LEGACY_OWNER_ACCOUNT_ID);
      assertThat(legacyStorage.get("owner_account_uuid", UUID.class)).isNull();

      GameInstanceRepository repository = new GameInstanceRepository(dsl);
      GameInstanceMapper mapper = new GameInstanceMapperImpl();
      var retained = repository.findById(7001L).orElseThrow();
      assertThat(retained.getOwnerAccountId()).isNull();
      assertThat(retained.getLegacyOwnerAccountId()).isEqualTo(LEGACY_OWNER_ACCOUNT_ID);
      assertThat(mapper.toDto(retained).ownerAccountId()).isNull();

      retained.setStatus("STOPPED");
      repository.save(retained);
      assertThat(
              dsl.resultQuery("SELECT owner_account_id FROM game_instances WHERE id = 7001")
                  .fetchOne(0, Long.class))
          .isEqualTo(LEGACY_OWNER_ACCOUNT_ID);

      GameInstance uuidOwned = new GameInstance();
      uuidOwned.setTenantId(77L);
      uuidOwned.setRuntimeVersion("1.0.0");
      uuidOwned.setStatus("RUNNING");
      uuidOwned.setOwnerAccountId(OWNER_ACCOUNT_UUID);
      GameInstance saved = repository.save(uuidOwned);
      Record uuidStorage =
          dsl.fetchOne(
              "SELECT owner_account_id, owner_account_uuid FROM game_instances WHERE id = ?",
              saved.getId());
      assertThat(uuidStorage.get("owner_account_id", Long.class)).isNull();
      assertThat(uuidStorage.get("owner_account_uuid", UUID.class))
          .isEqualTo(UUID.fromString(OWNER_ACCOUNT_UUID));
      assertThat(repository.findById(saved.getId()).orElseThrow().getOwnerAccountId())
          .isEqualTo(OWNER_ACCOUNT_UUID);

      assertThatThrownBy(
              () -> {
                GameInstance duplicate = new GameInstance();
                duplicate.setTenantId(77L);
                duplicate.setRuntimeVersion("1.0.0");
                duplicate.setStatus("RUNNING");
                duplicate.setOwnerAccountId(OWNER_ACCOUNT_UUID);
                repository.save(duplicate);
              })
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("uq_game_instances_running_tenant_owner_uuid");

      assertThat(dsl.fetch("SELECT indexname FROM pg_indexes WHERE tablename = 'game_instances'"))
          .extracting(row -> row.get("indexname", String.class))
          .contains(
              "uq_game_instances_running_tenant_owner",
              "uq_game_instances_running_tenant_owner_uuid");

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO game_instances (id, tenant_id, runtime_version, "
                          + "owner_account_uuid, status) VALUES "
                          + "(7002, 77, '1.0.0', '00000000-0000-0000-0000-000000000000', "
                          + "'STOPPED')"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("game_instances_owner_identity_present_and_valid");
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
