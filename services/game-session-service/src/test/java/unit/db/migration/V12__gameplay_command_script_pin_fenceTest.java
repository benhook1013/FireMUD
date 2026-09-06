package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V12__gameplay_command_script_pin_fenceTest {
  @Test
  void addsCoherentNullableCommandScriptPinFence() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V12__gameplay_command_script_pin_fence.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    String normalized = migration.replaceAll("\\s+", " ").trim();
    assertThat(normalized)
        .contains("ADD COLUMN script_pin_epoch bigint")
        .contains("ADD COLUMN script_pin_control_plane_request_id character varying(128)")
        .doesNotContain("idx_gameplay_command_recovery_accepted_unstaged")
        .contains(
            "UPDATE gameplay_command SET script_patch_version = NULL WHERE upper(btrim(source_type)) = 'AUTOMATION' AND completed_at IS NULL AND NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL;")
        .contains(
            "UPDATE gameplay_command SET script_patch_version = NULL WHERE upper(btrim(source_type)) = 'PLAYER' AND completed_at IS NULL AND NULLIF(regexp_replace(script_patch_version, '[[:space:]]', '', 'g'), '') IS NOT NULL;")
        .contains("ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent")
        .contains("completed_at IS NOT NULL AND script_pin_epoch IS NULL")
        .contains("upper(btrim(source_type)) = 'PLAYER'")
        .contains("NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL")
        .contains("script_pin_epoch > 0")
        .contains("regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g')")
        .contains(") /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;");
    assertThat(
            normalized.indexOf(
                "UPDATE gameplay_command SET script_patch_version = NULL WHERE upper(btrim(source_type)) = 'PLAYER'"))
        .isLessThan(
            normalized.indexOf("ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent"));
    assertThat(
            normalized.indexOf(
                "UPDATE gameplay_command SET script_patch_version = NULL WHERE upper(btrim(source_type)) = 'AUTOMATION'"))
        .isLessThan(
            normalized.indexOf("ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent"));
  }
}
