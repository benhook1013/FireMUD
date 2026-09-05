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

    assertThat(migration)
        .contains("ADD COLUMN script_pin_epoch bigint")
        .contains("ADD COLUMN script_pin_control_plane_request_id character varying(128)")
        .contains("UPDATE gameplay_command")
        .contains("SET script_patch_version = NULL")
        .contains("upper(btrim(source_type)) = 'AUTOMATION'")
        .contains(
            "NULLIF(regexp_replace(remote_followup_id, '[[:space:]]', '', 'g'), '') IS NULL")
        .contains("ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent")
        .contains("upper(btrim(source_type)) = 'PLAYER'")
        .contains("script_pin_epoch > 0")
        .contains("regexp_replace(script_pin_control_plane_request_id, '[[:space:]]', '', 'g')");
    assertThat(migration.indexOf("UPDATE gameplay_command"))
        .isLessThan(migration.indexOf("ADD CONSTRAINT gameplay_command_script_pin_tuple_coherent"));
  }
}
