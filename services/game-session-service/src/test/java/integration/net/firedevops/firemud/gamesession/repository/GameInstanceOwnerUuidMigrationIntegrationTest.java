package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
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
  private static final UUID OWNER_ACCOUNT_UUID =
      UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

  @Test
  void v20ToV21MigrationRetainsNumericOwnerAndEnforcesCanonicalUuidOwnership() {
    try (PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(TestContainerImages.postgres())) {
      postgres.start();
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName(postgres.getDriverClassName());
      dataSource.setUrl(postgres.getJdbcUrl());
      dataSource.setUsername(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());

      Flyway.configure()
          .dataSource(dataSource)
          .locations(MIGRATION_LOCATION)
          .target(MigrationVersion.fromVersion("20"))
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

      Flyway.configure()
          .dataSource(dataSource)
          .locations(MIGRATION_LOCATION)
          .target(MigrationVersion.fromVersion("21"))
          .load()
          .migrate();

      String legacyRowAfterMigration =
          dsl.resultQuery(
                  "SELECT ((to_jsonb(game_instance) - 'owner_account_uuid') "
                      + "- 'game_instance_uuid')::text FROM game_instances AS game_instance "
                      + "WHERE id = 7001")
              .fetchOne(0, String.class);
      assertThat(legacyRowAfterMigration).isEqualTo(legacyRowBeforeMigration);
      Record legacyStorage =
          dsl.fetchOne(
              "SELECT owner_account_id, owner_account_uuid FROM game_instances WHERE id = 7001");
      assertThat(legacyStorage.get("owner_account_id", Long.class))
          .isEqualTo(LEGACY_OWNER_ACCOUNT_ID);
      assertThat(legacyStorage.get("owner_account_uuid", UUID.class)).isNull();

      insertUuidOwnedGameInstance(dsl, 7002L, 77L, OWNER_ACCOUNT_UUID, "RUNNING");
      insertUuidOwnedGameInstance(dsl, 7003L, 78L, OWNER_ACCOUNT_UUID, "RUNNING");
      insertUuidOwnedGameInstance(dsl, 7004L, 77L, OWNER_ACCOUNT_UUID, "STOPPED");

      Record canonicalStorage =
          dsl.fetchOne(
              "SELECT owner_account_id, owner_account_uuid FROM game_instances WHERE id = 7002");
      assertThat(canonicalStorage.get("owner_account_id", Long.class)).isNull();
      assertThat(canonicalStorage.get("owner_account_uuid", UUID.class))
          .isEqualTo(OWNER_ACCOUNT_UUID);

      assertThatThrownBy(
              () -> insertUuidOwnedGameInstance(dsl, 7005L, 77L, OWNER_ACCOUNT_UUID, "RUNNING"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("uq_game_instances_running_tenant_owner_uuid");

      assertThatThrownBy(
              () ->
                  insertUuidOwnedGameInstance(
                      dsl,
                      7006L,
                      79L,
                      UUID.fromString("00000000-0000-0000-0000-000000000000"),
                      "STOPPED"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("game_instances_owner_identity_present_and_valid");

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO game_instances (id, tenant_id, runtime_version, status) "
                          + "VALUES (7007, 79, '1.0.0', 'STOPPED')"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("game_instances_owner_identity_present_and_valid");
    }
  }

  private static void insertUuidOwnedGameInstance(
      DSLContext dsl, long id, long tenantId, UUID ownerAccountUuid, String status) {
    dsl.execute(
        "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_uuid, status) "
            + "VALUES (?, ?, '1.0.0', ?, ?)",
        id,
        tenantId,
        ownerAccountUuid,
        status);
  }
}
