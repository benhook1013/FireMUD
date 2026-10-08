package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SuppressWarnings("resource")
@Testcontainers(disabledWithoutDocker = true)
class ExactScriptPatchBaseMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Test
  void migrationRetainsLegacyScriptProvenanceWithoutBackfillingMutableRuntimeVersion() {
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
          .target(MigrationVersion.fromVersion("2"))
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      dsl.execute(
          "INSERT INTO gameplay_command ("
              + "id, command_id, tenant_id, game_instance_id, session_id, command_name, "
              + "sanitized_command_text, requires_solo_tick, execution_outcome, gameplay_result, "
              + "accepted_at, completed_at, script_patch_version, enqueue_seq) "
              + "VALUES (1, 'legacy-command-1', 1, 7, 11, 'LOOK', 'look', false, "
              + "'COMPLETED', 'APPLIED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
              + "'legacy-patch', 1)");
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, version_id, "
              + "script_patch_version, script_pin_epoch, "
              + "script_patch_pinned_control_plane_request_id, owner_account_id, status) "
              + "VALUES (7, 1, '100', 100, 'legacy-patch', 1, 'legacy-request', 9, 'RUNNING')");
      dsl.execute(
          "INSERT INTO script_pin_operation (tenant_id, game_instance_id, "
              + "control_plane_request_id, operation_kind, target_script_patch_version, "
              + "expected_pin_kind, actor_principal, reason, mutation_digest, outcome, "
              + "resulting_script_patch_version, resulting_script_pin_epoch) "
              + "VALUES (1, 7, 'legacy-request', 'SET', 'legacy-patch', 'EXPECT_UNPINNED', "
              + "'operator', 'legacy pin', 'legacy-digest', 'COMMITTED', 'legacy-patch', 1)");
      dsl.execute(
          "INSERT INTO remote_command_coordinator (id, coordinator_id, tenant_id, command_id, "
              + "origin_game_instance_id, origin_region_id, origin_region_epoch, "
              + "target_game_instance_id, target_region_id, target_region_epoch, target_due_tick_id, "
              + "origin_deadline_region_epoch, origin_deadline_tick_id, state, late_result_policy, "
              + "updated_at, followup_id, script_patch_version) VALUES (1, 'coord-1', 1, 'cmd-1', "
              + "7, 'region-a', 1, 9, 'region-b', 1, 2, 1, 3, 'PENDING_REMOTE', 'ignore', "
              + "CURRENT_TIMESTAMP, 'followup-1', 'legacy-patch')");
      dsl.execute(
          "INSERT INTO remote_followup (id, followup_id, tenant_id, origin_game_instance_id, "
              + "origin_region_id, origin_region_epoch, target_game_instance_id, target_region_id, "
              + "target_region_epoch, due_tick_id, effect_key, status, created_at, updated_at, "
              + "script_patch_version, claim_target_aggregate) VALUES (1, 'followup-1', 1, 7, "
              + "'region-a', 1, 9, 'region-b', 1, 2, 'effect-1', 'SCHEDULED', CURRENT_TIMESTAMP, "
              + "CURRENT_TIMESTAMP, 'legacy-patch', 'entity:npc-1')");
      dsl.execute(
          "INSERT INTO remote_followup_result (id, result_id, tenant_id, coordinator_id, "
              + "followup_id, origin_region_id, origin_region_epoch, target_region_id, "
              + "target_region_epoch, outcome, observed_at, script_patch_version, "
              + "origin_game_instance_id, target_game_instance_id) VALUES (1, 'result-1', 1, "
              + "'coord-1', 'followup-1', 'region-a', 1, 'region-b', 1, 'REMOTE_APPLIED', "
              + "CURRENT_TIMESTAMP, 'legacy-patch', 7, 9)");

      Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();

      var legacyGameplayCommand =
          dsl.fetchOne(
              "SELECT script_patch_version, script_patch_base_version_id, execution_outcome "
                  + "FROM gameplay_command WHERE command_id = 'legacy-command-1'");
      assertThat(legacyGameplayCommand).isNotNull();
      assertThat(legacyGameplayCommand.get("script_patch_version", String.class))
          .isEqualTo("legacy-patch");
      assertThat(legacyGameplayCommand.get("script_patch_base_version_id", Long.class)).isNull();
      assertThat(legacyGameplayCommand.get("execution_outcome", String.class))
          .isEqualTo("COMPLETED");
      assertThat(
              dsl.fetchValue(
                  "SELECT script_patch_base_version_id FROM game_instances WHERE id = 7",
                  Long.class))
          .isNull();
      assertThat(
              dsl.fetchValue(
                  "SELECT validated_base_version_id FROM script_pin_operation "
                      + "WHERE control_plane_request_id = 'legacy-request'",
                  Long.class))
          .isNull();
      assertThat(
              dsl.fetchValue(
                  "SELECT previous_script_patch_base_version_id FROM script_pin_operation "
                      + "WHERE control_plane_request_id = 'legacy-request'",
                  Long.class))
          .isNull();
      assertThat(
              dsl.fetchValue(
                  "SELECT resulting_script_patch_base_version_id FROM script_pin_operation "
                      + "WHERE control_plane_request_id = 'legacy-request'",
                  Long.class))
          .isNull();
      assertThat(
              dsl.fetchValue(
                  "SELECT script_patch_base_version_id FROM remote_command_coordinator WHERE id = 1",
                  Long.class))
          .isNull();
      assertThat(
              dsl.fetchValue(
                  "SELECT script_patch_base_version_id FROM remote_followup WHERE id = 1",
                  Long.class))
          .isNull();
      assertThat(
              dsl.fetchValue(
                  "SELECT script_patch_base_version_id FROM remote_followup_result WHERE id = 1",
                  Long.class))
          .isNull();
    }
  }
}
