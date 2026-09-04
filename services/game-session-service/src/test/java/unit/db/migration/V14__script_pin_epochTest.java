package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V14__script_pin_epochTest {
  @Test
  void backfillsOnlyGenuineLegacyPinsAndEnforcesCompleteTuple() throws IOException {
    String migration;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("db/migration/V14__script_pin_epoch.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .contains("ADD COLUMN script_pin_epoch bigint")
        .contains(
            "NULLIF(BTRIM(script_patch_pinned_control_plane_request_id), '') IS NOT NULL")
        .contains("script_pin_epoch = CASE")
        .contains("THEN 1")
        .contains("ELSE NULL")
        .contains("ADD CONSTRAINT game_instances_script_pin_tuple_coherent")
        .contains("script_pin_epoch IS NULL")
        .contains("script_pin_epoch IS NOT NULL")
        .contains("script_pin_epoch > 0");
    assertThat(migration.indexOf("UPDATE game_instances")).isLessThan(
        migration.indexOf("ADD CONSTRAINT game_instances_script_pin_tuple_coherent"));
  }
}
