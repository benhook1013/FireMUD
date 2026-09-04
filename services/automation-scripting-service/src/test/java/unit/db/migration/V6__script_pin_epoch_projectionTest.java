package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class V6__script_pin_epoch_projectionTest {
  @Test
  void createsNullablePinEpochForProjectionRows() throws IOException {
    String migration;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("db/migration/V6__script_pin_epoch_projection.sql")) {
      assertThat(stream).isNotNull();
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(migration)
        .containsPattern(
            Pattern.quote("ALTER TABLE script_patch_pin_projections")
                + "\\s+ADD COLUMN script_pin_epoch BIGINT;");

    assertThat(migration)
        .contains(
            "UPDATE script_patch_pin_projections",
            "SET last_observed_control_plane_request_id = ''",
            "ADD CONSTRAINT ck_script_patch_pin_projections_pin_tuple CHECK",
            "script_pin_epoch IS NULL OR script_pin_epoch = 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL",
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL");
  }
}
