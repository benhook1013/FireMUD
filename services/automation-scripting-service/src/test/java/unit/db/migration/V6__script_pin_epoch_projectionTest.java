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
            "SET script_pin_epoch = NULL,",
            "observed_pinned_script_patch_version = '',",
            "last_observed_control_plane_request_id = ''",
            "ADD CONSTRAINT ck_script_patch_pin_projections_pin_tuple CHECK",
            "script_pin_epoch IS NULL",
            "NULLIF(BTRIM(observed_pinned_script_patch_version), '') IS NULL",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL",
            "script_pin_epoch IS NOT NULL",
            "script_pin_epoch > 0",
            "NULLIF(BTRIM(observed_pinned_script_patch_version), '') IS NOT NULL",
            "NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL",
            "/* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */");

    int constraintIndex =
        migration.indexOf("ADD CONSTRAINT ck_script_patch_pin_projections_pin_tuple");
    assertThat(migration.substring(constraintIndex))
        .doesNotContain("script_pin_epoch = 0", "script_pin_epoch IS NULL OR script_pin_epoch = 0");

    assertThat(migration.indexOf("UPDATE script_patch_pin_projections"))
        .isLessThan(migration.indexOf("ADD CONSTRAINT ck_script_patch_pin_projections_pin_tuple"));
  }
}
