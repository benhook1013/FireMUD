package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V1__baselineTest {

  @Test
  void containsTheCompleteGameSessionBaselineContract() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V1__baseline.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains(
            "execution_hook character varying(128)",
            "admitted_release_bundle_id bigint",
            "admitted_version_id bigint",
            "declared_effects_json text",
            "script_pin_epoch bigint",
            "script_pin_control_plane_request_id character varying(128)",
            "CREATE SEQUENCE resume_transcript_entry_id_seq",
            "CREATE TABLE resume_transcript_entry",
            "ALTER SEQUENCE resume_transcript_entry_id_seq OWNED BY resume_transcript_entry.id",
            "expires_at timestamp with time zone",
            "CONSTRAINT resume_transcript_entry_pkey PRIMARY KEY (id)",
            "CREATE INDEX idx_resume_transcript_entry_scope_order ON resume_transcript_entry USING btree (tenant_id, game_instance_id, character_id, appended_at, id)",
            "CREATE INDEX idx_resume_transcript_entry_expiry ON resume_transcript_entry USING btree (expires_at) WHERE expires_at IS NOT NULL",
            "CREATE SEQUENCE player_command_history_id_seq",
            "CREATE TABLE player_command_history",
            "ALTER SEQUENCE player_command_history_id_seq OWNED BY player_command_history.id",
            "CONSTRAINT player_command_history_pkey PRIMARY KEY (id)",
            "CREATE INDEX idx_player_command_history_scope_order ON player_command_history USING btree (tenant_id, game_instance_id, character_id, accepted_at, id)",
            "CREATE TABLE player_command_history_retention_sweep_state",
            "singleton BOOLEAN PRIMARY KEY DEFAULT TRUE",
            "cursor_tenant_id BIGINT",
            "cursor_game_instance_id BIGINT",
            "cursor_character_id BIGINT",
            "batches_since_wrap INTEGER NOT NULL DEFAULT 0",
            "INSERT INTO player_command_history_retention_sweep_state (singleton) VALUES (TRUE)",
            "CREATE UNIQUE INDEX idx_remote_followup_target_instance_region_epoch_effect ON remote_followup USING btree (tenant_id, target_game_instance_id, target_region_id, target_region_epoch, effect_key)",
            "CREATE UNIQUE INDEX uq_gameplay_admission_pointer_runtime_target ON gameplay_admission_pointer USING btree (tenant_id, game_instance_id)",
            "ADD CONSTRAINT game_instances_script_pin_tuple_coherent CHECK",
            "ADD CONSTRAINT game_instances_unpinned_script_pin_metadata_coherent CHECK",
            "ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent CHECK",
            "CREATE INDEX idx_gameplay_command_recovery_accepted_unstaged ON gameplay_command USING btree (accepted_at, id) WHERE execution_outcome = 'ACCEPTED' AND staged_at IS NULL",
            "CREATE TABLE script_pin_operation",
            "PRIMARY KEY (tenant_id, game_instance_id, control_plane_request_id)",
            "CREATE INDEX idx_script_pin_operation_instance ON script_pin_operation (tenant_id, game_instance_id, committed_at, control_plane_request_id)");

    String scriptPinOperation =
        normalized.substring(
            normalized.indexOf("CREATE TABLE script_pin_operation"),
            normalized.indexOf("CREATE TABLE prepared_version_upgrade"));
    assertThat(scriptPinOperation)
        .contains(
            "operation_kind IN ('SET', 'ROLLBACK', 'REPIN')",
            "BTRIM(control_plane_request_id) <> ''",
            "BTRIM(target_script_patch_version) <> ''",
            "BTRIM(actor_principal) <> ''",
            "BTRIM(reason) <> ''",
            "BTRIM(mutation_digest) <> ''",
            "previous_script_patch_version IS NULL AND previous_script_pin_epoch IS NULL",
            "previous_script_patch_version IS NOT NULL AND previous_script_pin_epoch IS NOT NULL AND previous_script_pin_epoch > 0",
            "resulting_script_patch_version IS NULL AND resulting_script_pin_epoch IS NULL",
            "resulting_script_patch_version IS NOT NULL AND resulting_script_pin_epoch IS NOT NULL AND resulting_script_pin_epoch > 0",
            "expected_pin_kind = 'EXPECT_UNPINNED' AND expected_script_pin_epoch IS NULL",
            "expected_pin_kind = 'EXPECT_EPOCH' AND expected_script_pin_epoch IS NOT NULL AND expected_script_pin_epoch > 0",
            "expected_pin_kind = 'UNCONDITIONAL' AND expected_script_pin_epoch IS NULL",
            "outcome = 'COMMITTED' AND error_code IS NULL",
            "outcome = 'FAILED' AND error_code IS NOT NULL AND BTRIM(error_code) <> ''");

    String gameInstanceConstraints =
        normalized.substring(
            normalized.indexOf("ADD CONSTRAINT game_instances_script_pin_tuple_coherent"),
            normalized.indexOf("ALTER TABLE game_manifest ADD CONSTRAINT game_manifest_pkey"));
    assertThat(gameInstanceConstraints)
        .contains(
            "script_pin_epoch IS NULL",
            "script_pin_epoch IS NOT NULL",
            "script_pin_epoch > 0",
            "NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '')",
            "NULLIF(regexp_replace(script_patch_pinned_control_plane_request_id, '[[:space:]]', '', 'g'), '')",
            "script_patch_pinned_at IS NULL",
            "script_patch_pinned_by IS NULL",
            "script_patch_pinned_reason IS NULL");

    String gameplayCommandConstraint =
        normalized.substring(
            normalized.indexOf("ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent"),
            normalized.indexOf(
                "ALTER TABLE prepared_version_upgrade ADD CONSTRAINT prepared_version_upgrade_pkey"));
    assertThat(gameplayCommandConstraint)
        .contains(
            "completed_at IS NOT NULL",
            "upper(btrim(source_type)) = 'PLAYER'",
            "upper(btrim(source_type)) = 'AUTOMATION'",
            "NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL",
            "script_pin_epoch IS NULL",
            "script_pin_epoch IS NOT NULL",
            "script_pin_epoch > 0",
            "script_pin_control_plane_request_id");

    assertThat(normalized)
        .doesNotContain(
            "idx_remote_followup_target_region_epoch_effect",
            "UPDATE ",
            "NOT VALID",
            "VALIDATE CONSTRAINT",
            "CONCURRENTLY",
            "jooq ignore");
  }
}
